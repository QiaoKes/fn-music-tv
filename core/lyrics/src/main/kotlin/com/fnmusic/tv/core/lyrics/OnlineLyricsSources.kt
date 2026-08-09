// SPDX-License-Identifier: GPL-3.0-only
// Provider request, transport decoding, and fallback behavior is derived from LDDC.
package com.fnmusic.tv.core.lyrics

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

object DefaultLyricsSources {
    fun create(client: OkHttpClient): List<LyricsSource> {
        val http = OkHttpLyricsHttpClient(client)
        return listOf(
            NeteaseLyricsSource(http),
            QqMusicLyricsSource(http),
            KugouLyricsSource(http),
        )
    }
}

class QqMusicLyricsSource(
    private val http: LyricsHttpClient,
) : LyricsSource {
    override val id = LyricsSourceId.QqMusic

    override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
        val root = parseObject(
            http.get(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp",
                mapOf("w" to query.keyword, "format" to "json", "p" to "1", "n" to "20", "cr" to "1", "g_tk" to "5381"),
                QQ_HEADERS,
            ),
        )
        return root.objectAt("data")?.objectAt("song")?.arrayAt("list").orEmpty().mapNotNull { item ->
            val song = item.asObject() ?: return@mapNotNull null
            val remoteId = song.stringAt("songid") ?: return@mapNotNull null
            val mediaId = song.stringAt("songmid") ?: return@mapNotNull null
            val title = decodeHtml(song.stringAt("songname").orEmpty()).takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                mediaId = mediaId,
                title = title,
                artists = song.arrayAt("singer").orEmpty()
                    .mapNotNull { it.asObject()?.stringAt("name")?.let(::decodeHtml) },
                album = song.stringAt("albumname")?.let(::decodeHtml),
                durationMs = song.longAt("interval")?.times(1_000L),
                instrumental = song.longAt("pure") == 1L,
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): SyncedLyrics = try {
        fetchNativeQrc(candidate)
    } catch (cause: CancellationException) {
        throw cause
    } catch (_: Exception) {
        fetchLrc(candidate)
    }

    private suspend fun fetchNativeQrc(candidate: LyricsCandidate): SyncedLyrics {
        val songId = candidate.remoteId.toLongOrNull()
            ?: throw LyricsPayloadException("QQ candidate has no numeric song ID")
        val body = buildJsonObject {
            put("comm", buildJsonObject {
                put("ct", 19)
                put("cv", 2111)
                put("uin", "0")
            })
            put("request", buildJsonObject {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                put("param", buildJsonObject {
                    put("albumName", encodeBase64(candidate.album.orEmpty()))
                    put("crypt", 1)
                    put("ct", 19)
                    put("cv", 2111)
                    put("interval", (candidate.durationMs ?: 0L) / 1_000L)
                    put("lrc_t", 0)
                    put("qrc", 1)
                    put("qrc_t", 0)
                    put("roma", 1)
                    put("roma_t", 0)
                    put("singerName", encodeBase64(candidate.artists.joinToString(" / ")))
                    put("songID", songId)
                    put("songName", encodeBase64(candidate.title))
                    put("trans", 1)
                    put("trans_t", 0)
                    put("type", 0)
                })
            })
        }.toString()
        val root = parseObject(http.post(QQ_MUSICU_URL, body, QQ_NATIVE_HEADERS))
        val data = root.objectAt("request")?.objectAt("data")
            ?: throw LyricsPayloadException("QQ QRC response has no data")
        val original = decodeQqNativeField(data.stringAt("lyric"))
        if (original.isBlank()) throw LyricsPayloadException("QQ QRC lyrics are empty")
        val translation = data.stringAt("trans")?.takeIf(String::isNotBlank)?.let { encrypted ->
            runCatching { decodeQqNativeField(encrypted) }.getOrNull()
        }
        val phonetic = data.stringAt("roma")?.takeIf(String::isNotBlank)?.let { encrypted ->
            runCatching { decodeQqNativeField(encrypted) }.getOrNull()
        }
        return parseLyrics(original, translation, phonetic).requireUsable("QQ QRC")
    }

    private suspend fun fetchLrc(candidate: LyricsCandidate): SyncedLyrics {
        val mediaId = candidate.mediaId ?: throw LyricsPayloadException("QQ candidate has no media ID")
        val root = parseObject(
            http.get(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                mapOf("songmid" to mediaId, "format" to "json", "nobase64" to "1", "g_tk" to "5381"),
                QQ_HEADERS,
            ),
        )
        val original = decodeHtml(decodeLyricField(root.stringAt("lyric")))
        val translation = decodeHtml(decodeLyricField(root.stringAt("trans")))
        return parseLyrics(original, translation).requireUsable("QQ LRC")
    }

    private companion object {
        const val QQ_MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        val QQ_HEADERS = mapOf("Referer" to "https://y.qq.com/")
        val QQ_NATIVE_HEADERS = mapOf(
            "Cookie" to "tmeLoginType=-1;",
            "User-Agent" to "okhttp/3.14.9",
        )
    }
}

