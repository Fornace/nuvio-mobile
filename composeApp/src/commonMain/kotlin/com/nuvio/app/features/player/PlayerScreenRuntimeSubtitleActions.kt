package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.aisubtitle.AiSubtitleRepository
import com.nuvio.app.features.aisubtitle.AiSubtitleTranslationException
import com.nuvio.app.features.aisubtitle.SubtitleTranslator
import com.nuvio.app.features.aisubtitle.toTranslatedAddonSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    SubtitleRepository.fetchAddonSubtitles(type, videoId)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}

internal fun PlayerScreenRuntime.translateSelectedAddonSubtitle() {
    val subtitle = selectedAddonSubtitle ?: return
    AiSubtitleRepository.ensureLoaded()
    val config = AiSubtitleRepository.config.value
    if (!config.enabled) return
    if (aiTranslationState.isRunning) return
    if (!AiSubtitleRepository.hasApiKey()) {
        aiTranslationState = aiTranslationState.copy(
            isRunning = false,
            errorMessage = null,
            requiresKey = true,
        )
        return
    }
    // Skip when the selected track is already the AI translation for this language.
    if (subtitle.id.startsWith("ai:") && subtitle.id.endsWith(":${config.targetLanguage}")) return
    val existingTranslation = addonSubtitles.firstOrNull {
        it.id == "ai:${subtitle.id}:${config.targetLanguage}"
    }
    if (existingTranslation != null) {
        isUserExplicitSubtitleSelection = true
        selectedAddonSubtitleId = existingTranslation.id
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        preferredSubtitleSelectionApplied = true
        persistAddonSubtitlePreference(existingTranslation)
        playerController?.setSubtitleUri(existingTranslation.url)
        return
    }

    val sourceSubtitle = if (subtitle.id.startsWith("ai:")) {
        // Translating an AI track again: find its source addon subtitle.
        addonSubtitles.firstOrNull { "ai:${it.id}" == subtitle.id.substringBeforeLast(":") } ?: return
    } else {
        subtitle
    }

    aiTranslationState = aiTranslationState.copy(
        isRunning = true,
        done = 0,
        total = 0,
        errorMessage = null,
        requiresKey = false,
    )
    scope.launch {
        val result = runCatching {
            SubtitleTranslator.translate(
                subtitle = sourceSubtitle,
                playbackHeaders = sanitizePlaybackHeaders(activeSourceHeaders),
            ) { done, total ->
                aiTranslationState = aiTranslationState.copy(done = done, total = total)
            }
        }
        result.fold(
            onSuccess = { translated ->
                val translatedAddon = sourceSubtitle.toTranslatedAddonSubtitle(translated)
                if (addonSubtitles.none { it.id == translatedAddon.id }) {
                    addonSubtitles = addonSubtitles + translatedAddon
                }
                isUserExplicitSubtitleSelection = true
                selectedAddonSubtitleId = translatedAddon.id
                selectedSubtitleIndex = -1
                useCustomSubtitles = true
                preferredSubtitleSelectionApplied = true
                persistAddonSubtitlePreference(translatedAddon)
                playerController?.setSubtitleUri(translatedAddon.url)
                aiTranslationState = aiTranslationState.copy(isRunning = false)
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                val message = when {
                    error is AiSubtitleTranslationException && error.message == "missing-api-key" -> null
                    error is AiSubtitleTranslationException -> error.message
                    else -> error.message
                }
                aiTranslationState = aiTranslationState.copy(
                    isRunning = false,
                    errorMessage = message ?: "translation-failed",
                )
            },
        )
    }
}
