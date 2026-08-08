// SPDX-License-Identifier: GPL-3.0-only
// Auto-fetch orchestration is derived from LDDC, commit 1ffa0e25426e654376e5d55d854b135ae601f43b.
package com.fnmusic.tv.core.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

class LyricsMatchCoordinator(
    sources: List<LyricsSource>,
    private val policy: LyricsMatchPolicy = LyricsMatchPolicy(),
    private val sourceTimeoutMs: Long = 1_800L,
    private val scorer: LyricsCandidateScorer = LyricsCandidateScorer(policy),
) {
    private val sources = sources.distinctBy(LyricsSource::id)

    suspend fun match(request: LyricsMatchRequest): LyricsMatchResult {
        if (request.title.isBlank() || sources.isEmpty()) return LyricsMatchResult.NotFound
        return matchAllSources(request)
    }

    private suspend fun matchAllSources(request: LyricsMatchRequest): LyricsMatchResult = supervisorScope {
        val primaryKeyword = buildList {
            request.artists.filter(String::isNotBlank).joinToString(" / ").takeIf(String::isNotBlank)?.let(::add)
            add(request.title)
        }.joinToString(" - ")
        val searchOutcomes = sources.map { source ->
            async { searchSource(source, primaryKeyword, request) }
        }.awaitAll()
        val ranked = scorer.score(request, searchOutcomes.flatMap(SearchOutcome::candidates))
        val fetchOutcomes = sources.map { source ->
            val sourceCandidates = ranked.asSequence()
                .filter { it.candidate.source == source.id }
                .sortedWith(
                    compareByDescending<ScoredLyricsCandidate>(ScoredLyricsCandidate::metadataScore)
                        .thenByDescending(ScoredLyricsCandidate::consensusCount)
                        .thenBy { it.candidate.remoteId },
                )
                .take(MAX_FETCH_ATTEMPTS_PER_SOURCE)
                .toList()
            async { fetchSource(source, sourceCandidates) }
        }.awaitAll()

        val matches = fetchOutcomes.mapNotNull(FetchOutcome::match)
        if (matches.isNotEmpty()) {
            val selected = matches.sortedWith(contentComparator()).first()
            return@supervisorScope LyricsMatchResult.Found(
                MatchedLyrics(
                    source = selected.scored.candidate.source,
                    candidate = selected.scored.candidate,
                    score = selected.scored.score,
                    original = selected.lyrics.original,
                    translation = selected.lyrics.translation,
                    romanization = selected.lyrics.romanization,
                    quality = selected.content.quality,
                ),
            )
        }

        classifyFailure(searchOutcomes, fetchOutcomes)
    }

    private suspend fun searchSource(
        source: LyricsSource,
        primaryKeyword: String,
        request: LyricsMatchRequest,
    ): SearchOutcome = try {
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
        SearchOutcome(source, candidates)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        SearchOutcome(source, emptyList(), cause.toFailureKind())
    }

    private suspend fun fetchSource(
        source: LyricsSource,
        candidates: List<ScoredLyricsCandidate>,
    ): FetchOutcome {
        if (candidates.isEmpty()) return FetchOutcome()

        var failure: FailureKind? = null
        candidates.forEach { scored ->
            try {
                val lyrics = withTimeoutOrNull(sourceTimeoutMs) { source.fetch(scored.candidate) }
                    ?: throw LyricsTransportException("${source.id} lyrics timed out")
                if (!lyrics.original.isNotEmpty) {
                    failure = FailureKind.InvalidResponse
                    return@forEach
                }
                return FetchOutcome(
                    match = ProviderMatch(
                        scored = scored,
                        lyrics = lyrics,
                        content = LyricsContentQualityEvaluator.evaluate(lyrics),
                    ),
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                failure = failure.combine(cause.toFailureKind())
            }
        }
        return FetchOutcome(failure = failure ?: FailureKind.InvalidResponse)
    }

    private fun contentComparator(): Comparator<ProviderMatch> =
        compareByDescending<ProviderMatch> { it.content.quality.rank }
            .thenByDescending { it.content.wordTimedCoverage }
            .thenByDescending { it.content.translationCoverage }
            .thenByDescending { it.content.timedLineCoverage }
            .thenByDescending { it.content.usableLineCount }
            .thenByDescending { it.scored.metadataScore }
            .thenByDescending { it.scored.consensusCount }
            .thenBy { sourceOrderIndex(it.scored.candidate.source) }
            .thenBy { it.scored.candidate.remoteId }

    private fun classifyFailure(
        searchOutcomes: List<SearchOutcome>,
        fetchOutcomes: List<FetchOutcome>,
    ): LyricsMatchResult {
        val failures = searchOutcomes.mapNotNull(SearchOutcome::failure) +
            fetchOutcomes.mapNotNull(FetchOutcome::failure)
        return when {
            FailureKind.InvalidResponse in failures -> LyricsMatchResult.InvalidResponse
            FailureKind.NetworkFailure in failures -> LyricsMatchResult.NetworkFailure
            else -> LyricsMatchResult.NotFound
        }
    }

    private fun sourceOrderIndex(source: LyricsSourceId): Int =
        policy.sourceOrder.indexOf(source).takeIf { it >= 0 } ?: Int.MAX_VALUE

    private suspend fun search(
        source: LyricsSource,
        keyword: String,
        request: LyricsMatchRequest,
    ): List<LyricsCandidate> = withTimeoutOrNull(sourceTimeoutMs) {
        source.search(LyricsSearchQuery(keyword, request))
    } ?: throw LyricsTransportException("${source.id} search timed out")

    private fun Throwable.toFailureKind(): FailureKind =
        if (this is LyricsTransportException) FailureKind.NetworkFailure else FailureKind.InvalidResponse

    private fun FailureKind?.combine(other: FailureKind): FailureKind = when {
        this == FailureKind.InvalidResponse || other == FailureKind.InvalidResponse -> FailureKind.InvalidResponse
        else -> other
    }

    private data class SearchOutcome(
        val source: LyricsSource,
        val candidates: List<LyricsCandidate>,
        val failure: FailureKind? = null,
    )

    private data class FetchOutcome(
        val match: ProviderMatch? = null,
        val failure: FailureKind? = null,
    )

    private data class ProviderMatch(
        val scored: ScoredLyricsCandidate,
        val lyrics: SourceLyrics,
        val content: LyricsContentQualityProjection,
    )

    private enum class FailureKind { NetworkFailure, InvalidResponse }

    private companion object {
        const val MAX_FETCH_ATTEMPTS_PER_SOURCE = 3
    }
}
