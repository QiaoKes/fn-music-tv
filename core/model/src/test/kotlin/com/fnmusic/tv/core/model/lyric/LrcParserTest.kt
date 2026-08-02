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

    @Test fun `parses yrc word timing as synchronized lines`() {
        val timeline = LyricParser.parse(
            "[20630,5210](20630,180,0)あ(20810,160,0)な (20970,150,0)text",
        )

        assertEquals(listOf(20_630L), timeline.lines.map { it.startMs })
        assertEquals("あな text", timeline.lines.single().texts.single())
    }

    @Test fun `joins yrc metadata chunks and places untimed credits at the start`() {
        val timeline = LyricParser.parse(
            """{"t":-1,"c":[{"tx":"作曲: "},{"tx":"Ken Arai","li":"ignored"}]}""",
        )

        assertEquals(0L, timeline.lines.single().startMs)
        assertEquals("作曲: Ken Arai", timeline.lines.single().texts.single())
    }

    @Test fun `groups timed yrc metadata and ignores malformed structured lines`() {
        val timeline = LyricParser.parse(
            """
            {"t":5403,"c":[{"tx":"编曲: "},{"tx":"Alex San"}]}
            {"t":"bad","c":[{"tx":"ignored"}]}
            {"t":[],"c":{}}
            [5403,1000](5403,200,0)Hello
            [bad,1000](5403,200,0)ignored
            """.trimIndent(),
        )

        assertEquals(listOf("编曲: Alex San", "Hello"), timeline.lines.single().texts)
    }

    @Test fun `keeps lrc behavior through the format detecting parser`() {
        val timeline = LyricParser.parse("[00:10.00]Hello\n[00:10.00]你好")

        assertEquals(listOf("Hello", "你好"), timeline.lines.single().texts)
    }

    @Test fun `keeps lrc body when json credits are mixed into the document`() {
        val timeline = LyricParser.parse(
            """
            {"t":-1,"c":[{"tx":"作词: "},{"tx":"薔薇园アヴ"}]}
            {"t":1000,"c":[{"tx":"编曲: "},{"tx":"女王蜂"}]}
            [00:10.00]正文第一句
            [00:15.50]正文第二句
            """.trimIndent(),
        )

        assertEquals(listOf(0L, 1_000L, 10_000L, 15_500L), timeline.lines.map(LyricLine::startMs))
        assertEquals("正文第一句", timeline.lines[2].texts.single())
        assertEquals(2, timeline.activeIndex(12_000))
    }
}
