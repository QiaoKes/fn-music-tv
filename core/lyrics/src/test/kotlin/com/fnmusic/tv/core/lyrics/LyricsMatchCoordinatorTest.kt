package com.fnmusic.tv.core.lyrics

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
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

    @Test fun `all providers are awaited concurrently`() = runBlocking {
        val sources = listOf(
            FakeSource(LyricsSourceId.QqMusic, searchDelayMs = 100, fetchDelayMs = 100),
            FakeSource(LyricsSourceId.Kugou, searchDelayMs = 100, fetchDelayMs = 100),
            FakeSource(LyricsSourceId.Netease, searchDelayMs = 100, fetchDelayMs = 100),
        )
        lateinit var result: LyricsMatchResult

        val elapsed = measureTimeMillis {
            result = LyricsMatchCoordinator(sources, sourceTimeoutMs = 500).match(request)
        }

        assertTrue(result is LyricsMatchResult.Found)
        assertTrue("provider work should overlap, elapsed=$elapsed", elapsed < 500)
        assertEquals(listOf(1, 1, 1), sources.map(FakeSource::fetchCalls))
    }

    @Test fun `translation beats eligible word timing`() = runBlocking {
        val word = FakeSource(
            LyricsSourceId.Kugou,
            candidates = { listOf(candidate(LyricsSourceId.Kugou, "word", durationMs = 180_100)) },
            fetchResult = { wordLyrics() },
        )
        val translated = FakeSource(LyricsSourceId.Netease, fetchResult = { translatedLyrics() })

        val found = LyricsMatchCoordinator(listOf(translated, word)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsSourceId.Netease, found.lyrics.source)
        assertEquals(LyricsContentQuality.Translated, found.lyrics.quality)
    }

    @Test fun `eligible word timing breaks a translation coverage tie`() = runBlocking {
        val translatedLine = FakeSource(
            LyricsSourceId.Netease,
            fetchResult = { translatedLyrics() },
        )
        val translatedWord = FakeSource(
            LyricsSourceId.Kugou,
            fetchResult = { translatedWordLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(translatedLine, translatedWord)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsSourceId.Kugou, found.lyrics.source)
        assertEquals(LyricsContentQuality.WordTimed, found.lyrics.quality)
    }

    @Test fun `greater translation coverage beats eligible word timing`() = runBlocking {
        val fullyTranslated = FakeSource(
            LyricsSourceId.Kugou,
            fetchResult = { translatedLyrics() },
        )
        val partiallyTranslatedWord = FakeSource(
            LyricsSourceId.Netease,
            fetchResult = { partiallyTranslatedWordLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(partiallyTranslatedWord, fullyTranslated)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsSourceId.Kugou, found.lyrics.source)
        assertEquals(LyricsContentQuality.Translated, found.lyrics.quality)
    }

    @Test fun `translation beats a smaller duration delta`() = runBlocking {
        val exact = FakeSource(LyricsSourceId.Netease, fetchResult = { lineLyrics("plain") })
        val translated = FakeSource(
            LyricsSourceId.QqMusic,
            candidates = { listOf(candidate(LyricsSourceId.QqMusic, "translated", durationMs = 180_900)) },
            fetchResult = { translatedLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(exact, translated)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsSourceId.QqMusic, found.lyrics.source)
    }

    @Test fun `smaller duration delta beats provider order`() = runBlocking {
        val netease = FakeSource(
            LyricsSourceId.Netease,
            candidates = { listOf(candidate(LyricsSourceId.Netease, "near", durationMs = 180_500)) },
        )
        val kugou = FakeSource(
            LyricsSourceId.Kugou,
            candidates = { listOf(candidate(LyricsSourceId.Kugou, "nearest", durationMs = 180_100)) },
        )

        val found = LyricsMatchCoordinator(listOf(netease, kugou)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsSourceId.Kugou, found.lyrics.source)
    }

    @Test fun `provider tie order is netease then qq then kugou`() = runBlocking {
        val found = LyricsMatchCoordinator(
            listOf(
                FakeSource(LyricsSourceId.Kugou),
                FakeSource(LyricsSourceId.QqMusic),
                FakeSource(LyricsSourceId.Netease),
            ),
        ).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsSourceId.Netease, found.lyrics.source)
    }

    @Test fun `word timing at two second boundary remains eligible`() = runBlocking {
        val source = FakeSource(
            LyricsSourceId.Netease,
            candidates = { listOf(candidate(LyricsSourceId.Netease, "boundary", durationMs = 182_000)) },
            fetchResult = { wordLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(source)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsContentQuality.WordTimed, found.lyrics.quality)
        assertTrue(found.lyrics.lyrics.lines.single() is KaraokeLine)
    }

    @Test fun `word timing one millisecond beyond two seconds is downgraded`() = runBlocking {
        val source = FakeSource(
            LyricsSourceId.Netease,
            candidates = { listOf(candidate(LyricsSourceId.Netease, "downgraded", durationMs = 182_001)) },
            fetchResult = { wordLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(source)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsContentQuality.Basic, found.lyrics.quality)
        assertTrue(found.lyrics.lyrics.lines.all { it is SyncedLine })
    }

    @Test fun `word timing at five second boundary remains as downgraded line timing`() = runBlocking {
        val source = FakeSource(
            LyricsSourceId.Netease,
            candidates = { listOf(candidate(LyricsSourceId.Netease, "line-boundary", durationMs = 185_000)) },
            fetchResult = { wordLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(source)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsContentQuality.Basic, found.lyrics.quality)
        assertTrue(found.lyrics.lyrics.lines.single() is SyncedLine)
    }

    @Test fun `candidate one millisecond beyond five seconds is rejected`() = runBlocking {
        val source = FakeSource(
            LyricsSourceId.Netease,
            candidates = { listOf(candidate(LyricsSourceId.Netease, "rejected", durationMs = 185_001)) },
            fetchResult = { wordLyrics() },
        )

        val result = LyricsMatchCoordinator(listOf(source)).match(request)

        assertEquals(LyricsMatchResult.NotFound, result)
        assertEquals(0, source.fetchCalls)
    }

    @Test fun `unknown duration never enables word timing`() = runBlocking {
        val source = FakeSource(
            LyricsSourceId.Netease,
            candidates = { listOf(candidate(LyricsSourceId.Netease, "unknown", durationMs = null)) },
            fetchResult = { wordLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(source)).match(request) as LyricsMatchResult.Found

        assertEquals(LyricsContentQuality.Basic, found.lyrics.quality)
        assertTrue(found.lyrics.lyrics.lines.single() is SyncedLine)
    }

    @Test fun `all fetched candidates participate in content ranking`() = runBlocking {
        val source = FakeSource(
            LyricsSourceId.QqMusic,
            candidates = {
                listOf(
                    candidate(LyricsSourceId.QqMusic, "a"),
                    candidate(LyricsSourceId.QqMusic, "b"),
                )
            },
            fetchResult = { if (it.remoteId == "b") translatedLyrics() else lineLyrics("plain") },
        )

        val found = LyricsMatchCoordinator(listOf(source)).match(request) as LyricsMatchResult.Found

        assertEquals("b", found.lyrics.candidate.remoteId)
        assertEquals(2, source.fetchCalls)
    }

    @Test fun `provider attempts at most three metadata eligible candidates`() = runBlocking {
        val source = FakeSource(
            LyricsSourceId.QqMusic,
            candidates = {
                (1..4).map { index -> candidate(LyricsSourceId.QqMusic, index.toString()) }
            },
            fetchResult = { throw LyricsPayloadException("invalid lyrics") },
        )

        val result = LyricsMatchCoordinator(listOf(source)).match(request)

        assertEquals(LyricsMatchResult.InvalidResponse, result)
        assertEquals(3, source.fetchCalls)
    }

    @Test fun `delayed richer provider still participates in final ranking`() = runBlocking {
        val rtrt = LyricsMatchRequest(
            localId = "rtrt",
            title = "RTRT",
            artists = listOf("Mili"),
            album = "Miracle Milk",
            durationMs = 215_000,
        )
        fun rtrtCandidate(source: LyricsSourceId) = LyricsCandidate(
            source = source,
            remoteId = source.name,
            title = rtrt.title,
            artists = rtrt.artists,
            album = rtrt.album,
            durationMs = rtrt.durationMs,
        )
        val plain = FakeSource(
            LyricsSourceId.QqMusic,
            candidates = { listOf(rtrtCandidate(LyricsSourceId.QqMusic)) },
            fetchResult = { lineLyrics("plain") },
        )
        val rich = FakeSource(
            LyricsSourceId.Netease,
            searchDelayMs = 120,
            candidates = { listOf(rtrtCandidate(LyricsSourceId.Netease)) },
            fetchResult = { translatedWordLyrics() },
        )

        val found = LyricsMatchCoordinator(listOf(plain, rich), sourceTimeoutMs = 500)
            .match(rtrt) as LyricsMatchResult.Found

        assertEquals(LyricsSourceId.Netease, found.lyrics.source)
        assertEquals(LyricsContentQuality.WordTimed, found.lyrics.quality)
        assertEquals(listOf(1, 1), listOf(plain.fetchCalls, rich.fetchCalls))
    }

    @Test fun `terminal provider failures retain their classification`() = runBlocking {
        val networkSources = LyricsSourceId.entries.map { id ->
            FakeSource(id, searchFailure = LyricsTransportException("offline"))
        }
        val invalidSources = LyricsSourceId.entries.map { id ->
            FakeSource(id, searchFailure = LyricsPayloadException("malformed"))
        }

        assertEquals(
            LyricsMatchResult.NetworkFailure,
            LyricsMatchCoordinator(networkSources).match(request),
        )
        assertEquals(
            LyricsMatchResult.InvalidResponse,
            LyricsMatchCoordinator(invalidSources).match(request),
        )
    }

    @Test fun `one provider timeout does not discard another result`() = runBlocking {
        val result = LyricsMatchCoordinator(
            listOf(
                FakeSource(LyricsSourceId.QqMusic, searchDelayMs = 500),
                FakeSource(LyricsSourceId.Netease),
            ),
            sourceTimeoutMs = 80,
        ).match(request)

        assertTrue(result is LyricsMatchResult.Found)
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
            override suspend fun fetch(candidate: LyricsCandidate): SyncedLyrics = lineLyrics("unused")
        }
        val job = async { LyricsMatchCoordinator(listOf(source), sourceTimeoutMs = 5_000).match(request) }

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
            listOf(candidate(id, id.name, durationMs = query.request.durationMs))
        },
        private val fetchResult: (LyricsCandidate) -> SyncedLyrics = { lineLyrics("line") },
    ) : LyricsSource {
        var fetchCalls = 0

        override suspend fun search(query: LyricsSearchQuery): List<LyricsCandidate> {
            delay(searchDelayMs)
            searchFailure?.let { throw it }
            return candidates(query)
        }

        override suspend fun fetch(candidate: LyricsCandidate): SyncedLyrics {
            fetchCalls++
            delay(fetchDelayMs)
            return fetchResult(candidate)
        }
    }

    private companion object {
        fun lineLyrics(text: String) = parseLyrics("[00:01.00]$text")

        fun translatedLyrics() = parseLyrics(
            original = "[00:01.00]Hello\n[00:03.00]World",
            translation = "[00:01.00]你好\n[00:03.00]世界",
        )

        fun wordLyrics() = parseLyrics(
            "[1000,2000](1000,500,0)Hel(1500,500,0)lo(2000,500,0) world",
        ).also { assertTrue(it.lines.single() is KaraokeLine) }

        fun translatedWordLyrics() = parseLyrics(
            original = "[1000,2000](1000,500,0)Hel(1500,500,0)lo(2000,500,0) world",
            translation = "[00:01.00]\u4f60\u597d\u4e16\u754c",
        ).also { assertTrue(it.lines.single() is KaraokeLine) }

        fun partiallyTranslatedWordLyrics() = parseLyrics(
            original = """
                [1000,1000](1000,500,0)Hel(1500,500,0)lo
                [3000,1000](3000,500,0)Wor(3500,500,0)ld
            """.trimIndent(),
            translation = "[00:01.00]\u4f60\u597d",
        ).also { lyrics -> assertTrue(lyrics.lines.all { it is KaraokeLine }) }

        fun candidate(
            source: LyricsSourceId,
            remoteId: String,
            durationMs: Long? = 180_000,
        ) = LyricsCandidate(
            source = source,
            remoteId = remoteId,
            title = "Song",
            artists = listOf("Artist"),
            album = "Album",
            durationMs = durationMs,
            mediaId = "mid",
        )
    }
}
