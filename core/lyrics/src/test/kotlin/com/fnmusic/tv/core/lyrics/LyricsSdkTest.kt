package com.fnmusic.tv.core.lyrics

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSdkTest {
    @Test fun `standard lrc and duplicate timestamp translation use sdk model`() {
        val lyrics = parseLyrics("[00:01.25]Hello\n[00:01.25]你好\n[00:03.500]World")

        assertEquals(listOf(1_250, 3_500), lyrics.lines.map { it.start })
        assertEquals("Hello", lyrics.lines.first().lyricText())
        assertEquals("你好", lyrics.lines.first().translationText())
    }

    @Test fun `explicit sidecar stays attached when its text matches the source`() {
        val lyrics = parseLyrics(
            original = "[00:01.00]Same",
            translation = "[00:01.00]Same",
        )

        assertEquals("Same", lyrics.lines.single().translationText())
    }

    @Test fun `yrc keeps word timing and removes split japanese readings`() {
        val lyrics = parseLyrics(
            "[1000,1500](1000,300,0)世(1300,100,0)（(1400,300,0)よ(1700,100,0)）(1800,700,0)界",
        )

        val line = lyrics.lines.single() as KaraokeLine
        assertEquals("世界", line.lyricText())
        assertEquals(listOf("世", "界"), line.syllables.map { it.content })
        assertEquals(listOf(1_000, 1_800), line.syllables.map { it.start })
    }

    @Test fun `line lyrics hide kana readings but preserve ordinary parentheses`() {
        val lyrics = parseLyrics("[00:01.00]世(よ)に虚（むな）しさ Live (2024)")

        assertEquals("世に虚しさ Live (2024)", (lyrics.lines.single() as SyncedLine).content)
    }

    @Test fun `qrc xml adapter keeps syllable timing`() {
        val lyrics = parseLyrics(
            """<Lyric_1 LyricType="1" LyricContent="[1000,1000]你(1000,500)好(1500,500)"/>""",
        )

        val line = lyrics.lines.single() as KaraokeLine
        assertEquals("你好", line.lyricText())
        assertEquals(listOf(1_000, 1_500), line.syllables.map { it.start })
    }

    @Test fun `krc uses sdk parser`() {
        val lyrics = parseLyrics("[0,1000]<0,500,0>Hel<500,500,0>lo")

        val line = lyrics.lines.single() as KaraokeLine
        assertEquals("Hello", line.lyricText())
        assertTrue(line.syllables.all { it.end > it.start })
    }
}
