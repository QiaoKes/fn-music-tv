package com.fnmusic.tv.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTextParserTest {
    @Test fun `lrc parses fractional timestamps and infers line ends`() {
        val track = LyricsTextParser.parseLrc("[00:01.25]first\n[00:03.500]second")

        assertEquals(1_250L, track.lines[0].startMs)
        assertEquals(3_500L, track.lines[0].endMs)
        assertEquals("second", track.lines[1].text)
        assertNull(track.lines[1].endMs)
    }

    @Test fun `yrc retains word timing`() {
        val track = LyricsTextParser.parseYrc("[1000,2000](1000,500,0)你(1500,500,0)好")

        assertEquals("你好", track.lines.single().text)
        assertEquals(1_000L, track.lines.single().words.first().startMs)
        assertEquals(2_000L, track.lines.single().words.last().endMs)
    }

    @Test fun `duplicate lrc timestamps become original and translation tracks`() {
        val lyrics = LyricsTextParser.parseMultiTrackLrc("[00:01.00]Hello\n[00:01.00]你好\n[00:03.00]World\n[00:03.00]世界")

        assertEquals(listOf("Hello", "World"), lyrics.original.lines.map { it.text })
        assertEquals(listOf("你好", "世界"), lyrics.translation?.lines?.map { it.text })
    }

    @Test fun `alignment keeps original first and skips duplicate translation`() {
        val original = LyricsTextParser.parseLrc("[00:01.00]Hello\n[00:03.00]World")
        val translation = LyricsTextParser.parseLrc("[00:01.20]你好\n[00:03.10]World", LyricsTrackKind.Translation)

        val aligned = LyricsTrackAligner.align(original, translation)

        assertEquals(listOf("Hello", "你好"), aligned[0].words.map { it.text })
        assertEquals(listOf("World"), aligned[1].words.map { it.text })
        assertTrue(aligned.all { it.words.isNotEmpty() })
    }
}
