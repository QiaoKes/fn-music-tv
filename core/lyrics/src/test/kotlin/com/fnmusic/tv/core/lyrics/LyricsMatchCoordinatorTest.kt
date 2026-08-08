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

    @Test fun `tied candidate fetches overlap inside the total deadline`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, fetchDelayMs = 180),
            FakeSource(LyricsSourceId.Kugou, fetchDelayMs = 180),
            FakeSource(LyricsSourceId.Netease, fetchDelayMs = 180),
        )
        lateinit var result: LyricsMatchResult

        val elapsed = measureTimeMillis {
            result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 400, totalTimeoutMs = 500).match(request)
        }

        assertTrue(result is LyricsMatchResult.Found)
        assertEquals(3, sources.sumOf(FakeSource::fetchCalls))
        assertTrue("fetches should overlap, elapsed=$elapsed", elapsed < 450)
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
    ) : LyricsSource {
        var fetchCalls = 0

        override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
            delay(delayMs)
            failure?.let { throw it }
            return listOf(LyricsCandidate(id, id.name, "Song", listOf("Artist"), "Album", 180_000))
        }

        override suspend fun fetch(candidate: LyricsCandidate): SourceLyrics {
            fetchCalls += 1
            delay(fetchDelayMs)
            return SourceLyrics(LyricsTextParser.parseLrc("[00:01.00]line"))
        }
    }
}
