package com.fnmusic.tv.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import com.fnmusic.tv.core.model.PlaybackCredentials
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val playbackProbeErrorCodes = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
)

internal data class PlaybackStreamDiagnosis(
    val summary: String,
    val detail: String,
)

internal data class PlaybackStreamProbeResult(
    val opened: Boolean,
    val status: Int?,
    val headers: Map<String, List<String>>,
    val observation: String,
    val bytes: ByteArray,
)

private const val PROBE_BYTES = 256 * 1024
private const val HEX_PREVIEW_BYTES = 48
private const val MAX_DUMP_FILES = 4
private const val DIAGNOSTICS_DIRECTORY = "stream-diagnostics"
private const val TAG = "PlaybackStreamDiag"

@OptIn(UnstableApi::class)
internal suspend fun diagnosePlaybackStream(
    streamUrl: String,
    credentials: PlaybackCredentials?,
    cacheRoot: File,
    mediaId: String,
): PlaybackStreamDiagnosis = withContext(Dispatchers.IO) {
    val probe = performPlaybackStreamProbe(streamUrl, credentials)
    val dumpPath = writeProbeDump(cacheRoot, mediaId, probe.bytes)
    buildStreamDiagnosis(probe, credentials?.relayMode == true, dumpPath, streamUrl)
}

@OptIn(UnstableApi::class)
internal suspend fun performPlaybackStreamProbe(
    streamUrl: String,
    credentials: PlaybackCredentials?,
): PlaybackStreamProbeResult = withContext(Dispatchers.IO) {
    val requestHeaders = credentials
        ?.let { playbackRequestHeaders(it.rawAuthorization, it.accessCodeHeader, it.relayMode) }
        ?: emptyMap()
    val dataSource = DefaultHttpDataSource.Factory()
        .setUserAgent(PLAYBACK_USER_AGENT)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(10_000)
        .setDefaultRequestProperties(requestHeaders)
        .createDataSource()
    val dataSpec = DataSpec.Builder().setUri(streamUrl).build()
    val buffer = ByteArray(PROBE_BYTES)
    var read = 0
    var status: Int? = null
    var headers: Map<String, List<String>> = emptyMap()
    var observation = ""
    try {
        dataSource.open(dataSpec)
        headers = (dataSource as HttpDataSource).responseHeaders
        while (read < PROBE_BYTES) {
            val readBytes = dataSource.read(buffer, read, PROBE_BYTES - read)
            if (readBytes == C.RESULT_END_OF_INPUT) break
            read += readBytes
        }
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        status = e.responseCode
        observation = e.message ?: "HTTP ${e.responseCode}"
    } catch (e: Exception) {
        observation = "${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}"
    } finally {
        try {
            dataSource.close()
        } catch (_: Exception) {
        }
    }
    PlaybackStreamProbeResult(
        opened = status == null && observation.isEmpty(),
        status = status,
        headers = headers,
        observation = observation,
        bytes = buffer.copyOf(read),
    )
}

internal fun buildStreamDiagnosis(
    probe: PlaybackStreamProbeResult,
    relayMode: Boolean,
    dumpPath: String?,
    connection: String? = null,
): PlaybackStreamDiagnosis {
    val lines = mutableListOf<String?>()
    lines.add(if (probe.opened) "HTTP 2xx" else "HTTP ${probe.status ?: "未建立"}")
    lines.add(headerLine(probe.headers, "content-type")?.let { "type=$it" })
    headerLine(probe.headers, "content-length")?.let { lines.add("length=$it") }
    headerLine(probe.headers, "content-encoding")?.let { lines.add("encoding=$it") }
    headerLine(probe.headers, "content-range")?.let { lines.add("range=$it") }
    if (relayMode) lines.add("relay=true")
    if (probe.observation.isNotBlank()) lines.add("探测异常=${probe.observation}")
    lines.add(classifyStreamBytes(probe.bytes))
    if (probe.bytes.isNotEmpty()) lines.add("首字节=${hexPreview(probe.bytes)}")
    dumpPath?.let { lines.add("样本=$it") }
    val facts = lines.filterNotNull().joinToString(", ")
    val detail = buildString {
        append(facts)
        connection?.let { append("\n连接: $it") }
        if (probe.headers.isNotEmpty()) append("\n响应头: ${formatHeaders(probe.headers)}")
    }
    return PlaybackStreamDiagnosis(summary = facts, detail = detail)
}

