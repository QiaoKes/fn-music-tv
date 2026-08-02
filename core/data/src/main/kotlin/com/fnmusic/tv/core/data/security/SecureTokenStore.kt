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

interface TokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
    fun readAccessCode(): String? = null
    fun writeAccessCode(accessCode: String) = Unit
    fun clearAccessCode() = Unit
}

class SecureTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun read(): String? = decrypt(TOKEN, TOKEN_IV)

    override fun readAccessCode(): String? = decrypt(ACCESS_CODE, ACCESS_CODE_IV)

    private fun decrypt(valueKey: String, ivKey: String): String? = runCatching {
        val encrypted = preferences.getString(valueKey, null) ?: return null
        val iv = preferences.getString(ivKey, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    override fun write(token: String) = encrypt(TOKEN, TOKEN_IV, token)

    override fun writeAccessCode(accessCode: String) = encrypt(ACCESS_CODE, ACCESS_CODE_IV, accessCode)

    private fun encrypt(valueKey: String, ivKey: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(valueKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(TOKEN).remove(TOKEN_IV).apply()
    }

    override fun clearAccessCode() {
        preferences.edit().remove(ACCESS_CODE).remove(ACCESS_CODE_IV).apply()
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
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fn_music_tv_user_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TOKEN = "token"
        const val TOKEN_IV = "iv"
        const val ACCESS_CODE = "access_code"
        const val ACCESS_CODE_IV = "access_code_iv"
    }
}
