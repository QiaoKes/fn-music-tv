// SPDX-License-Identifier: GPL-3.0-only
// Auto-fetch orchestration is derived from LDDC, commit 1ffa0e25426e654376e5d55d854b135ae601f43b.
package com.fnmusic.tv.core.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

class LyricsMatchCoordinator(
    sources: List<LyricsSource>,
    private val policy: LyricsMatchPolicy = LyricsMatchPolicy(),
    private val sourceTimeoutMs: Long = 1_800L,
    private val totalTimeoutMs: Long = 3_000L,
    private val aggregationWindowMs: Long = 350L,
    private val scorer: LyricsCandidateScorer = LyricsCandidateScorer(policy),
) {
    private val sources = sources.distinctBy(LyricsSource::id)

    suspend fun match(request: LyricsMatchRequest): LyricsMatchResult {
        if (request.title.isBlank() || sources.isEmpty()) return LyricsMatchResult.NotFound
        return withTimeoutOrNull(totalTimeoutMs) { matchWithinBudget(request) } ?: LyricsMatchResult.NetworkFailure
    }

    private suspend fun matchWithinBudget(request: LyricsMatchRequest): LyricsMatchResult = supervisorScope {
        val primaryKeyword = buildList {
            request.artists.filter(String::isNotBlank).joinToString(" / ").takeIf(String::isNotBlank)?.let(::add)
            add(request.title)
        }.joinToString(" - ")
        val outcomeChannel = Channel<SearchOutcome>(sources.size)
        val searchJobs = sources.map { source ->
            launch {
                try {
                    val primaryCandidates = search(source, primaryKeyword, request)
                    val candidates = if (
                        primaryKeyword != request.title &&
                        scorer.score(request, primaryCandidates).isEmpty()
                    ) {
                        (primaryCandidates + search(source, request.title, request))
                            .distinctBy { it.source to it.remoteId }
                    } else {
                        primaryCandidates
                    }
                    outcomeChannel.send(SearchOutcome(source, candidates))
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    outcomeChannel.send(SearchOutcome(source, emptyList(), cause))
                }
            }
        }
        val outcomes = mutableListOf<SearchOutcome>()
        var aggregateUntilNanos: Long? = null
        while (outcomes.size < sources.size) {
            val deadline = aggregateUntilNanos
            val outcome = if (deadline == null) {
                outcomeChannel.receive()
            } else {
                val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                withTimeoutOrNull(remainingMs) { outcomeChannel.receive() } ?: break
            }
            outcomes += outcome
            if (
                aggregateUntilNanos == null &&
                scorer.score(request, outcomes.flatMap(SearchOutcome::candidates)).isNotEmpty()
            ) {
                aggregateUntilNanos = System.nanoTime() + aggregationWindowMs * 1_000_000L
            }
        }
        searchJobs.filter { it.isActive }.forEach { it.cancel() }

        val ranked = scorer.score(request, outcomes.flatMap(SearchOutcome::candidates))
        if (ranked.isEmpty()) {
            return@supervisorScope when {
                outcomes.all { it.failure == null } -> LyricsMatchResult.NotFound
                outcomes.any { it.failure != null && it.failure !is LyricsTransportException } ->
                    LyricsMatchResult.InvalidResponse
                else -> LyricsMatchResult.NetworkFailure
            }
        }

        var hadNetworkFailure = false
        var hadInvalidPayload = false
        ranked.inTieBreakOrder().take(MAX_FETCH_ATTEMPTS).forEach { scored ->
            val source = outcomes.first { it.source.id == scored.candidate.source }.source
            try {
                val lyrics = withTimeoutOrNull(sourceTimeoutMs) { source.fetch(scored.candidate) }
                    ?: throw LyricsTransportException("${source.id} lyrics timed out")
                if (!lyrics.original.isNotEmpty) {
                    hadInvalidPayload = true
                    return@forEach
                }
                return@supervisorScope LyricsMatchResult.Found(
                    MatchedLyrics(
                        source = scored.candidate.source,
                        candidate = scored.candidate,
                        score = scored.score,
                        original = lyrics.original,
                        translation = lyrics.translation,
                        romanization = lyrics.romanization,
                    ),
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: LyricsTransportException) {
                hadNetworkFailure = true
            } catch (_: Throwable) {
                hadInvalidPayload = true
            }
        }
        when {
            hadNetworkFailure -> LyricsMatchResult.NetworkFailure
            hadInvalidPayload -> LyricsMatchResult.InvalidResponse
            else -> LyricsMatchResult.NotFound
        }
    }

    private fun List<ScoredLyricsCandidate>.inTieBreakOrder(): List<ScoredLyricsCandidate> = buildList {
        var start = 0
        while (start < this@inTieBreakOrder.size) {
            val windowTopScore = this@inTieBreakOrder[start].score
            val end = (start + 1 until this@inTieBreakOrder.size)
                .firstOrNull { index -> windowTopScore - this@inTieBreakOrder[index].score > policy.qualityTieWindow }
                ?: this@inTieBreakOrder.size
            addAll(
                this@inTieBreakOrder.subList(start, end).sortedWith(
                    compareBy<ScoredLyricsCandidate> {
                        policy.sourceOrder.indexOf(it.candidate.source).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
                    }.thenByDescending(ScoredLyricsCandidate::score),
                ),
            )
            start = end
        }
    }

    private suspend fun search(
        source: LyricsSource,
        keyword: String,
        request: LyricsMatchRequest,
    ): List<LyricsCandidate> = withTimeoutOrNull(sourceTimeoutMs) {
        source.search(LyricsSearchQuery(keyword, request))
    } ?: throw LyricsTransportException("${source.id} search timed out")

    private data class SearchOutcome(
        val source: LyricsSource,
        val candidates: List<LyricsCandidate>,
        val failure: Throwable? = null,
    )

    private companion object {
        const val MAX_FETCH_ATTEMPTS = 3
    }
}
