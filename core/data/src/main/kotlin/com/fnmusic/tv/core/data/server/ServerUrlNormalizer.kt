package com.fnmusic.tv.core.data.server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

data class NormalizedServer(
    val origin: HttpUrl,
    val apiBase: HttpUrl,
    val useHttps: Boolean,
) {
    fun resolveApi(relativePath: String): HttpUrl =
        apiBase.resolve(relativePath.removePrefix("/")) ?: error("Invalid API path")

    fun persistentApiBase(): String {
        val serializedHost = if (apiBase.host.contains(':')) "[${apiBase.host}]" else apiBase.host
        return "${apiBase.scheme}://$serializedHost:${apiBase.port}${apiBase.encodedPath}"
    }
}

data class EditableServerInput(val address: String, val useHttps: Boolean)

sealed interface ServerUrlResult {
    data class Valid(val server: NormalizedServer) : ServerUrlResult
    data class Invalid(val reason: Reason) : ServerUrlResult

    enum class Reason { Empty, UnsupportedScheme, Credentials, InvalidHost, QueryOrFragment }
}

object ServerUrlNormalizer {
    fun editableInput(input: String, currentUseHttps: Boolean): EditableServerInput {
        val value = input.trim()
        val scheme = Regex("^(https?)://", RegexOption.IGNORE_CASE).find(value)
        val useHttps = when (scheme?.groupValues?.get(1)?.lowercase()) {
            "https" -> true
            "http" -> false
            else -> currentUseHttps
        }
        var address = (if (scheme == null) value else value.substring(scheme.range.last + 1)).trimEnd('/')
        if (address.endsWith("/api/v1", ignoreCase = true)) address = address.dropLast(7).trimEnd('/')
        if (address.endsWith("/music", ignoreCase = true)) address = address.dropLast(6).trimEnd('/')
        val implicitPort = if (useHttps) HTTPS_PORT else DEFAULT_HTTP_PORT
        if (address.endsWith(":$implicitPort")) address = address.dropLast(implicitPort.toString().length + 1)
        return EditableServerInput(address, useHttps)
    }

    fun normalize(input: String, useHttps: Boolean): ServerUrlResult {
        val value = input.trim()
        if (value.isEmpty()) return ServerUrlResult.Invalid(ServerUrlResult.Reason.Empty)
        val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").find(value)?.value
        if (explicitScheme != null && explicitScheme.lowercase() !in setOf("http://", "https://")) {
            return ServerUrlResult.Invalid(ServerUrlResult.Reason.UnsupportedScheme)
        }
        val candidate = if (explicitScheme == null) "${if (useHttps) "https" else "http"}://$value" else value
        val parsed = candidate
            .toHttpUrlOrNull()
            ?: return ServerUrlResult.Invalid(ServerUrlResult.Reason.InvalidHost)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return ServerUrlResult.Invalid(ServerUrlResult.Reason.Credentials)
        }
        if (parsed.query != null || parsed.fragment != null) {
            return ServerUrlResult.Invalid(ServerUrlResult.Reason.QueryOrFragment)
        }
        val schemeHttps = parsed.scheme == "https"
        val hasExplicitPort = runCatching { URI(candidate).port >= 0 }.getOrDefault(false)
        val origin = parsed.newBuilder()
            .apply {
                if (!hasExplicitPort) {
                    port(
                        when {
                            schemeHttps -> HTTPS_PORT
                            explicitScheme != null -> HTTP_PORT
                            else -> DEFAULT_HTTP_PORT
                        },
                    )
                }
            }
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        val path = parsed.encodedPath.trimEnd('/')
        val prefix = when {
            path.endsWith("/music/api/v1") -> path
            path.endsWith("/music") -> "$path/api/v1"
            path == "/" || path.isEmpty() -> "/music/api/v1"
            else -> "$path/music/api/v1"
        }
        val apiBase = origin.newBuilder().encodedPath("$prefix/").build()
        return ServerUrlResult.Valid(NormalizedServer(origin, apiBase, schemeHttps))
    }

    private const val DEFAULT_HTTP_PORT = 5666
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
}
