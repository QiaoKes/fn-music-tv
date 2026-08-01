package com.fnmusic.tv.core.model.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun `parses bom fractional times and multiple timestamps`() {
        val timeline = LrcParser.parse("\uFEFF[ar:Artist]\n[0:01.2][00:03.250] line\n[001:02]next")
        assertEquals(listOf(1_200L, 3_250L, 62_000L), timeline.lines.map { it.startMs })
        assertEquals("line", timeline.lines.first().texts.single())
    }

    @Test fun `groups multilingual lines at the same time`() {
        val timeline = LrcParser.parse("[00:10.00]Hello\n[00:10.00]你好")
        assertEquals(listOf("Hello", "你好"), timeline.lines.single().texts)
        assertEquals(0, timeline.activeIndex(10_100))
    }

    @Test fun `ignores malformed timestamps`() {
        assertEquals(emptyList<LyricLine>(), LrcParser.parse("[00:99.0]bad\nplain").lines)
    }
}
