package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.data.api.LyricDto
import com.fnmusic.tv.core.data.api.LyricListDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