private fun hexPreview(bytes: ByteArray): String =
    bytes.take(HEX_PREVIEW_BYTES).joinToString(" ") { String.format("%02X", it) }

private fun headerLine(headers: Map<String, List<String>>, name: String): String? =
    headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.joinToString(", ")
        ?.takeIf(String::isNotBlank)

private fun formatHeaders(headers: Map<String, List<String>>): String =
    headers.entries
        .filter { entry -> !entry.key.equals("authorization", ignoreCase = true) }
        .joinToString("; ") { entry -> "${entry.key}=${entry.value.joinToString(",")}" }

internal fun classifyStreamBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "响应体为空(0 字节)"
    val prefix = asciiPrefix(bytes, 12)
    return when {
        bytes[0] == '{'.code.toByte() -> "疑似 JSON 错误体"
        bytes[0] == '<'.code.toByte() -> "疑似 HTML 错误体"
        prefix.startsWith("ID3") -> id3Note(bytes)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> {
            val layer = (bytes[1].toInt() shr 1) and 0x03
            if (layer == 0x01) "MPEG 音频帧开头(Layer3)" else "MPEG 音频帧开头(Layer$layer)"
        }
        prefix.startsWith("fLaC") -> "FLAC 流"
        prefix.startsWith("OggS") -> "Ogg 容器"
        prefix.startsWith("RIFF") -> "RIFF(WAV/容器)"
        asciiPrefix(bytes, 8).takeLast(4) == "ftyp" -> "MP4 容器"
        else -> "未识别开头 ${hexPreview(bytes.take(8).toByteArray())}"
    }
}

private fun id3Note(bytes: ByteArray): String {
    val declared = if (bytes.size >= 10) {
        var size = 0
        var malformed = false
        for (index in 6..9) {
            val byte = bytes[index].toInt() and 0xFF
            if (byte and 0x80 != 0) malformed = true
            size = (size shl 7) or (byte and 0x7F)
        }
        if (malformed) {
            "ID3 标签(长度字段损坏: 非同步安全编码)"
        } else {
            "ID3 标签声明长度 $size 字节, 实际捕捉 ${bytes.size} 字节"
        }
    } else {
        "ID3 头被截断"
    }
    return "MP3($declared)"
}

private fun asciiPrefix(bytes: ByteArray, limit: Int): String =
    ByteArray(minOf(limit, bytes.size)) { index ->
        val byte = bytes[index].toInt()
        if (byte in 0x20..0x7E) bytes[index] else 0
    }.toString(Charsets.ISO_8859_1).trimEnd { it == Char(0) }

private fun writeProbeDump(cacheRoot: File, mediaId: String, bytes: ByteArray): String? = runCatching {
    if (bytes.isEmpty()) return null
    val directory = File(cacheRoot, DIAGNOSTICS_DIRECTORY).apply { mkdirs() }
    val safeMediaId = mediaId.filter { it.isLetterOrDigit() || it in "-._" }.ifEmpty { "track" }
    val path = File(directory, "stream-$safeMediaId.bin").apply { writeBytes(bytes) }.absolutePath
    val dumps = directory.listFiles()?.sortedBy(File::lastModified).orEmpty()
    dumps.take((dumps.size - MAX_DUMP_FILES).coerceAtLeast(0)).forEach { stale ->
        runCatching { stale.delete() }
    }
    path
}.getOrNull()

internal fun debugLogDiagnosis(mediaId: String, errorCodeName: String, diagnosis: PlaybackStreamDiagnosis) {
    Log.w(TAG, "$mediaId $errorCodeName:\n${diagnosis.detail}")
}
