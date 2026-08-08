// SPDX-License-Identifier: GPL-3.0-only
// Auto-fetch orchestration is derived from LDDC, commit 1ffa0e25426e654376e5d55d854b135ae601f43b.
package com.fnmusic.tv.core.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                    val candidates = withTimeoutOrNull(sourceTimeoutMs) {
                        source.search(LyricsSearchQuery(primaryKeyword, request)).ifEmpty {
                            if (primaryKeyword == request.title) emptyList()
                            else source.search(LyricsSearchQuery(request.title, request))
                        }
                    } ?: throw LyricsTransportException("${source.id} search timed out")
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
            return@supervisorScope if (outcomes.all { it.failure is LyricsTransportException }) {
                LyricsMatchResult.NetworkFailure
            } else {
                LyricsMatchResult.NotFound
            }
        }

        val highest = ranked.first().score
        val candidatesToFetch = ranked
            .filter { highest - it.score <= policy.qualityTieWindow }
            .take(MAX_FETCH_ATTEMPTS)
        val fetchOutcomes = candidatesToFetch.map { scored ->
            async {
                val source = outcomes.first { it.source.id == scored.candidate.source }.source
                try {
                    val lyrics = withTimeoutOrNull(sourceTimeoutMs) { source.fetch(scored.candidate) }
                        ?: throw LyricsTransportException("${source.id} lyrics timed out")
                    FetchOutcome(
                        fetched = lyrics.takeIf { it.original.isNotEmpty }?.let { FetchedCandidate(scored, it) },
                    )
                } catch (cause: CancellationException) {
                    throw cause
                } catch (_: LyricsTransportException) {
                    FetchOutcome(networkFailure = true)
                } catch (_: Throwable) {
                    FetchOutcome(invalidPayload = true)
                }
            }
        }.awaitAll()
        val fetched = fetchOutcomes.mapNotNull(FetchOutcome::fetched)
        val hadNetworkFailure = fetchOutcomes.any(FetchOutcome::networkFailure)
        val hadInvalidPayload = fetchOutcomes.any(FetchOutcome::invalidPayload)
        val selected = fetched.maxWithOrNull(
            compareBy<FetchedCandidate> { qualityRank(it.lyrics) }
                .thenBy { it.scored.score }
                .thenByDescending { policy.sourceOrder.indexOf(it.scored.candidate.source).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
        )
        if (selected == null) {
            return@supervisorScope when {
                hadNetworkFailure -> LyricsMatchResult.NetworkFailure
                hadInvalidPayload -> LyricsMatchResult.InvalidResponse
                else -> LyricsMatchResult.NotFound
            }
        }
        LyricsMatchResult.Found(
            MatchedLyrics(
                source = selected.scored.candidate.source,
                candidate = selected.scored.candidate,
                score = selected.scored.score,
                original = selected.lyrics.original,
                translation = selected.lyrics.translation,
                romanization = selected.lyrics.romanization,
            ),
        )
    }

    private fun qualityRank(lyrics: SourceLyrics): Int =
        (if (lyrics.original.isTimed) 4 else 0) +
            (if (lyrics.translation?.isNotEmpty == true) 2 else 0) +
            (if (lyrics.romanization?.isNotEmpty == true) 1 else 0)

    private data class SearchOutcome(
        val source: LyricsSource,
        val candidates: List<LyricsCandidate>,
        val failure: Throwable? = null,
    )

    private data class FetchedCandidate(
        val scored: ScoredLyricsCandidate,
        val lyrics: SourceLyrics,
    )

    private data class FetchOutcome(
        val fetched: FetchedCandidate? = null,
        val networkFailure: Boolean = false,
        val invalidPayload: Boolean = false,
    )

    private companion object {
        const val MAX_FETCH_ATTEMPTS = 3
    }
}
