// SPDX-License-Identifier: GPL-3.0-only
package com.fnmusic.tv.core.lyrics

import kotlinx.serialization.Serializable

@Serializable
data class LyricsMatchRequest(
    val localId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
)

@Serializable
enum class LyricsSourceId { QqMusic, Kugou, Netease }

@Serializable
enum class LyricsTrackKind { Original, Translation, Romanization }

@Serializable
enum class LyricsContentQuality(internal val rank: Int) {
    Basic(0),
    Translated(1),
    WordTimed(2),
}

@Serializable
data class LyricsCandidate(
    val source: LyricsSourceId,
    val remoteId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val mediaId: String? = null,
    val fileHash: String? = null,
    val accessKey: String? = null,
    val instrumental: Boolean = false,
)

@Serializable
data class TimedLyricsWord(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String,
)

@Serializable
data class TimedLyricsLine(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val words: List<TimedLyricsWord>,
) {
    val text: String get() = words.joinToString(separator = "", transform = TimedLyricsWord::text).trim()
}

@Serializable
data class TimedLyricsTrack(
    val kind: LyricsTrackKind,
    val lines: List<TimedLyricsLine>,
) {
    val isTimed: Boolean get() = lines.any { it.startMs != null }
    val isNotEmpty: Boolean get() = lines.any { it.text.isNotBlank() }
}

@Serializable
data class SourceLyrics(
    val original: TimedLyricsTrack,
    val translation: TimedLyricsTrack? = null,
    val romanization: TimedLyricsTrack? = null,
)

@Serializable
data class MatchedLyrics(
    val source: LyricsSourceId,
    val candidate: LyricsCandidate,
    val score: Double,
    val original: TimedLyricsTrack,
    val translation: TimedLyricsTrack? = null,
    val romanization: TimedLyricsTrack? = null,
    val quality: LyricsContentQuality = LyricsContentQuality.Basic,
)

sealed interface LyricsMatchResult {
    data class Found(val lyrics: MatchedLyrics) : LyricsMatchResult
    data object NotFound : LyricsMatchResult
    data object NetworkFailure : LyricsMatchResult
    data object InvalidResponse : LyricsMatchResult
}

data class LyricsSearchQuery(
    val keyword: String,
    val request: LyricsMatchRequest,
)

interface LyricsSource {
    val id: LyricsSourceId
    suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate>
    suspend fun fetch(candidate: LyricsCandidate): SourceLyrics
}

class LyricsTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class LyricsPayloadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
