// SPDX-License-Identifier: GPL-3.0-only
// Provider request and response behavior is derived from LDDC,
// https://github.com/chenmozhijin/LDDC, commit 1ffa0e25426e654376e5d55d854b135ae601f43b.
package com.fnmusic.tv.core.lyrics

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

object DefaultLyricsSources {
    fun create(client: OkHttpClient): List<LyricsSource> {
        val http = OkHttpLyricsHttpClient(client)
        return listOf(
            QqMusicLyricsSource(http),
            KugouLyricsSource(http),
            NeteaseLyricsSource(http),
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
            val title = decodeHtml(song.stringAt("songname").orEmpty()).takeIf(String::isNotBlank) ?: return@mapNotNull null
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                mediaId = mediaId,
                title = title,
                artists = song.arrayAt("singer").orEmpty().mapNotNull { it.asObject()?.stringAt("name")?.let(::decodeHtml) },
                album = song.stringAt("albumname")?.let(::decodeHtml),
                durationMs = song.longAt("interval")?.times(1_000L),
                instrumental = song.longAt("pure") == 1L,
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): SourceLyrics {
        val mediaId = candidate.mediaId ?: throw LyricsPayloadException("QQ candidate has no media ID")
        val root = parseObject(
            http.get(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                mapOf("songmid" to mediaId, "format" to "json", "nobase64" to "1", "g_tk" to "5381"),
                QQ_HEADERS,
            ),
        )
        val originalText = decodeLyricField(root.stringAt("lyric"))
        val translationText = decodeLyricField(root.stringAt("trans"))
        val original = LyricsTextParser.parseLrcOrPlain(decodeHtml(originalText), LyricsTrackKind.Original)
        if (!original.isNotEmpty) throw LyricsPayloadException("QQ lyrics are empty")
        return SourceLyrics(
            original = original,
            translation = translationText.takeIf(String::isNotBlank)
                ?.let(::decodeHtml)
                ?.let { LyricsTextParser.parseLrcOrPlain(it, LyricsTrackKind.Translation) }
                ?.takeIf(TimedLyricsTrack::isNotEmpty),
        )
    }

    private companion object {
        val QQ_HEADERS = mapOf("Referer" to "https://y.qq.com/")
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

    override suspend fun fetch(candidate: LyricsCandidate): SourceLyrics {
        val root = parseObject(
            http.get(
                "https://music.163.com/api/song/lyric",
                mapOf("id" to candidate.remoteId, "lv" to "1", "kv" to "1", "tv" to "1", "yv" to "1", "rv" to "1"),
                NETEASE_HEADERS,
            ),
        )
        val yrc = root.objectAt("yrc")?.stringAt("lyric").orEmpty()
        val lrc = root.objectAt("lrc")?.stringAt("lyric").orEmpty()
        val original = if (yrc.isNotBlank()) LyricsTextParser.parseYrc(yrc) else LyricsTextParser.parseLrcOrPlain(lrc, LyricsTrackKind.Original)
        if (!original.isNotEmpty) throw LyricsPayloadException("Netease lyrics are empty")
        return SourceLyrics(
            original = original,
            translation = root.objectAt("tlyric")?.stringAt("lyric")
                ?.takeIf(String::isNotBlank)
                ?.let { LyricsTextParser.parseLrcOrPlain(it, LyricsTrackKind.Translation) }
                ?.takeIf(TimedLyricsTrack::isNotEmpty),
            romanization = root.objectAt("romalrc")?.stringAt("lyric")
                ?.takeIf(String::isNotBlank)
                ?.let { LyricsTextParser.parseLrcOrPlain(it, LyricsTrackKind.Romanization) }
                ?.takeIf(TimedLyricsTrack::isNotEmpty),
        )
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
            val title = song.stringAt("SongName")?.let(::stripMarkup)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val durationSeconds = song.longAt("Duration") ?: song.longAt("SQDuration") ?: song.longAt("HQDuration")
            LyricsCandidate(
                source = id,
                remoteId = remoteId,
                title = title,
                artists = song.stringAt("SingerName").orEmpty().split("、", "/", "&").map(String::trim).filter(String::isNotBlank),
                album = song.stringAt("AlbumName")?.let(::stripMarkup),
                durationMs = durationSeconds?.times(1_000L),
                fileHash = song.stringAt("FileHash") ?: song.stringAt("SQFileHash") ?: song.stringAt("HQFileHash"),
            )
        }
    }

    override suspend fun fetch(candidate: LyricsCandidate): SourceLyrics {
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
        val lyricCandidate = search.arrayAt("candidates").orEmpty()
            .mapNotNull(JsonElement::asObject)
            .filter { it.stringAt("id") != null && it.stringAt("accesskey") != null }
            .minWithOrNull(
                compareBy<JsonObject> { row ->
                    val duration = row.longAt("duration")
                    if (duration == null || candidate.durationMs == null) Long.MAX_VALUE / 2
                    else kotlin.math.abs(duration - candidate.durationMs)
                }.thenByDescending { it.longAt("score") ?: 0L },
            ) ?: throw LyricsPayloadException("Kugou returned no lyric candidates")
        val body = parseObject(
            http.get(
                "https://lyrics.kugou.com/download",
                mapOf(
                    "ver" to "1",
                    "client" to "pc",
                    "id" to lyricCandidate.stringAt("id")!!,
                    "accesskey" to lyricCandidate.stringAt("accesskey")!!,
                    "fmt" to "lrc",
                    "charset" to "utf8",
                ),
            ),
        )
        val decoded = decodeBase64(body.stringAt("content").orEmpty())
        if (decoded.isBlank()) throw LyricsPayloadException("Kugou lyrics are empty")
        return LyricsTextParser.parseMultiTrackLrc(decoded)
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

private fun decodeLyricField(value: String?): String {
    val raw = value.orEmpty()
    if (raw.isBlank() || raw.contains('[')) return raw
    return runCatching { decodeBase64(raw) }.getOrDefault(raw)
}

private fun decodeBase64(value: String): String = try {
    Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
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
