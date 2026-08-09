package com.fnmusic.tv.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsCandidateScorerTest {
    private val scorer = LyricsCandidateScorer()
    private val request = LyricsMatchRequest(
        localId = "local",
        title = "晴天",
        artists = listOf("周杰伦"),
        album = "叶惠美",
        durationMs = 269_000,
    )

    @Test fun `exact metadata is accepted and cross-source agreement adds confidence`() {
        val candidates = listOf(
            candidate(LyricsSourceId.QqMusic),
            candidate(LyricsSourceId.Netease, remoteId = "2"),
        )

        val scored = scorer.score(request, candidates)

        assertEquals(2, scored.size)
        assertEquals(2, scored.first().consensusCount)
        assertEquals(100.0, scored.first().score, 0.001)
    }

    @Test fun `consensus counts distinct sources rather than duplicate candidates`() {
        val candidates = listOf(
            candidate(LyricsSourceId.QqMusic),
            candidate(LyricsSourceId.Netease, remoteId = "2"),
            candidate(LyricsSourceId.Netease, remoteId = "3"),
        )

        val scored = scorer.score(request, candidates)

        assertEquals(3, scored.size)
        assertTrue(scored.all { it.consensusCount == 2 })
    }

    @Test fun `duration mismatch at five seconds is accepted`() {
        assertEquals(1, scorer.score(request, listOf(candidate(durationMs = 274_000))).size)
    }

    @Test fun `duration mismatch one millisecond over five seconds is rejected`() {
        assertTrue(scorer.score(request, listOf(candidate(durationMs = 274_001))).isEmpty())
    }

    @Test fun `instrumental and live conflicts are rejected`() {
        assertTrue(scorer.score(request, listOf(candidate(title = "晴天 (Instrumental)", instrumental = true))).isEmpty())
        assertTrue(scorer.score(request, listOf(candidate(title = "晴天 Live"))).isEmpty())
    }

    @Test fun `full width and artist separators normalize consistently`() {
        val local = request.copy(title = "ＡＢＣ", artists = listOf("A / B"), album = null)
        val remote = candidate(title = "ABC", artists = listOf("A、B"), album = null)

        assertTrue(scorer.score(local, listOf(remote)).single().score >= 99.0)
    }

    private fun candidate(
        source: LyricsSourceId = LyricsSourceId.QqMusic,
        remoteId: String = "1",
        title: String = "晴天",
        artists: List<String> = listOf("周杰伦"),
        album: String? = "叶惠美",
        durationMs: Long = 269_000,
        instrumental: Boolean = false,
    ) = LyricsCandidate(source, remoteId, title, artists, album, durationMs, instrumental = instrumental)
}
