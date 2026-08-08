package com.fnmusic.tv.core.lyrics

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchCoordinatorTest {
    private val request = LyricsMatchRequest("id", "Song", listOf("Artist"), "Album", 180_000)

    @Test fun `all sources complete search and fetch concurrently`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, searchDelayMs = 120, fetchDelayMs = 120),
            FakeSource(LyricsSourceId.Kugou, searchDelayMs = 120, fetchDelayMs = 120),
            FakeSource(LyricsSourceId.Netease, searchDelayMs = 120, fetchDelayMs = 120),
        )
        lateinit var result: LyricsMatchResult

        val elapsed = measureTimeMillis {
            result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 500).match(request)
        }

        assertTrue(result is LyricsMatchResult.Found)
        assertEquals(listOf(1, 1, 1), sources.map(FakeSource::fetchCalls))
        assertTrue("provider work should overlap, elapsed=$elapsed", elapsed < 550)
    }

    @Test fun `later rich provider is awaited instead of cut off early`() = runBlocking {
        val sources = listOf(
            FakeSource(
                LyricsSourceId.QqMusic,
                searchDelayMs = 20,
                fetchResult = { basicLyrics("plain") },
            ),
            FakeSource(
                LyricsSourceId.Netease,
                searchDelayMs = 220,
                fetchResult = { wordTimedLyrics(withTranslation = true) },
            ),
        )
        lateinit var result: LyricsMatchResult

        val elapsed = measureTimeMillis {
            result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 500).match(request)
        }

        val found = result as LyricsMatchResult.Found
        assertEquals(LyricsSourceId.Netease, found.lyrics.source)
        assertEquals(LyricsContentQuality.WordTimed, found.lyrics.quality)
        assertTrue("later provider should reach a terminal outcome, elapsed=$elapsed", elapsed >= 180)
        assertEquals(1, sources[0].fetchCalls)
        assertEquals(1, sources[1].fetchCalls)
    }

    @Test fun `RTRT selects later Netease word timed bilingual lyrics over QQ plain lyrics`() = runBlocking {
        val rtrt = LyricsMatchRequest(
            localId = "rtrt",
            title = "RTRT",
            artists = listOf("Mili"),
            album = "Miracle Milk",
            durationMs = 215_000,
        )
        val qq = FakeSource(
            id = LyricsSourceId.QqMusic,
            searchDelayMs = 10,
            candidates = { listOf(candidate(it.request, LyricsSourceId.QqMusic, "qq-rtrt")) },
            fetchResult = { basicLyrics("Hop, step, jump") },
        )
        val netease = FakeSource(
            id = LyricsSourceId.Netease,
            searchDelayMs = 180,
            candidates = { listOf(candidate(it.request, LyricsSourceId.Netease, "netease-rtrt")) },
            fetchResult = {
                SourceLyrics(
                    original = LyricsTextParser.parseYrc(
                        "[1200,1800](1200,450,0)Hop(1650,350,0), (2000,400,0)step(2400,600,0) jump",
                    ),
                    translation = LyricsTextParser.parseLrc(
                        "[00:01.20]跳起来",
                        LyricsTrackKind.Translation,
                    ),
                )
            },
        )

        val result = LyricsMatchCoordinator(listOf(qq, netease), sourceTimeoutMs = 500).match(rtrt)

        val lyrics = (result as LyricsMatchResult.Found).lyrics
        assertEquals(LyricsSourceId.Netease, lyrics.source)
        assertEquals("netease-rtrt", lyrics.candidate.remoteId)
        assertEquals(LyricsContentQuality.WordTimed, lyrics.quality)
        assertTrue(lyrics.translation?.isNotEmpty == true)
    }

    @Test fun `word timed original beats line timed bilingual lyrics`() = runBlocking {
        val translated = FakeSource(
            LyricsSourceId.QqMusic,
            fetchResult = { translatedLyrics() },
        )
        val wordTimed = FakeSource(
            LyricsSourceId.Netease,
            candidates = {
                listOf(candidate(it.request, LyricsSourceId.Netease, "word").copy(artists = listOf("Artis")))
            },
            fetchResult = { wordTimedLyrics(withTranslation = false) },
        )

        val result = LyricsMatchCoordinator(listOf(translated, wordTimed), sourceTimeoutMs = 200).match(request)

        val lyrics = (result as LyricsMatchResult.Found).lyrics
        assertEquals(LyricsSourceId.Netease, lyrics.source)
        assertEquals(LyricsContentQuality.WordTimed, lyrics.quality)
    }

    @Test fun `aligned translation beats higher confidence basic lyrics`() = runBlocking {
        val basic = FakeSource(LyricsSourceId.QqMusic, fetchResult = { basicLyrics("plain") })
        val translated = FakeSource(
            LyricsSourceId.Netease,
            candidates = {
                listOf(candidate(it.request, LyricsSourceId.Netease, "translated").copy(artists = listOf("Artis")))
            },
            fetchResult = { translatedLyrics() },
        )

        val result = LyricsMatchCoordinator(listOf(basic, translated), sourceTimeoutMs = 200).match(request)

        val lyrics = (result as LyricsMatchResult.Found).lyrics
        assertEquals(LyricsSourceId.Netease, lyrics.source)
        assertEquals(LyricsContentQuality.Translated, lyrics.quality)
    }

    @Test fun `romanization alone does not elevate basic lyrics`() = runBlocking {
        val romanized = FakeSource(
            LyricsSourceId.QqMusic,
            fetchResult = {
                basicLyrics("original").copy(
                    romanization = LyricsTextParser.parseLrc(
                        "[00:01.00]romanized",
                        LyricsTrackKind.Romanization,
                    ),
                )
            },
        )
        val basic = FakeSource(LyricsSourceId.Netease, fetchResult = { basicLyrics("plain") })

        val result = LyricsMatchCoordinator(listOf(romanized, basic), sourceTimeoutMs = 200).match(request)

        val lyrics = (result as LyricsMatchResult.Found).lyrics
        assertEquals(LyricsSourceId.QqMusic, lyrics.source)
        assertEquals(LyricsContentQuality.Basic, lyrics.quality)
    }

    @Test fun `metadata score and provider order deterministically break equal quality`() = runBlocking {
        val lowerScore = FakeSource(
            LyricsSourceId.QqMusic,
            candidates = {
                listOf(candidate(it.request, LyricsSourceId.QqMusic, "lower").copy(artists = listOf("Artis")))
            },
        )
        val exact = FakeSource(LyricsSourceId.Netease)

        val scoreWinner = LyricsMatchCoordinator(listOf(lowerScore, exact), sourceTimeoutMs = 200)
            .match(request) as LyricsMatchResult.Found
        assertEquals(LyricsSourceId.Netease, scoreWinner.lyrics.source)

        val exactQq = FakeSource(LyricsSourceId.QqMusic)
        val exactNetease = FakeSource(LyricsSourceId.Netease)
        val providerWinner = LyricsMatchCoordinator(listOf(exactNetease, exactQq), sourceTimeoutMs = 200)
            .match(request) as LyricsMatchResult.Found
        assertEquals(LyricsSourceId.QqMusic, providerWinner.lyrics.source)
    }

    @Test fun `provider retries metadata ranked candidates until one is usable`() = runBlocking {
        val source = FakeSource(
            id = LyricsSourceId.QqMusic,
            candidates = {
                listOf(
                    candidate(LyricsSourceId.QqMusic, "leader"),
                    candidate(LyricsSourceId.QqMusic, "fallback", artists = listOf("Artist X"), album = null),
                )
            },
            fetchResult = { candidate ->
                if (candidate.remoteId == "leader") throw LyricsPayloadException("empty")
                basicLyrics("fallback")
            },
        )

        val result = LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 200).match(request)

        assertEquals("fallback", (result as LyricsMatchResult.Found).lyrics.candidate.remoteId)
        assertEquals(2, source.fetchCalls)
    }

    @Test fun `provider stops after its first usable metadata ranked candidate`() = runBlocking {
        val source = FakeSource(
            id = LyricsSourceId.QqMusic,
            candidates = {
                listOf(
                    candidate(LyricsSourceId.QqMusic, "leader"),
                    candidate(LyricsSourceId.QqMusic, "lower", artists = listOf("Artist X"), album = null),
                )
            },
            fetchResult = { candidate ->
                if (candidate.remoteId == "leader") basicLyrics("leader") else wordTimedLyrics(true)
            },
        )

        val result = LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 200).match(request)

        assertEquals("leader", (result as LyricsMatchResult.Found).lyrics.candidate.remoteId)
        assertEquals(1, source.fetchCalls)
    }

    @Test fun `provider attempts at most three candidates`() = runBlocking {
        val source = FakeSource(
            id = LyricsSourceId.QqMusic,
            candidates = {
                (1..4).map { index -> candidate(LyricsSourceId.QqMusic, index.toString()) }
            },
            fetchResult = { throw LyricsPayloadException("empty") },
        )

        val result = LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 200).match(request)

        assertEquals(LyricsMatchResult.InvalidResponse, result)
        assertEquals(3, source.fetchCalls)
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

        val result = LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 200).match(request)

        assertTrue(result is LyricsMatchResult.Found)
        assertEquals(listOf("Artist - Song", "Song"), source.searchKeywords)
    }

    @Test fun `transport and invalid payload failures do not discard another provider result`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, searchFailure = LyricsTransportException("down")),
            FakeSource(
                LyricsSourceId.Kugou,
                fetchResult = { throw LyricsPayloadException("malformed") },
            ),
            FakeSource(LyricsSourceId.Netease, fetchResult = { translatedLyrics() }),
        )

        val result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 200).match(request)

        assertEquals(LyricsSourceId.Netease, (result as LyricsMatchResult.Found).lyrics.source)
    }

    @Test fun `all transport failures return network failure after terminal outcomes`() = runBlocking {
        val sources = LyricsSourceId.entries.map {
            FakeSource(it, searchFailure = LyricsTransportException("down"))
        }

        assertEquals(
            LyricsMatchResult.NetworkFailure,
            LyricsMatchCoordinator(sources, sourceTimeoutMs = 100).match(request),
        )
    }

    @Test fun `invalid payload is not reduced to not found`() = runBlocking {
        val sources = LyricsSourceId.entries.map {
            FakeSource(it, searchFailure = LyricsPayloadException("invalid payload"))
        }

        assertEquals(
            LyricsMatchResult.InvalidResponse,
            LyricsMatchCoordinator(sources, sourceTimeoutMs = 100).match(request),
        )
    }

    @Test fun `empty successful searches return not found`() = runBlocking {
        val sources = LyricsSourceId.entries.map { id ->
            FakeSource(id, candidates = { emptyList() })
        }

        assertEquals(
            LyricsMatchResult.NotFound,
            LyricsMatchCoordinator(sources, sourceTimeoutMs = 100).match(request),
        )
    }

    @Test fun `one source timeout remains bounded while another result succeeds`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, searchDelayMs = 500),
            FakeSource(LyricsSourceId.Netease, searchDelayMs = 10),
        )
        lateinit var result: LyricsMatchResult

        val elapsed = measureTimeMillis {
            result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 80).match(request)
        }

        assertTrue(result is LyricsMatchResult.Found)
        assertTrue("source timeout should remain bounded, elapsed=$elapsed", elapsed < 300)
    }

    @Test fun `caller cancellation cancels provider work`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val source = object : LyricsSource {
            override val id = LyricsSourceId.QqMusic

            override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }

            override suspend fun fetch(candidate: LyricsCandidate): SourceLyrics = basicLyrics("unused")
        }
        val job = async {
            LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 5_000).match(request)
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(cancelled.isCompleted)
    }

    private class FakeSource(
        override val id: LyricsSourceId,
        private val searchDelayMs: Long = 0,
        private val fetchDelayMs: Long = 0,
        private val searchFailure: Throwable? = null,
        private val candidates: (LyricsSearchQuery) -> List<LyricsCandidate> = { query ->
            listOf(candidate(query.request, id))
        },
        private val fetchResult: (LyricsCandidate) -> SourceLyrics = { basicLyrics("line") },
    ) : LyricsSource {
        var fetchCalls = 0
        val searchKeywords = mutableListOf<String>()

        override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
            delay(searchDelayMs)
            searchFailure?.let { throw it }
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
        fun basicLyrics(text: String) = SourceLyrics(
            LyricsTextParser.parseLrc("[00:01.00]$text"),
        )

        fun translatedLyrics() = SourceLyrics(
            original = LyricsTextParser.parseLrc("[00:01.00]Hello\n[00:03.00]World"),
            translation = LyricsTextParser.parseLrc(
                "[00:01.00]你好\n[00:03.00]世界",
                LyricsTrackKind.Translation,
            ),
        )

        fun wordTimedLyrics(withTranslation: Boolean) = SourceLyrics(
            original = LyricsTextParser.parseYrc(
                "[1000,2000](1000,500,0)Hel(1500,500,0)lo(2000,500,0) world",
            ),
            translation = if (withTranslation) {
                LyricsTextParser.parseLrc("[00:01.00]你好世界", LyricsTrackKind.Translation)
            } else {
                null
            },
        )

        fun candidate(
            source: LyricsSourceId,
            remoteId: String,
            title: String = "Song",
            artists: List<String> = listOf("Artist"),
            album: String? = "Album",
        ) = LyricsCandidate(source, remoteId, title, artists, album, 180_000)

        fun candidate(
            request: LyricsMatchRequest,
            source: LyricsSourceId,
            remoteId: String = source.name,
        ) = LyricsCandidate(
            source,
            remoteId,
            request.title,
            request.artists,
            request.album,
            request.durationMs,
        )
    }
}
