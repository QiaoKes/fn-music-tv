// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.data.api.ApiDecoder
import com.fnmusic.tv.core.data.local.CachedMatchedLyricEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.lyrics.LyricsMatchRequest
import com.fnmusic.tv.core.lyrics.LyricsMatchResult
import com.fnmusic.tv.core.lyrics.LyricsCandidateScorer
import com.fnmusic.tv.core.lyrics.LyricsTrackAligner
import com.fnmusic.tv.core.lyrics.MatchedLyrics
import com.fnmusic.tv.core.lyrics.TimedLyricsTrack
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.lyric.LyricLine
import com.fnmusic.tv.core.model.lyric.LyricTimeline
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
            isRetainedValid = { encoded ->
                encoded.decodeEnvelope()?.accepts(fingerprint, now()) == true
            },
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
                        fingerprint = fingerprint,
                        matched = result.lyrics,
                        expiresAtMs = Long.MAX_VALUE,
                    )
                    LyricsMatchResult.NotFound -> MatchedLyricsEnvelope(
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
        const val MATCHED_LYRICS_SCHEMA_VERSION = 1
        const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1_000L
    }

    @Serializable
    private data class MatchedLyricsEnvelope(
        val schemaVersion: Int = MATCHED_LYRICS_SCHEMA_VERSION,
        val fingerprint: String,
        val matched: MatchedLyrics?,
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

private fun MatchedLyrics.toCurrentLyrics(trackGuid: String): CurrentLyrics {
    val timedLines = LyricsTrackAligner.align(original, translation)
        .filter { it.startMs != null && it.text.isNotBlank() }
        .map { line ->
            LyricLine(
                startMs = requireNotNull(line.startMs),
                texts = if (translation?.isNotEmpty == true) {
                    line.words.map { it.text.trim() }.filter(String::isNotBlank).distinct()
                } else {
                    listOf(line.text)
                },
            )
        }
        .filter { it.texts.isNotEmpty() }
    val timeline = timedLines.takeIf(List<LyricLine>::isNotEmpty)?.let(::LyricTimeline)
    val content = if (timeline != null) {
        timeline.lines.joinToString("\n") { line ->
            line.texts.joinToString("\n") { text -> "${line.startMs.toLrcTimestamp()}$text" }
        }
    } else {
        staticText(original, translation)
    }
    return CurrentLyrics(
        document = LyricDocument(
            guid = "online:$trackGuid:${source.name}:${candidate.remoteId}",
            content = content,
            isLrc = timeline != null,
            offsetMs = 0,
        ),
        timeline = timeline,
    )
}

private fun staticText(original: TimedLyricsTrack, translation: TimedLyricsTrack?): String = buildList {
    addAll(original.lines.map { it.text }.filter(String::isNotBlank))
    translation?.lines?.map { it.text }?.filter(String::isNotBlank)?.let(::addAll)
}.distinct().joinToString("\n")

private fun Long.toLrcTimestamp(): String {
    val minutes = this / 60_000L
    val seconds = (this % 60_000L) / 1_000L
    val millis = this % 1_000L
    return "[%02d:%02d.%03d]".format(minutes, seconds, millis)
}

private const val MATCH_PROTOCOL_VERSION = "lddc-1"
