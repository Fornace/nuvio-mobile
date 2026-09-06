package com.nuvio.app.features.aisubtitle

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object AiSubtitleConfigStorage {
    private const val PREFERENCES_NAME = "nuvio_ai_subtitles"
    private const val ENABLED_KEY = "ai_subtitles_enabled"
    private const val BASE_URL_KEY = "ai_subtitles_base_url"
    private const val MODEL_KEY = "ai_subtitles_model"
    private const val TARGET_LANGUAGE_KEY = "ai_subtitles_target_language"
    private const val API_KEY_KEY = "ai_subtitles_api_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "nuvio.aisubtitles.credentials.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun loadEnabled(): Boolean? =
        preferences?.let { prefs ->
            val key = ProfileScopedKey.of(ENABLED_KEY)
            if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        }

    actual fun saveEnabled(enabled: Boolean) {
        preferences?.edit()
            ?.putBoolean(ProfileScopedKey.of(ENABLED_KEY), enabled)
            ?.apply()
    }

    actual fun loadBaseUrl(): String? =
        preferences?.getString(ProfileScopedKey.of(BASE_URL_KEY), null)

    actual fun saveBaseUrl(value: String) {
        preferences?.edit()
            ?.putString(ProfileScopedKey.of(BASE_URL_KEY), value)
            ?.apply()
    }

    actual fun loadModel(): String? =
        preferences?.getString(ProfileScopedKey.of(MODEL_KEY), null)

    actual fun saveModel(value: String) {
        preferences?.edit()
            ?.putString(ProfileScopedKey.of(MODEL_KEY), value)
            ?.apply()
    }

    actual fun loadTargetLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(TARGET_LANGUAGE_KEY), null)

    actual fun saveTargetLanguage(value: String) {
        preferences?.edit()
            ?.putString(ProfileScopedKey.of(TARGET_LANGUAGE_KEY), value)
            ?.apply()
    }

    actual fun loadApiKey(): String? {
        val scopedKey = ProfileScopedKey.of(API_KEY_KEY)
        val stored = preferences?.getString(scopedKey, null) ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { preferences?.edit()?.remove(scopedKey)?.apply() }
            .getOrNull()
    }

    actual fun saveApiKey(value: String?) {
        val scopedKey = ProfileScopedKey.of(API_KEY_KEY)
        val editor = preferences?.edit() ?: return
        if (value.isNullOrBlank()) {
            editor.remove(scopedKey).apply()
        } else {
            editor.putString(scopedKey, encrypt(value)).apply()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return "${cipher.iv.toBase64()}.${ciphertext.toBase64()}"
    }

    private fun decrypt(value: String): String {
        val separator = value.indexOf('.')
        require(separator > 0 && separator < value.lastIndex) { "Invalid encrypted credential" }
        val iv = value.substring(0, separator).fromBase64()
        val ciphertext = value.substring(separator + 1).fromBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ByteArray.toBase64(): String =
        Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray =
        Base64.decode(this, Base64.NO_WRAP)
}

actual object AiSubtitleFileStore {
    private var filesDir: File? = null

    fun initialize(context: Context) {
        filesDir = context.filesDir
    }

    actual suspend fun writeTranslatedSubtitle(name: String, content: String): String {
        val dir = File(filesDir, "NuvioAiSubtitles").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(content)
        return file.absolutePath
    }
}
