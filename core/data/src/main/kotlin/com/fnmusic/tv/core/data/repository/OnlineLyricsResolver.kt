// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.data.api.ApiDecoder
import com.fnmusic.tv.core.data.local.CachedMatchedLyricEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.lyrics.LyricsCandidate
import com.fnmusic.tv.core.lyrics.LyricsCandidateScorer
import com.fnmusic.tv.core.lyrics.LyricsContentQuality
import com.fnmusic.tv.core.lyrics.LyricsMatchRequest
import com.fnmusic.tv.core.lyrics.LyricsMatchResult
import com.fnmusic.tv.core.lyrics.LyricsSourceId
import com.fnmusic.tv.core.lyrics.MatchedLyrics
import com.fnmusic.tv.core.lyrics.lyricText
import com.fnmusic.tv.core.lyrics.translationText
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.Track
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class OnlineLyricsResolver(
    private val localStore: LocalStore,
    private val responses: SerializedResponseCache,
    private val namespace: () -> String,
    private val matcher: suspend (LyricsMatchRequest) -> LyricsMatchResult,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun resolve(track: Track): CurrentLyrics? {
        val namespace = namespace()
        val fingerprint = track.lyricsFingerprint()
        val currentTime = now()
        val key = ResponseCacheKey(
            namespace = namespace,
            kind = "matched-lyrics",
            businessKey = "${track.guid.value}:$fingerprint",
        )
        var shouldPersist = false
        val payload = responses.getOrFetch(
            key = key,
            isRetainedValid = { encoded -> encoded.decodeEnvelope()?.accepts(fingerprint, now()) == true },
            persist = { encoded ->
                if (shouldPersist) {
                    try {
                        localStore.saveMatchedLyric(
                            CachedMatchedLyricEntity(namespace, track.guid.value, encoded, now()),
                        )
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (_: Exception) {
                        // A disk-cache failure must not discard valid online lyrics.
                    }
                }
            },
        ) {
            try {
                localStore.matchedLyric(namespace, track.guid.value)
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                null
            }
                ?.payload
                ?.decodeEnvelope()
                ?.takeIf { it.accepts(fingerprint, currentTime) }
                ?.let(ApiDecoder.json::encodeToString)
                ?: when (val result = matcher(track.toLyricsMatchRequest())) {
                    is LyricsMatchResult.Found -> MatchedLyricsEnvelope(
                        schemaVersion = MATCHED_LYRICS_SCHEMA_VERSION,
                        fingerprint = fingerprint,
                        matched = result.lyrics.toCache(),
                        expiresAtMs = Long.MAX_VALUE,
                    )
                    LyricsMatchResult.NotFound -> MatchedLyricsEnvelope(
                        schemaVersion = MATCHED_LYRICS_SCHEMA_VERSION,
                        fingerprint = fingerprint,
                        matched = null,
                        expiresAtMs = currentTime + NEGATIVE_CACHE_TTL_MS,
                    )
                    LyricsMatchResult.NetworkFailure,
                    LyricsMatchResult.InvalidResponse,
                    -> throw OnlineLyricsUnavailableException()
                }.also { shouldPersist = true }.let(ApiDecoder.json::encodeToString)
        }
        return payload.decodeEnvelope()
            ?.takeIf { it.accepts(fingerprint, now()) }
            ?.matched
            ?.toCurrentLyrics(track.guid.value)
    }

    private fun String.decodeEnvelope(): MatchedLyricsEnvelope? = try {
        ApiDecoder.json.decodeFromString(this)
    } catch (_: Exception) {
        null
    }

    private fun MatchedLyricsEnvelope.accepts(expectedFingerprint: String, timeMs: Long): Boolean =
        schemaVersion == MATCHED_LYRICS_SCHEMA_VERSION &&
            fingerprint == expectedFingerprint &&
            expiresAtMs > timeMs

    private companion object {
        const val MATCHED_LYRICS_SCHEMA_VERSION = 4
        const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1_000L
    }

    @Serializable
    private data class MatchedLyricsEnvelope(
        val schemaVersion: Int,
        val fingerprint: String,
        val matched: CachedMatchedLyrics?,
        val expiresAtMs: Long,
    )
}

private class OnlineLyricsUnavailableException : Exception()

private fun Track.toLyricsMatchRequest() = LyricsMatchRequest(
    localId = guid.value,
    title = title,
    artists = listOfNotNull(artistName?.takeIf(String::isNotBlank)),
    album = albumName,
    durationMs = durationMs,
)

private fun Track.lyricsFingerprint(): String {
    val value = listOf(
        guid.value,
        LyricsCandidateScorer.normalize(title),
        LyricsCandidateScorer.normalize(artistName.orEmpty()),
        LyricsCandidateScorer.normalize(albumName.orEmpty()),
        durationMs?.toString().orEmpty(),
        MATCH_PROTOCOL_VERSION,
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Serializable
private data class CachedMatchedLyrics(
    val source: LyricsSourceId,
    val candidate: LyricsCandidate,
    val score: Double,
    val quality: LyricsContentQuality,
    val lines: List<CachedLyricsLine>,
)

@Serializable
private data class CachedLyricsLine(
    val kind: CachedLineKind,
    val start: Int,
    val end: Int,
    val content: String,
    val translation: String? = null,
    val phonetic: String? = null,
    val alignment: String? = null,
    val syllables: List<CachedSyllable> = emptyList(),
    val accompaniment: List<CachedLyricsLine> = emptyList(),
)

@Serializable
private data class CachedSyllable(
    val content: String,
    val start: Int,
    val end: Int,
    val phonetic: String? = null,
)

@Serializable
private enum class CachedLineKind { Synced, KaraokeMain, KaraokeAccompaniment }

private fun MatchedLyrics.toCache() = CachedMatchedLyrics(
    source = source,
    candidate = candidate,
    score = score,
    quality = quality,
    lines = lyrics.lines.mapNotNull(ISyncedLine::toCache),
)

private fun ISyncedLine.toCache(): CachedLyricsLine? = when (this) {
    is SyncedLine -> CachedLyricsLine(
        kind = CachedLineKind.Synced,
        start = start,
        end = end,
        content = content,
        translation = translation,
    )
    is KaraokeLine.MainKaraokeLine -> CachedLyricsLine(
        kind = CachedLineKind.KaraokeMain,
        start = start,
        end = end,
        content = lyricText(),
        translation = translation,
        phonetic = phonetic,
        alignment = alignment.name,
        syllables = syllables.map(KaraokeSyllable::toCache),
        accompaniment = accompanimentLines.orEmpty().mapNotNull(ISyncedLine::toCache),
    )
    is KaraokeLine.AccompanimentKaraokeLine -> CachedLyricsLine(
        kind = CachedLineKind.KaraokeAccompaniment,
        start = start,
        end = end,
        content = lyricText(),
        translation = translation,
        phonetic = phonetic,
        alignment = alignment.name,
        syllables = syllables.map(KaraokeSyllable::toCache),
    )
    else -> null
}

private fun KaraokeSyllable.toCache() = CachedSyllable(content, start, end, phonetic)

private fun CachedMatchedLyrics.toCurrentLyrics(trackGuid: String): CurrentLyrics {
    val synced = SyncedLyrics(lines.mapNotNull(CachedLyricsLine::toSdk))
    val content = synced.lines.joinToString("\n") { line ->
        listOfNotNull(line.lyricText(), line.translationText()).filter(String::isNotBlank).joinToString("\n")
    }
    return CurrentLyrics(
        document = LyricDocument(
            guid = "online:$trackGuid:${source.name}:${candidate.remoteId}",
            content = content,
            isLrc = false,
            offsetMs = 0,
        ),
        syncedLyrics = synced,
    )
}

private fun CachedLyricsLine.toSdk(): ISyncedLine? {
    if (end < start) return null
    if (kind == CachedLineKind.Synced) {
        return content.takeIf(String::isNotBlank)?.let { SyncedLine(it, translation, start, end) }
    }
    val restoredSyllables = syllables.mapNotNull(CachedSyllable::toSdk)
    if (restoredSyllables.isEmpty()) return null
    val restoredAlignment = runCatching { KaraokeAlignment.valueOf(alignment.orEmpty()) }
        .getOrDefault(KaraokeAlignment.Unspecified)
    return when (kind) {
        CachedLineKind.KaraokeMain -> KaraokeLine.MainKaraokeLine(
            syllables = restoredSyllables,
            translation = translation,
            alignment = restoredAlignment,
            start = start,
            end = end,
            phonetic = phonetic,
            accompanimentLines = accompaniment.mapNotNull {
                it.toSdk() as? KaraokeLine.AccompanimentKaraokeLine
            },
        )
        CachedLineKind.KaraokeAccompaniment -> KaraokeLine.AccompanimentKaraokeLine(
            syllables = restoredSyllables,
            translation = translation,
            alignment = restoredAlignment,
            start = start,
            end = end,
            phonetic = phonetic,
        )
        CachedLineKind.Synced -> null
    }
}

private fun CachedSyllable.toSdk(): KaraokeSyllable? =
    takeIf { end >= start }?.let { KaraokeSyllable(content, start, end, phonetic) }

private const val MATCH_PROTOCOL_VERSION = "lyrics-sdk-4"
