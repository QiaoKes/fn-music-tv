package com.fnmusic.tv.core.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredLoginProfile(
    val id: String,
    val server: String,
    val relayMode: Boolean,
    val username: String,
    val passwordSha256: String,
    val accessCode: String? = null,
    val userToken: String? = null,
    val lastUsedAt: Long,
)

@Serializable
internal data class SecureSessionPayload(
    val version: Int = CURRENT_VERSION,
    val activeProfileId: String? = null,
    val profiles: List<StoredLoginProfile> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal sealed interface SecureSessionRead {
    data object Missing : SecureSessionRead
    data class Ready(val payload: SecureSessionPayload) : SecureSessionRead
    data object Unreadable : SecureSessionRead
}

internal interface TokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
    fun readAccessCode(): String? = null
    fun writeAccessCode(accessCode: String) = Unit
    fun clearAccessCode() = Unit
    fun readSession(): SecureSessionRead = SecureSessionRead.Missing
    fun writeSession(payload: SecureSessionPayload) = Unit
    fun clearSession() = Unit
}

internal class SecureTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun read(): String? = decrypt(TOKEN, TOKEN_IV)

    override fun readAccessCode(): String? = decrypt(ACCESS_CODE, ACCESS_CODE_IV)

    override fun readSession(): SecureSessionRead {
        val encoded = when (val result = decryptResult(SESSION, SESSION_IV)) {
            DecryptionResult.Missing -> return SecureSessionRead.Missing
            DecryptionResult.Unreadable -> return SecureSessionRead.Unreadable
            is DecryptionResult.Ready -> result.value
        }
        val payload = runCatching { JSON.decodeFromString<SecureSessionPayload>(encoded) }.getOrNull()
            ?: return SecureSessionRead.Unreadable
        return if (payload.version == SecureSessionPayload.CURRENT_VERSION) {
            SecureSessionRead.Ready(payload)
        } else {
            SecureSessionRead.Unreadable
        }
    }

    private fun decrypt(valueKey: String, ivKey: String): String? = runCatching {
        val encrypted = preferences.getString(valueKey, null) ?: return null
        val iv = preferences.getString(ivKey, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun decryptResult(valueKey: String, ivKey: String): DecryptionResult {
        val encrypted = preferences.getString(valueKey, null)
        val iv = preferences.getString(ivKey, null)
        if (encrypted == null && iv == null) return DecryptionResult.Missing
        if (encrypted == null || iv == null) return DecryptionResult.Unreadable
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            DecryptionResult.Ready(
                String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8),
            )
        }.getOrDefault(DecryptionResult.Unreadable)
    }

    override fun write(token: String) = encrypt(TOKEN, TOKEN_IV, token)

    override fun writeAccessCode(accessCode: String) = encrypt(ACCESS_CODE, ACCESS_CODE_IV, accessCode)

    override fun writeSession(payload: SecureSessionPayload) {
        require(payload.version == SecureSessionPayload.CURRENT_VERSION)
        encrypt(SESSION, SESSION_IV, JSON.encodeToString(payload))
    }

    private fun encrypt(valueKey: String, ivKey: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
            .putString(valueKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit(),
        )
    }

    override fun clear() {
        check(preferences.edit().remove(TOKEN).remove(TOKEN_IV).commit())
    }

    override fun clearAccessCode() {
        check(preferences.edit().remove(ACCESS_CODE).remove(ACCESS_CODE_IV).commit())
    }

    override fun clearSession() {
        check(preferences.edit().remove(SESSION).remove(SESSION_IV).commit())
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fn_music_tv_user_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TOKEN = "token"
        const val TOKEN_IV = "iv"
        const val ACCESS_CODE = "access_code"
        const val ACCESS_CODE_IV = "access_code_iv"
        const val SESSION = "session_v1"
        const val SESSION_IV = "session_v1_iv"
    }

    private sealed interface DecryptionResult {
        data object Missing : DecryptionResult
        data class Ready(val value: String) : DecryptionResult
        data object Unreadable : DecryptionResult
    }
}
