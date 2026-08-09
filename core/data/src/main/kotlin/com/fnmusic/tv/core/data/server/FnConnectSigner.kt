package com.fnmusic.tv.core.data.server

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

internal interface FnConnectCredentials {
    val isConfigured: Boolean
    fun authxPrefix(): ByteArray
    fun apiKey(): ByteArray
}

internal object GeneratedFnConnectCredentials : FnConnectCredentials {
    override val isConfigured: Boolean
        get() = GeneratedFnConnectPayload.isConfigured

    override fun authxPrefix(): ByteArray = decrypt(
        ciphertext = GeneratedFnConnectPayload.encryptedPrefix(),
        nonce = GeneratedFnConnectPayload.prefixNonce(),
    )

    override fun apiKey(): ByteArray = decrypt(
        ciphertext = GeneratedFnConnectPayload.encryptedApiKey(),
        nonce = GeneratedFnConnectPayload.apiKeyNonce(),
    )

    private fun decrypt(ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        check(isConfigured) { "Connection signing configuration is unavailable." }
        val keyMask = GeneratedFnConnectPayload.keyMask()
        val contentKey = GeneratedFnConnectPayload.maskedKey()
        try {
            check(keyMask.size == contentKey.size && contentKey.size == AES_KEY_SIZE_BYTES)
            for (index in contentKey.indices) {
                contentKey[index] = (contentKey[index].toInt() xor keyMask[index].toInt()).toByte()
            }
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(contentKey, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce),
            )
            return cipher.doFinal(ciphertext)
        } finally {
            ciphertext.fill(0)
            nonce.fill(0)
            keyMask.fill(0)
            contentKey.fill(0)
        }
    }

    private const val AES_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_KEY_SIZE_BYTES = 32
    private const val GCM_TAG_SIZE_BITS = 128
}

internal class FnConnectSigner(
    private val credentials: FnConnectCredentials = GeneratedFnConnectCredentials,
    private val nonceProvider: () -> String = { Random.nextInt(100000, 1000000).toString() },
    private val timestampProvider: () -> String = { System.currentTimeMillis().toString() },
) {
    fun authx(path: String, body: String): String? {
        if (!credentials.isConfigured) return null
        val authxPrefix = credentials.authxPrefix()
        try {
            val apiKey = credentials.apiKey()
            try {
                val nonce = nonceProvider()
                val timestamp = timestampProvider()
                val digest = MessageDigest.getInstance(MD5)
                digest.update(authxPrefix)
                digest.update(SEPARATOR)
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update(SEPARATOR)
                digest.update(nonce.toByteArray(Charsets.UTF_8))
                digest.update(SEPARATOR)
                digest.update(timestamp.toByteArray(Charsets.UTF_8))
                digest.update(SEPARATOR)
                digest.update(body.md5Hex().toByteArray(Charsets.UTF_8))
                digest.update(SEPARATOR)
                digest.update(apiKey)
                return "nonce=$nonce&timestamp=$timestamp&sign=${digest.digest().toHex()}"
            } finally {
                apiKey.fill(0)
            }
        } finally {
            authxPrefix.fill(0)
        }
    }

    private companion object {
        const val MD5 = "MD5"
        val SEPARATOR = '_'.code.toByte()
    }
}

private fun String.md5Hex(): String = MessageDigest.getInstance("MD5")
    .digest(toByteArray(Charsets.UTF_8))
    .toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
