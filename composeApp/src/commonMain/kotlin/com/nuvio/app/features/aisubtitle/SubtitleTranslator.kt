package com.nuvio.app.features.aisubtitle

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AiSubtitleTranslationException(message: String) : Exception(message)

object SubtitleTranslator {
    private const val BATCH_SIZE = 50
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun translate(
        subtitle: AddonSubtitle,
        playbackHeaders: Map<String, String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TranslatedSubtitle {
        val config = AiSubtitleRepository.config.value
        val apiKey = AiSubtitleRepository.resolveApiKey()
            ?: throw AiSubtitleTranslationException("missing-api-key")

        val body = httpGetTextWithHeaders(
            url = subtitle.url,
            headers = playbackHeaders,
        )
        val cues = PlayerSubtitleCueParser.parse(body, subtitle.url)
        if (cues.isEmpty()) throw AiSubtitleTranslationException("no-cues")

        // Batch only the cues with text; blank cues pass through untouched.
        val textCueIndexes = cues.indices.filter { cues[it].text.isNotBlank() }
        val totalTranslatable = textCueIndexes.size
        var translatedCount = 0
        val results = cues.toMutableList()

        textCueIndexes.chunked(BATCH_SIZE).forEach { batchIndexes ->
            val batchCues = batchIndexes.map { results[it] }
            val translations = translateBatch(batchCues, config, apiKey)
            batchIndexes.forEachIndexed { i, cueIndex ->
                results[cueIndex] = results[cueIndex].copy(text = translations[i])
            }
            translatedCount += batchIndexes.size
            onProgress(translatedCount, totalTranslatable)
        }

        val vtt = buildWebVtt(results)
        val safeKey = "${subtitle.id}_${config.targetLanguage}".map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("")
        val path = AiSubtitleFileStore.writeTranslatedSubtitle("$safeKey.vtt", vtt)
        return TranslatedSubtitle(cues = results, localPath = path, language = config.targetLanguage)
    }

    private suspend fun translateBatch(
        cues: List<SubtitleSyncCue>,
        config: AiSubtitleConfig,
        apiKey: String,
    ): List<String> {
        val payload = buildJsonObject {
            put("model", config.model)
            // Qwen3.x reasoning models burn hundreds of thinking tokens per batch,
            // tripling latency and cost for zero subtitle-quality gain. Measured on
            // comath-qwen-38-flash: 5.9s/295tok with thinking vs 1.9s/44tok without.
            if (config.model.contains("qwen", ignoreCase = true)) {
                put("enable_thinking", false)
            }
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put(
                                "content",
                                "You are a professional subtitle translator. Translate each subtitle line into " +
                                    languageName(config.targetLanguage) +
                                    ". Reply with ONLY a JSON array of strings, exactly one translated string per " +
                                    "input line, same order, no extra text.",
                            )
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    cues.forEach { cue ->
                                        add(JsonPrimitive(cue.text.replace('\n', ' ')))
                                    }
                                }.toString(),
                            )
                        },
                    )
                },
            )
        }

        val response = httpPostJsonWithHeaders(
            url = "${config.baseUrl}/chat/completions",
            body = payload.toString(),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )
        val content = json.parseToJsonElement(response)
            .jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
            ?: throw AiSubtitleTranslationException("empty-translation-response")

        val translations = parseTranslations(content)
        if (translations.size != cues.size) {
            throw AiSubtitleTranslationException(
                "translation-count-mismatch: expected ${cues.size} got ${translations.size}",
            )
        }
        return translations
    }

    private fun parseTranslations(content: String): List<String> {
        val trimmed = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        // The model may wrap the array in prose or an object; use the outermost JSON array.
        val arrayStart = trimmed.indexOf('[')
        val arrayEnd = trimmed.lastIndexOf(']')
        if (arrayStart < 0 || arrayEnd <= arrayStart) {
            throw AiSubtitleTranslationException("translation-parse-error")
        }
        val arrayText = trimmed.substring(arrayStart, arrayEnd + 1)
        return json.parseToJsonElement(arrayText).jsonArray.map { element ->
            element.jsonPrimitive.content ?: throw AiSubtitleTranslationException("translation-parse-error")
        }
    }

    fun buildWebVtt(cues: List<SubtitleSyncCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { cue ->
            append(formatTimestamp(cue.startTimeMs))
            append(" --> ")
            append(formatTimestamp(cue.endTimeMs))
            append('\n')
            append(cue.text.trim())
            append("\n\n")
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val millis = (ms % 1000).toInt()
        fun pad2(value: Long) = value.toString().padStart(2, '0')
        fun pad3(value: Int) = value.toString().padStart(3, '0')
        return buildString {
            append(pad2(totalSeconds / 3600))
            append(':')
            append(pad2((totalSeconds % 3600) / 60))
            append(':')
            append(pad2(totalSeconds % 60))
            append('.')
            append(pad3(millis))
        }
    }

    private fun languageName(code: String): String = languageLabelForAi(code)
}

data class TranslatedSubtitle(
    val cues: List<SubtitleSyncCue>,
    val localPath: String,
    val language: String,
)

fun AddonSubtitle.toTranslatedAddonSubtitle(translated: TranslatedSubtitle): AddonSubtitle = AddonSubtitle(
    id = "ai:${id}:${translated.language}",
    url = translated.localPath,
    language = translated.language,
    display = "${languageLabelForAi(translated.language)} (AI)",
    addonName = addonName,
)

fun languageLabelForAi(code: String): String = when (code.lowercase()) {
    "it" -> "Italian"
    "en" -> "English"
    "es" -> "Spanish"
    "fr" -> "French"
    "de" -> "German"
    "pt" -> "Portuguese"
    "nl" -> "Dutch"
    "pl" -> "Polish"
    "ru" -> "Russian"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "zh" -> "Chinese"
    "ar" -> "Arabic"
    "hi" -> "Hindi"
    "tr" -> "Turkish"
    else -> code.uppercase()
}
