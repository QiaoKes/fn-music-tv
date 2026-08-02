package com.fnmusic.tv.core.data.server

import android.util.Base64
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class ConnectionTarget(
    val server: NormalizedServer,
    val relayMode: Boolean,
)

data class ConnectionAccess(
    val encodedAccessCode: String? = null,
    val relayMode: Boolean = false,
) {
    fun headers(token: String? = null): Map<String, String> = buildMap {
        if (token != null) put("Authorization", token)
        val cookies = buildList {
            if (token != null && relayMode) add("music-token=$token")
            if (relayMode) add("mode=relay")
        }
        if (cookies.isNotEmpty()) put("Cookie", cookies.joinToString("; "))
        if (encodedAccessCode != null) {
            put("x-access-code", encodedAccessCode)
            put("x-access-source", "app")
        }
    }

    companion object {
        fun from(accessCode: String, relayMode: Boolean): ConnectionAccess {
            val encoded = accessCode.takeIf(String::isNotBlank)?.let {
                Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            return ConnectionAccess(encoded, relayMode)
        }
    }
}

class ConnectionResolver(
    private val client: OkHttpClient,
    private val fnLookupUrl: String = FN_LOOKUP_URL,
) {
    suspend fun resolve(input: String, useHttps: Boolean): ConnectionTarget {
        val trimmed = input.trim()
        if (!isFnId(trimmed)) {
            val normalized = (ServerUrlNormalizer.normalize(trimmed, useHttps) as? ServerUrlResult.Valid)?.server
                ?: throw AppException(AppError.Unknown("invalid_server"))
            return ConnectionTarget(normalized, relayMode = false)
        }
        return resolveFnId(trimmed)
    }

    suspend fun verifyAccessCode(target: ConnectionTarget, accessCode: String): ConnectionAccess {
        val access = ConnectionAccess.from(accessCode, target.relayMode)
        val request = Request.Builder()
            .url(target.server.origin.resolve(ACCESS_CODE_PATH) ?: error("Invalid access-code path"))
            .apply { access.headers().forEach(::header) }
            .get()
            .build()
        val response = client.newCall(request).await()
        response.use {
            return when {
                it.isSuccessful -> access
                accessCode.isBlank() && it.code in ACCESS_CODE_REJECTION_CODES ->
                    throw AppException(AppError.AccessCodeRequired)
                it.code in ACCESS_CODE_REJECTION_CODES -> throw AppException(AppError.InvalidAccessCode)
                else -> access
            }
        }
    }

    private suspend fun resolveFnId(fnId: String): ConnectionTarget {
        val params = lookup(fnId)
        val candidates = buildCandidates(fnId, params)
        val probeClient = client.newBuilder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .build()
        val results = coroutineScope {
            candidates.map { candidate -> async { candidate to isReachable(probeClient, candidate) } }.awaitAll()
        }
        val selected = results.firstOrNull { it.second }?.first
            ?: throw AppException(AppError.FnIdUnavailable)
        val normalized = (ServerUrlNormalizer.normalize(selected.url.toString(), selected.url.isHttps) as ServerUrlResult.Valid).server
        return ConnectionTarget(normalized, selected.relayMode)
    }

    private suspend fun lookup(fnId: String): FnConnectionParams {
        val body = "{\"fnId\":${Json.encodeToString(fnId)}}"
        val request = Request.Builder()
            .url(fnLookupUrl)
            .header("authx", authx(FN_LOOKUP_PATH, body))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = client.newCall(request).await()
        response.use {
            if (!it.isSuccessful) throw AppException(AppError.FnIdUnavailable)
            val root = runCatching { Json.parseToJsonElement(it.body.string()).jsonObject }.getOrNull()
                ?: throw AppException(AppError.FnIdUnavailable)
            if (root.int("code") != 0) throw AppException(AppError.FnIdUnavailable)
            val data = root["data"] as? JsonObject ?: throw AppException(AppError.FnIdUnavailable)
            val ports = data["port"] as? JsonObject
            return FnConnectionParams(
                internalIpv4 = data.strings("ipv4"),
                publicIpv4 = data.strings("publicIpv4"),
                publicIpv6 = data.strings("publicIpv6"),
                relayAddresses = data.strings("fn"),
                httpPort = ports?.int("httpPort") ?: DEFAULT_HTTP_PORT,
                httpsPort = ports?.int("httpsPort") ?: DEFAULT_HTTPS_PORT,
            )
        }
    }

    private suspend fun isReachable(probeClient: OkHttpClient, candidate: ProbeCandidate): Boolean {
        val request = Request.Builder().url(candidate.url)
            .apply { if (candidate.relayMode) header("Cookie", "mode=relay") }
            .get()
            .build()
        return try {
            probeClient.newCall(request).await().use { true }
        } catch (_: IOException) {
            false
        }
    }

    internal fun buildCandidates(fnId: String, params: FnConnectionParams): List<ProbeCandidate> = buildList {
        fun addIp(host: String, ipv6: Boolean = false) {
            val serialized = if (ipv6) "[$host]" else host
            addCandidate("http://$serialized:${params.httpPort}", relayMode = false)
            addCandidate("https://$serialized:${params.httpsPort}", relayMode = false)
        }
        params.internalIpv4.forEach { addIp(it) }
        params.publicIpv6.forEach { addIp(it, ipv6 = true) }
        params.publicIpv4.forEach { addIp(it) }
        val relays = params.relayAddresses.ifEmpty { listOf("$fnId.5ddd.com") }
        relays.forEach { relay ->
            val host = relay.substringBeforeLast(':').takeIf { relay.substringAfterLast(':').toIntOrNull() != null } ?: relay
            addCandidate("https://$host", relayMode = true)
        }
    }

    private fun MutableList<ProbeCandidate>.addCandidate(value: String, relayMode: Boolean) {
        value.toHttpUrlOrNull()?.let { add(ProbeCandidate(it, relayMode)) }
    }

    private fun authx(path: String, body: String): String {
        val nonce = Random.nextInt(100000, 1000000).toString()
        val timestamp = System.currentTimeMillis().toString()
        val raw = listOf(AUTHX_PREFIX, path, nonce, timestamp, body.md5(), API_KEY).joinToString("_")
        return "nonce=$nonce&timestamp=$timestamp&sign=${raw.md5()}"
    }

    companion object {
        fun isFnId(value: String): Boolean = FN_ID.matches(value.trim())

        private val FN_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{5,}$")
        private const val ACCESS_CODE_PATH = "access_code_verify"
        private val ACCESS_CODE_REJECTION_CODES = setOf(401, 403, 429)
        private const val FN_LOOKUP_PATH = "/api/v1/fn/con"
        private const val FN_LOOKUP_URL = "https://5ddd.com$FN_LOOKUP_PATH"
        private const val DEFAULT_HTTP_PORT = 5666
        private const val DEFAULT_HTTPS_PORT = 5667
        private const val AUTHX_PREFIX = "REMOVED"
        private const val API_KEY = "REMOVED"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal data class FnConnectionParams(
    val internalIpv4: List<String>,
    val publicIpv4: List<String>,
    val publicIpv6: List<String>,
    val relayAddresses: List<String>,
    val httpPort: Int,
    val httpsPort: Int,
)

internal data class ProbeCandidate(val url: HttpUrl, val relayMode: Boolean)

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

private fun String.md5(): String = MessageDigest.getInstance("MD5")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
