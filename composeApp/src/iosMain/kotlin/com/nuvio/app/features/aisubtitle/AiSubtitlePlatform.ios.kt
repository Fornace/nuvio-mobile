package com.nuvio.app.features.aisubtitle

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

internal actual object AiSubtitleConfigStorage {
    private const val ENABLED_KEY = "ai_subtitles_enabled"
    private const val BASE_URL_KEY = "ai_subtitles_base_url"
    private const val MODEL_KEY = "ai_subtitles_model"
    private const val TARGET_LANGUAGE_KEY = "ai_subtitles_target_language"
    private const val API_KEY_KEY = "ai_subtitles_api_key"
    private const val KEYCHAIN_SERVICE = "com.nuvio.media.aisubtitles"

    actual fun loadEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(ProfileScopedKey.of(ENABLED_KEY)) != null) {
            defaults.boolForKey(ProfileScopedKey.of(ENABLED_KEY))
        } else {
            null
        }
    }

    actual fun saveEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(ENABLED_KEY))
    }

    actual fun loadBaseUrl(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(BASE_URL_KEY))

    actual fun saveBaseUrl(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(BASE_URL_KEY))
    }

    actual fun loadModel(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(MODEL_KEY))

    actual fun saveModel(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(MODEL_KEY))
    }

    actual fun loadTargetLanguage(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(TARGET_LANGUAGE_KEY))

    actual fun saveTargetLanguage(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(TARGET_LANGUAGE_KEY))
    }

    actual fun loadApiKey(): String? = loadKeychainValue(API_KEY_KEY)

    actual fun saveApiKey(value: String?) = saveKeychainValue(API_KEY_KEY, value)

    @OptIn(ExperimentalForeignApi::class)
    private fun loadKeychainValue(key: String): String? = withKeychainQuery(key) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) return@memScoped null
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val length = CFDataGetLength(data).toInt()
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(length) { index -> bytes[index].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun saveKeychainValue(key: String, value: String?) {
        deleteKeychainValue(key)
        if (value.isNullOrBlank()) return
        withKeychainQuery(key) { query ->
            val bytes = value.encodeToByteArray().toUByteArray()
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
                ?: error("Unable to encode AI subtitle credential")
            try {
                CFDictionarySetValue(query, kSecValueData, data)
                check(SecItemAdd(query, null) == errSecSuccess) { "Unable to store AI subtitle credential" }
            } finally {
                CFRelease(data)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun deleteKeychainValue(key: String) {
        withKeychainQuery(key) { query -> SecItemDelete(query) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun <T> withKeychainQuery(
        key: String,
        block: (CFMutableDictionaryRef) -> T,
    ): T {
        val service = CFStringCreateWithCString(null, KEYCHAIN_SERVICE, kCFStringEncodingUTF8)
            ?: error("Unable to encode Keychain service")
        val account = CFStringCreateWithCString(
            null,
            ProfileScopedKey.of(key),
            kCFStringEncodingUTF8,
        ) ?: error("Unable to encode Keychain account")
        val query = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0L,
            keyCallBacks = null,
            valueCallBacks = null,
        ) ?: error("Unable to create Keychain query")
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual object AiSubtitleFileStore {
    private val directory = "${NSHomeDirectory()}/Library/Application Support/NuvioAiSubtitles"

    actual suspend fun writeTranslatedSubtitle(name: String, content: String): String {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val path = "$directory/$name"
        val bytes = content.encodeToByteArray()
        val file = fopen(path, "wb") ?: error("Unable to write translated subtitle to $path")
        try {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toLong()
                check(written == bytes.size.toLong()) { "Short write for translated subtitle at $path" }
            }
        } finally {
            fclose(file)
        }
        return path
    }
}
