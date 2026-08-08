package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.data.api.LyricDto
import com.fnmusic.tv.core.data.api.LyricListDto
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.lyric.LyricTimeline
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MusicRepositoryLyricsTest {
    @Test fun `parses preferred yrc even when api does not mark it as lrc`() {
        val response = LyricListDto(
            list = listOf(
                LyricDto(
                    guid = "yrc",
                    content = "[20630,5210](20630,180,0)あ(20810,160,0)な",
                    isLRC = false,
                ),
            ),
            preferred = "yrc",
        )

        val (document, timeline) = decodeLyrics(response)

        assertFalse(document!!.isLrc)
        assertNotNull(timeline)
        assertEquals(20_630L, timeline!!.lines.single().startMs)
        assertEquals("あな", timeline.lines.single().texts.single())
    }

    @Test fun `online lyrics win while misses and failures fall back to first party`() = runBlocking {
        val firstPartyCalls = AtomicInteger()
        val firstParty = {
            firstPartyCalls.incrementAndGet()
            CurrentResourceResult.Ready(currentLyrics("first party"))
        }

        val online = resolveLyricsWithFallback(true, { currentLyrics("online") }, firstParty)
        assertEquals("online", (online as CurrentResourceResult.Ready).value.document.content)
        assertEquals(0, firstPartyCalls.get())

        val missing = resolveLyricsWithFallback(true, { null }, firstParty)
        assertEquals("first party", (missing as CurrentResourceResult.Ready).value.document.content)
        val failed = resolveLyricsWithFallback(true, { error("provider failed") }, firstParty)
        assertEquals("first party", (failed as CurrentResourceResult.Ready).value.document.content)
        val disabled = resolveLyricsWithFallback(false, { error("must not search") }, firstParty)
        assertEquals("first party", (disabled as CurrentResourceResult.Ready).value.document.content)
        assertEquals(3, firstPartyCalls.get())
    }

    @Test fun `lyrics fallback never swallows caller cancellation`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                resolveLyricsWithFallback(
                    onlineMatchingEnabled = true,
                    online = { throw CancellationException("track changed") },
                    firstParty = { error("must not fall back") },
                )
            }
        }
    }

    private fun currentLyrics(content: String) = CurrentLyrics(
        document = LyricDocument("lyric", content, false, 0L),
        timeline = null as LyricTimeline?,
    )
}