class NeteaseLyricsSource(
    private val http: LyricsHttpClient,
) : LyricsSource {
    override val id = LyricsSourceId.Netease

    override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
        val root = parseObject(
            http.get(
                "https://music.163.com/api/search/get/web",
                mapOf("s" to query.keyword, "type" to "1", "limit" to "20", "offset" to "0"),
                NETEASE_HEADERS,
            ),
        )
        return root.objectAt("result")?.arrayAt("songs").orEmpty().mapNotNull { item ->
            val song = item.asObject() ?: return@mapNotNull null
            val remoteId = song.stringAt("id") ?: return@mapNotNull null
            val title = song.stringAt("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val artists = (song.arrayAt("artists") ?: song.arrayAt("ar")).orEmpty()
                .mapNotNull { it.asObject()?.stringAt("name") }
            val album = song.objectAt("album") ?: song.objectAt("al")
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                title = title,
                artists = artists,
                album = album?.stringAt("name"),
                durationMs = song.longAt("duration") ?: song.longAt("dt"),
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): SyncedLyrics {
        val root = parseObject(
            http.get(
                "https://music.163.com/api/song/lyric",
                mapOf("id" to candidate.remoteId, "lv" to "1", "kv" to "1", "tv" to "1", "yv" to "1", "rv" to "1"),
                NETEASE_HEADERS,
            ),
        )
        val yrc = root.objectAt("yrc")?.stringAt("lyric").orEmpty()
        val lrc = root.objectAt("lrc")?.stringAt("lyric").orEmpty()
        val original = yrc.ifBlank { lrc }
        val translation = root.objectAt("tlyric")?.stringAt("lyric")
        val phonetic = root.objectAt("romalrc")?.stringAt("lyric")
        return parseLyrics(original, translation, phonetic).requireUsable("Netease lyrics")
    }

    private companion object {
        val NETEASE_HEADERS = mapOf("Referer" to "https://music.163.com/")
    }
}

class KugouLyricsSource(
    private val http: LyricsHttpClient,
) : LyricsSource {
    override val id = LyricsSourceId.Kugou

    override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
        val root = parseObject(
            http.get(
                "https://songsearch.kugou.com/song_search_v2",
                mapOf(
                    "keyword" to query.keyword,
                    "page" to "1",
                    "pagesize" to "20",
                    "platform" to "WebFilter",
                    "userid" to "-1",
                    "clientver" to "2000",
                ),
            ),
        )
        return root.objectAt("data")?.arrayAt("lists").orEmpty().mapNotNull { item ->
            val song = item.asObject() ?: return@mapNotNull null
            val remoteId = song.stringAt("ID") ?: return@mapNotNull null
            val title = song.stringAt("SongName")?.let(::stripMarkup)?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val durationSeconds = song.longAt("Duration") ?: song.longAt("SQDuration") ?: song.longAt("HQDuration")
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                title = title,
                artists = song.stringAt("SingerName").orEmpty().split("、", "/", "&")
                    .map(String::trim).filter(String::isNotBlank),
                album = song.stringAt("AlbumName")?.let(::stripMarkup),
                durationMs = durationSeconds?.times(1_000L),
                fileHash = song.stringAt("FileHash") ?: song.stringAt("SQFileHash") ?: song.stringAt("HQFileHash"),
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): SyncedLyrics {
        val lyricCandidate = findLyricCandidate(candidate)
        return try {
            download(lyricCandidate, "krc").requireUsable("Kugou KRC")
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            download(lyricCandidate, "lrc").requireUsable("Kugou LRC")
        }
    }

    private suspend fun findLyricCandidate(candidate: LyricsCandidate): JsonObject {
        val search = parseObject(
            http.get(
                "https://lyrics.kugou.com/search",
                buildMap {
                    put("ver", "1")
                    put("man", "yes")
                    put("client", "pc")
                    put("keyword", (candidate.artists.firstOrNull()?.plus(" - ") ?: "") + candidate.title)
                    candidate.durationMs?.let { put("duration", it.toString()) }
                    candidate.fileHash?.let { put("hash", it) }
                },
            ),
        )
        return search.arrayAt("candidates").orEmpty()
            .mapNotNull(JsonElement::asObject)
            .filter { it.stringAt("id") != null && it.stringAt("accesskey") != null }
            .minWithOrNull(
                compareBy<JsonObject> { row ->
                    val duration = row.longAt("duration")
                    if (duration == null || candidate.durationMs == null) Long.MAX_VALUE / 2
                    else kotlin.math.abs(duration - candidate.durationMs)
                }.thenByDescending { it.longAt("score") ?: 0L },
            ) ?: throw LyricsPayloadException("Kugou returned no lyric candidates")
    }

    private suspend fun download(candidate: JsonObject, format: String): SyncedLyrics {
        val body = parseObject(
            http.get(
                "https://lyrics.kugou.com/download",
                mapOf(
                    "ver" to "1",
                    "client" to "pc",
                    "id" to candidate.stringAt("id")!!,
                    "accesskey" to candidate.stringAt("accesskey")!!,
                    "fmt" to format,
                    "charset" to "utf8",
                ),
            ),
        )
        val encoded = body.stringAt("content").orEmpty()
        if (encoded.isBlank()) throw LyricsPayloadException("Kugou lyrics are empty")
        val decoded = when {
            format == "krc" && body.longAt("contenttype") != 2L -> decryptKrc(encoded)
            else -> decodeBase64(encoded)
        }
        return parseLyrics(decoded)
    }
}

private val providerJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseObject(value: String): JsonObject = try {
    providerJson.parseToJsonElement(value).jsonObject
} catch (cause: Exception) {
    throw LyricsPayloadException("Invalid provider JSON", cause)
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonObject.objectAt(name: String): JsonObject? = get(name) as? JsonObject
private fun JsonObject.arrayAt(name: String): JsonArray? = get(name) as? JsonArray
private fun JsonObject.stringAt(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.longAt(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

private fun SyncedLyrics.requireUsable(label: String): SyncedLyrics =
    takeIf(SyncedLyrics::hasUsableLines) ?: throw LyricsPayloadException("$label are empty")

private fun decodeLyricField(value: String?): String {
    val raw = value.orEmpty()
    if (raw.isBlank() || raw.contains('[')) return raw
    return runCatching { decodeBase64(raw) }.getOrDefault(raw)
}

private fun decodeQqNativeField(value: String?): String {
    val raw = value.orEmpty()
    if (raw.isBlank()) return ""
    return if (raw.length % 2 == 0 && raw.all(Char::isHexDigit)) decryptQrc(raw) else decodeLyricField(raw)
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun decryptQrc(value: String): String = try {
    val encrypted = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY, "DESede"))
    inflate(cipher.doFinal(encrypted))
} catch (cause: Exception) {
    throw LyricsPayloadException("Invalid QQ QRC payload", cause)
}

private fun decryptKrc(value: String): String = try {
    val encoded = decodeBase64Bytes(value)
    if (encoded.size < KRC_HEADER.size || !encoded.copyOfRange(0, KRC_HEADER.size).contentEquals(KRC_HEADER)) {
        throw LyricsPayloadException("Invalid Kugou KRC header")
    }
    val encrypted = encoded.copyOfRange(KRC_HEADER.size, encoded.size)
    val decoded = ByteArray(encrypted.size) { index ->
        (encrypted[index].toInt() xor KRC_KEY[index % KRC_KEY.size].toInt()).toByte()
    }
    inflate(decoded)
} catch (cause: LyricsPayloadException) {
    throw cause
} catch (cause: Exception) {
    throw LyricsPayloadException("Invalid Kugou KRC payload", cause)
}

private fun inflate(value: ByteArray): String = InflaterInputStream(ByteArrayInputStream(value)).use { stream ->
    stream.readBytes().toString(Charsets.UTF_8)
}

private fun encodeBase64(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeBase64(value: String): String = decodeBase64Bytes(value).toString(Charsets.UTF_8)

private fun decodeBase64Bytes(value: String): ByteArray = try {
    Base64.getDecoder().decode(value)
} catch (cause: IllegalArgumentException) {
    throw LyricsPayloadException("Invalid base64 lyric payload", cause)
}

private fun decodeHtml(value: String): String = value
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("&#(\\d+);")) { match ->
        match.groupValues[1].toIntOrNull()?.let { codePoint -> String(Character.toChars(codePoint)) }.orEmpty()
    }

private fun stripMarkup(value: String): String = decodeHtml(value.replace(Regex("<[^>]+>"), "")).trim()

private val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)
private val KRC_HEADER = byteArrayOf('k'.code.toByte(), 'r'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte())
private val KRC_KEY = byteArrayOf(
    0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
    0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
)
