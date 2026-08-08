package com.fnmusic.tv.core.lyrics

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchCoordinatorTest {
    private val request = LyricsMatchRequest("id", "Song", listOf("Artist"), "Album", 180_000)

    @Test fun `sources search concurrently and a failed source is isolated`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, delayMs = 150, failure = LyricsTransportException("down")),
            FakeSource(LyricsSourceId.Kugou, delayMs = 150),
            FakeSource(LyricsSourceId.Netease, delayMs = 150),
        )
        lateinit var result: LyricsMatchResult

        val elapsed = measureTimeMillis {
            result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 500, totalTimeoutMs = 1_000).match(request)
        }

        assertTrue(result is LyricsMatchResult.Found)
        assertTrue("searches should overlap, elapsed=$elapsed", elapsed < 450)
        assertTrue((sources[1].fetchCalls + sources[2].fetchCalls) in 1..3)
    }

    @Test fun `all transport failures return network failure within deadline`() = runBlocking {
        val sources = LyricsSourceId.entries.map { FakeSource(it, failure = LyricsTransportException("down")) }

        assertEquals(
            LyricsMatchResult.NetworkFailure,
            LyricsMatchCoordinator(sources, sourceTimeoutMs = 100, totalTimeoutMs = 300).match(request),
        )
    }

    @Test fun `highest ranked successful candidate avoids fallback fetches`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, fetchDelayMs = 180),
            FakeSource(LyricsSourceId.Kugou, fetchDelayMs = 180),
            FakeSource(LyricsSourceId.Netease, fetchDelayMs = 180),
        )
        val result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 400, totalTimeoutMs = 1_000).match(request)

        assertTrue(result is LyricsMatchResult.Found)
        assertEquals(1, sources.sumOf(FakeSource::fetchCalls))
        assertEquals(1, sources[0].fetchCalls)
    }

    @Test fun `title-only search runs when primary results are unusable`() = runBlocking {
        val source = FakeSource(
            id = LyricsSourceId.QqMusic,
            candidates = { query ->
                if (query.keyword == request.title) {
                    listOf(candidate(LyricsSourceId.QqMusic, "exact"))
                } else {
                    listOf(candidate(LyricsSourceId.QqMusic, "irrelevant", title = "Different Song"))
                }
            },
        )

        val result = LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 200, totalTimeoutMs = 800).match(request)

        assertTrue(result is LyricsMatchResult.Found)
        assertEquals(listOf("Artist - Song", "Song"), source.searchKeywords)
    }

    @Test fun `failed leader falls back beyond the quality tie window`() = runBlocking {
        val source = FakeSource(
            id = LyricsSourceId.QqMusic,
            candidates = {
                listOf(
                    candidate(LyricsSourceId.QqMusic, "leader"),
                    candidate(
                        LyricsSourceId.QqMusic,
                        remoteId = "fallback",
                        artists = listOf("Artist X"),
                        album = null,
                    ),
                )
            },
            fetchResult = { candidate ->
                if (candidate.remoteId == "leader") throw LyricsPayloadException("empty")
                SourceLyrics(LyricsTextParser.parseLrc("[00:01.00]fallback"))
            },
        )

        val result = LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 200, totalTimeoutMs = 800).match(request)

        assertEquals("fallback", (result as LyricsMatchResult.Found).lyrics.candidate.remoteId)
        assertEquals(2, source.fetchCalls)
    }

    @Test fun `source priority breaks near ties but cannot override a score gap over the window`() = runBlocking {
        val nearTieSources = listOf(
            FakeSource(
                id = LyricsSourceId.QqMusic,
                candidates = { listOf(candidate(LyricsSourceId.QqMusic, "qq", artists = listOf("Artis"))) },
            ),
            FakeSource(id = LyricsSourceId.Netease),
        )
        val nearTie = LyricsMatchCoordinator(
            nearTieSources,
            sourceTimeoutMs = 200,
            totalTimeoutMs = 800,
            aggregationWindowMs = 50,
        ).match(request) as LyricsMatchResult.Found
        assertEquals(LyricsSourceId.QqMusic, nearTie.lyrics.source)

        val wideGapSources = listOf(
            FakeSource(
                id = LyricsSourceId.QqMusic,
                candidates = { listOf(candidate(LyricsSourceId.QqMusic, "qq", artists = listOf("Art X"))) },
            ),
            FakeSource(id = LyricsSourceId.Netease),
        )
        val wideGap = LyricsMatchCoordinator(
            wideGapSources,
            sourceTimeoutMs = 200,
            totalTimeoutMs = 800,
            aggregationWindowMs = 50,
        ).match(request) as LyricsMatchResult.Found
        assertEquals(LyricsSourceId.Netease, wideGap.lyrics.source)
    }

    @Test fun `invalid search payload is not reduced to not found`() = runBlocking {
        val sources = LyricsSourceId.entries.map {
            FakeSource(it, failure = LyricsPayloadException("invalid payload"))
        }

        assertEquals(
            LyricsMatchResult.InvalidResponse,
            LyricsMatchCoordinator(sources, sourceTimeoutMs = 100, totalTimeoutMs = 300).match(request),
        )
    }

    @Test fun `a slow fallback source does not delay an early high confidence match`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, delayMs = 40),
            FakeSource(LyricsSourceId.Netease, delayMs = 60),
            FakeSource(LyricsSourceId.Kugou, delayMs = 900),
        )
        lateinit var result: LyricsMatchResult

        val elapsed = measureTimeMillis {
            result = LyricsMatchCoordinator(
                sources = sources,
                sourceTimeoutMs = 1_000,
                totalTimeoutMs = 1_200,
                aggregationWindowMs = 100,
            ).match(request)
        }

        assertTrue(result is LyricsMatchResult.Found)
        assertTrue("slow fallback should be canceled, elapsed=$elapsed", elapsed < 500)
        assertEquals(0, sources[2].fetchCalls)
    }

    private class FakeSource(
        override val id: LyricsSourceId,
        private val delayMs: Long = 0,
        private val fetchDelayMs: Long = 0,
        private val failure: Throwable? = null,
        private val candidates: (LyricsSearchQuery) -> List<LyricsCandidate> = { query ->
            listOf(candidate(query.request, id))
        },
        private val fetchResult: (LyricsCandidate) -> SourceLyrics = {
            SourceLyrics(LyricsTextParser.parseLrc("[00:01.00]line"))
        },
    ) : LyricsSource {
        var fetchCalls = 0
        val searchKeywords = mutableListOf<String>()

        override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
            delay(delayMs)
            failure?.let { throw it }
            searchKeywords += query.keyword
            return candidates(query)
        }

        override suspend fun fetch(candidate: LyricsCandidate): SourceLyrics {
            fetchCalls += 1
            delay(fetchDelayMs)
            return fetchResult(candidate)
        }
    }

    private companion object {
        fun candidate(
            source: LyricsSourceId,
            remoteId: String,
            title: String = "Song",
            artists: List<String> = listOf("Artist"),
            album: String? = "Album",
        ) = LyricsCandidate(source, remoteId, title, artists, album, 180_000)

        fun candidate(request: LyricsMatchRequest, source: LyricsSourceId) = LyricsCandidate(
            source,
            source.name,
            request.title,
            request.artists,
            request.album,
            request.durationMs,
        )
    }
}
