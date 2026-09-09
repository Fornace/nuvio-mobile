package com.nuvio.app.features.aisubtitle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AiSubtitleConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
) {
    val isConfigured: Boolean get() = AiSubtitleConfigStorage.loadApiKey()?.isNotBlank() == true

    companion object {
        const val DEFAULT_BASE_URL = "https://llm.fornace.net"
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_TARGET_LANGUAGE = "it"
    }
}

data class AiSubtitleTranslationState(
    val isRunning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val errorMessage: String? = null,
    val requiresKey: Boolean = false,
)

internal expect object AiSubtitleConfigStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadBaseUrl(): String?
    fun saveBaseUrl(value: String)
    fun loadModel(): String?
    fun saveModel(value: String)
    fun loadTargetLanguage(): String?
    fun saveTargetLanguage(value: String)
    fun loadApiKey(): String?
    fun saveApiKey(value: String?)
}

expect object AiSubtitleFileStore {
    suspend fun writeTranslatedSubtitle(name: String, content: String): String
}

object AiSubtitleRepository {
    /** Defaults that previous builds shipped; saved copies of these migrate to the current default. */
    private val supersededDefaultModels = setOf(
        "qwen3.7-max",
        "comath-qwen-38-flash",
    )

    private val _config = MutableStateFlow(AiSubtitleConfig())
    val config: StateFlow<AiSubtitleConfig> = _config.asStateFlow()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val savedModel = AiSubtitleConfigStorage.loadModel()?.takeIf { it.isNotBlank() }
        _config.value = AiSubtitleConfig(
            enabled = AiSubtitleConfigStorage.loadEnabled()
                ?: AiSubtitleBuildConfig.API_KEY.isNotBlank(),
            baseUrl = AiSubtitleConfigStorage.loadBaseUrl()?.takeIf { it.isNotBlank() }
                ?: AiSubtitleConfig.DEFAULT_BASE_URL,
            model = savedModel?.takeIf { it !in supersededDefaultModels }
                ?: AiSubtitleConfig.DEFAULT_MODEL,
            targetLanguage = AiSubtitleConfigStorage.loadTargetLanguage()?.takeIf { it.isNotBlank() }
                ?: AiSubtitleConfig.DEFAULT_TARGET_LANGUAGE,
        )
        // Persist the migration so the model group stays stable across restarts.
        if (savedModel != null && savedModel in supersededDefaultModels) {
            setModel(AiSubtitleConfig.DEFAULT_MODEL)
        }
    }

    fun setEnabled(enabled: Boolean) {
        AiSubtitleConfigStorage.saveEnabled(enabled)
        _config.value = _config.value.copy(enabled = enabled)
    }

    fun setBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        if (normalized.isBlank()) return
        AiSubtitleConfigStorage.saveBaseUrl(normalized)
        _config.value = _config.value.copy(baseUrl = normalized)
    }

    fun setModel(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) return
        AiSubtitleConfigStorage.saveModel(normalized)
        _config.value = _config.value.copy(model = normalized)
    }

    fun setTargetLanguage(value: String) {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return
        AiSubtitleConfigStorage.saveTargetLanguage(normalized)
        _config.value = _config.value.copy(targetLanguage = normalized)
    }

    fun setApiKey(value: String?) {
        val normalized = value?.trim()
        AiSubtitleConfigStorage.saveApiKey(normalized?.takeIf { it.isNotBlank() })
    }

    fun hasApiKey(): Boolean = resolveApiKey() != null

    /** Keychain first; personal builds fall back to the gitignored build-time key. */
    fun resolveApiKey(): String? =
        AiSubtitleConfigStorage.loadApiKey()?.takeIf { it.isNotBlank() }
            ?: AiSubtitleBuildConfig.API_KEY.takeIf { it.isNotBlank() }
}
