// SPDX-License-Identifier: GPL-3.0-only
// Matching behavior is derived from LDDC, commit 1ffa0e25426e654376e5d55d854b135ae601f43b.
package com.fnmusic.tv.core.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class LyricsMatchPolicy(
    val minimumScore: Double = 80.0,
    val minimumTitleScore: Double = 50.0,
    val maximumDurationDeltaMs: Long = 4_000L,
    val qualityTieWindow: Double = 5.0,
    val sourceOrder: List<LyricsSourceId> = listOf(
        LyricsSourceId.QqMusic,
        LyricsSourceId.Kugou,
        LyricsSourceId.Netease,
    ),
)

data class ScoredLyricsCandidate(
    val candidate: LyricsCandidate,
    val score: Double,
    val titleScore: Double,
    val consensusCount: Int,
)

class LyricsCandidateScorer(
    private val policy: LyricsMatchPolicy = LyricsMatchPolicy(),
) {
    fun score(request: LyricsMatchRequest, candidates: List<LyricsCandidate>): List<ScoredLyricsCandidate> {
        val eligible = candidates.mapNotNull { scoreOne(request, it) }
        if (eligible.isEmpty()) return emptyList()
        return eligible.map { scored ->
            val consensus = eligible.count { other ->
                other.candidate.source != scored.candidate.source && sameRecording(scored.candidate, other.candidate)
            } + 1
            scored.copy(
                score = (scored.score + (consensus - 1).coerceAtMost(2) * 1.5).coerceAtMost(100.0),
                consensusCount = consensus,
            )
        }.filter { it.score >= policy.minimumScore && it.titleScore >= policy.minimumTitleScore }
            .sortedWith(
                compareByDescending<ScoredLyricsCandidate> { it.score }
                    .thenBy { policy.sourceOrder.indexOf(it.candidate.source).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
            )
    }

    private fun scoreOne(request: LyricsMatchRequest, candidate: LyricsCandidate): ScoredLyricsCandidate? {
        if (candidate.title.isBlank() || request.title.isBlank()) return null
        val durationDelta = knownDurationDelta(request.durationMs, candidate.durationMs)
        if (durationDelta != null && durationDelta > policy.maximumDurationDeltaMs) return null
        if (hasHardVersionConflict(request.title, candidate.title, candidate.instrumental)) return null

        val titleScore = titleScore(request.title, candidate.title)
        val artistScore = artistScore(request.artists, candidate.artists)
        val albumScore = request.album?.takeIf(String::isNotBlank)?.let { local ->
            candidate.album?.takeIf(String::isNotBlank)?.let { remote -> sequenceRatio(normalize(local), normalize(remote)) * 100.0 }
        }
        val score = when {
            request.artists.isNotEmpty() && candidate.artists.isNotEmpty() && albumScore != null -> max(
                titleScore * 0.5 + artistScore * 0.5,
                titleScore * 0.5 + artistScore * 0.35 + albumScore * 0.15,
            )
            request.artists.isNotEmpty() && candidate.artists.isNotEmpty() -> titleScore * 0.5 + artistScore * 0.5
            albumScore != null -> max(titleScore * 0.7 + albumScore * 0.3, titleScore * 0.8)
            else -> titleScore
        }
        return ScoredLyricsCandidate(candidate, score, titleScore, consensusCount = 1)
    }

    private fun sameRecording(left: LyricsCandidate, right: LyricsCandidate): Boolean {
        if (titleScore(left.title, right.title) < 92.0) return false
        if (artistScore(left.artists, right.artists) < 85.0) return false
        return knownDurationDelta(left.durationMs, right.durationMs)?.let { it <= policy.maximumDurationDeltaMs } ?: true
    }

    private fun titleScore(left: String, right: String): Double {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        if (normalizedLeft == normalizedRight) return 100.0
        val full = sequenceRatio(normalizedLeft, normalizedRight) * 100.0
        val baseLeft = stripVersionTags(normalizedLeft)
        val baseRight = stripVersionTags(normalizedRight)
        val base = sequenceRatio(baseLeft, baseRight) * 95.0
        return max(full, base)
    }

    private fun artistScore(left: List<String>, right: List<String>): Double {
        val local = splitArtists(left)
        val remote = splitArtists(right)
        if (local.isEmpty() || remote.isEmpty()) return 0.0
        val shorter = if (local.size <= remote.size) local else remote
        val longer = if (local.size <= remote.size) remote else local
        val used = mutableSetOf<Int>()
        var total = 0.0
        shorter.forEach { artist ->
            val best = longer.withIndex()
                .filterNot { it.index in used }
                .maxByOrNull { sequenceRatio(artist, it.value) }
            if (best != null) {
                used += best.index
                total += sequenceRatio(artist, best.value)
            }
        }
        return total / max(local.size, remote.size) * 100.0
    }

    private fun splitArtists(values: List<String>): List<String> = values
        .flatMap { it.split(ARTIST_SEPARATOR) }
        .map { normalize(it.replace(FEAT_PREFIX, "")) }
        .filter(String::isNotBlank)
        .distinct()

    private fun hasHardVersionConflict(local: String, remote: String, remoteInstrumental: Boolean): Boolean {
        val localFlags = versionFlags(local)
        val remoteFlags = versionFlags(remote).toMutableSet().apply {
            if (remoteInstrumental) add(VersionFlag.Instrumental)
        }
        if (VersionFlag.Instrumental !in localFlags && VersionFlag.Instrumental in remoteFlags) return true
        return listOf(VersionFlag.Live, VersionFlag.Remix, VersionFlag.Acoustic, VersionFlag.Cover, VersionFlag.Short)
            .any { flag -> (flag in localFlags) != (flag in remoteFlags) }
    }

    private fun versionFlags(value: String): Set<VersionFlag> {
        val normalized = normalize(value)
        return VersionFlag.entries.filterTo(mutableSetOf()) { it.pattern.containsMatchIn(normalized) }
    }

    private fun stripVersionTags(value: String): String = VersionFlag.entries
        .fold(value) { text, flag -> flag.pattern.replace(text, " ") }
        .replace(VERSION_DECORATION, " ")
        .replace(WHITESPACE, " ")
        .trim()

    private enum class VersionFlag(val pattern: Regex) {
        Instrumental(Regex("(?:^|\\W)(?:inst(?:rumental)?|off\\s*vocal|伴奏|纯音乐)(?:$|\\W)")),
        Live(Regex("(?:^|\\W)(?:live|现场|演唱会)(?:$|\\W)")),
        Remix(Regex("(?:^|\\W)(?:remix|mix|混音)(?:$|\\W)")),
        Acoustic(Regex("(?:^|\\W)(?:acoustic|unplugged|不插电)(?:$|\\W)")),
        Cover(Regex("(?:^|\\W)(?:cover|翻唱)(?:$|\\W)")),
        Short(Regex("(?:^|\\W)(?:tv\\s*size|radio\\s*edit|short\\s*ver)(?:$|\\W)")),
    }

    companion object {
        private val ARTIST_SEPARATOR = Regex("\\s*(?:/|、|,|，|&|＆|;|；|·|・|\\bfeat\\.?\\b|\\bft\\.?\\b)\\s*", RegexOption.IGNORE_CASE)
        private val FEAT_PREFIX = Regex("^(?:feat\\.?|ft\\.?)\\s*", RegexOption.IGNORE_CASE)
        private val VERSION_DECORATION = Regex("[()\\[\\]{}<>_-]+")
        private val WHITESPACE = Regex("\\s+")

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('（', '(')
            .replace('）', ')')
            .replace(WHITESPACE, " ")
            .trim()

        private fun knownDurationDelta(left: Long?, right: Long?): Long? =
            if (left != null && left > 0 && right != null && right > 0) abs(left - right) else null
    }
}

internal fun sequenceRatio(left: String, right: String): Double {
    if (left == right) return 1.0
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val leftPoints = left.codePoints().toArray()
    val rightPoints = right.codePoints().toArray()
    val matches = matchingBlocks(leftPoints, 0, leftPoints.size, rightPoints, 0, rightPoints.size)
        .sumOf { it.size }
    return 2.0 * matches / (leftPoints.size + rightPoints.size)
}

private data class MatchBlock(val left: Int, val right: Int, val size: Int)

private fun matchingBlocks(
    left: IntArray,
    leftStart: Int,
    leftEnd: Int,
    right: IntArray,
    rightStart: Int,
    rightEnd: Int,
): List<MatchBlock> {
    val longest = longestMatch(left, leftStart, leftEnd, right, rightStart, rightEnd)
    if (longest.size == 0) return emptyList()
    val before = matchingBlocks(left, leftStart, longest.left, right, rightStart, longest.right)
    val after = matchingBlocks(
        left,
        longest.left + longest.size,
        leftEnd,
        right,
        longest.right + longest.size,
        rightEnd,
    )
    return before + longest + after
}

private fun longestMatch(
    left: IntArray,
    leftStart: Int,
    leftEnd: Int,
    right: IntArray,
    rightStart: Int,
    rightEnd: Int,
): MatchBlock {
    var bestLeft = leftStart
    var bestRight = rightStart
    var bestSize = 0
    var previous = mutableMapOf<Int, Int>()
    for (leftIndex in leftStart until leftEnd) {
        val current = mutableMapOf<Int, Int>()
        for (rightIndex in rightStart until rightEnd) {
            if (left[leftIndex] != right[rightIndex]) continue
            val size = (previous[rightIndex - 1] ?: 0) + 1
            current[rightIndex] = size
            if (size > bestSize) {
                bestLeft = leftIndex - size + 1
                bestRight = rightIndex - size + 1
                bestSize = size
            }
        }
        previous = current
    }
    return MatchBlock(bestLeft, bestRight, bestSize)
}
