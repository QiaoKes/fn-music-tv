package com.fnmusic.tv.update

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class UpdateClient(
    private val endpoint: String,
    private val client: OkHttpClient = defaultUpdateHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    suspend fun fetchManifest(): UpdateManifest {
        val endpointUri = validateHttpsUri(endpoint, "更新地址")
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw UpdateFailure("检查更新失败（${response.code}）")
            if (response.request.url.host != endpointUri.host) throw UpdateFailure("更新地址发生了跨域跳转")
            val contentLength = response.body.contentLength()
            if (contentLength > MAX_UPDATE_MANIFEST_BYTES) throw UpdateFailure("更新信息过大")
            val bytes = response.body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (output.size() <= MAX_UPDATE_MANIFEST_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_UPDATE_MANIFEST_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_UPDATE_MANIFEST_BYTES) throw UpdateFailure("更新信息过大")
            val dto = try {
                json.decodeFromString<UpdateManifestDto>(bytes.decodeToString())
            } catch (_: SerializationException) {
                throw UpdateFailure("更新信息格式无效")
            } catch (_: IllegalArgumentException) {
                throw UpdateFailure("更新信息格式无效")
            }
            validateManifest(dto, endpointUri)
        }
    }
}

internal fun validateManifest(dto: UpdateManifestDto, endpointUri: URI): UpdateManifest {
    if (dto.schemaVersion != 1) throw UpdateFailure("暂不支持这个更新信息版本")
    if (dto.packageName != UPDATE_PACKAGE_NAME) throw UpdateFailure("更新包名不匹配")
    if (dto.versionName.isBlank() || dto.versionName.length > 64 || dto.versionCode <= 0) {
        throw UpdateFailure("更新版本号无效")
    }
    if (dto.title.isBlank() || dto.title.length > 120 || dto.notes.length > MAX_UPDATE_NOTES_LENGTH) {
        throw UpdateFailure("更新说明格式无效")
    }
    if (dto.apk.size <= 0 || !LOWERCASE_SHA256.matches(dto.apk.sha256)) {
        throw UpdateFailure("更新文件信息无效")
    }
    val apkUri = validateHttpsUri(dto.apk.url, "APK 地址")
    if (!apkUri.host.equals(endpointUri.host, ignoreCase = true)) {
        throw UpdateFailure("APK 与更新信息必须使用同一下载域名")
    }
    if (runCatching { Instant.parse(dto.publishedAt) }.isFailure) throw UpdateFailure("发布时间无效")
    validateHttpsUri(dto.githubReleaseUrl, "GitHub Release 地址")
    return UpdateManifest(
        versionName = dto.versionName,
        versionCode = dto.versionCode,
        title = dto.title,
        notes = dto.notes,
        apkUrl = apkUri.toString(),
        apkSize = dto.apk.size,
        apkSha256 = dto.apk.sha256,
    )
}

internal fun validateHttpsUri(value: String, label: String): URI {
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw UpdateFailure("$label 无效")
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.fragment != null
    ) {
        throw UpdateFailure("$label 必须使用安全的 HTTPS 地址")
    }
    return uri
}

internal class UpdateFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")

private fun defaultUpdateHttpClient() = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

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
