package com.fnmusic.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateTest {
    @Test fun `lyric window keeps active line near the second slot`() {
        assertEquals(3..6, playerLyricWindow(lineCount = 10, activeIndex = 4))
    }

    @Test fun `lyric window remains full at timeline boundaries`() {
        assertEquals(0..3, playerLyricWindow(lineCount = 10, activeIndex = -1))
        assertEquals(6..9, playerLyricWindow(lineCount = 10, activeIndex = 9))
        assertEquals(0..2, playerLyricWindow(lineCount = 3, activeIndex = 1))
    }

    @Test fun `poster lyric keeps current line in the second slot at boundaries`() {
        assertEquals(listOf(null, 0, 1, 2), posterLyricIndices(lineCount = 4, activeIndex = -1))
        assertEquals(listOf(null, 0, 1, 2), posterLyricIndices(lineCount = 4, activeIndex = 0))
        assertEquals(listOf(2, 3, null, null), posterLyricIndices(lineCount = 4, activeIndex = 3))
    }

    @Test fun `poster lyric supports single lines and deduplicates translations`() {
        assertEquals(listOf("Plain lyric"), posterLyricTexts(listOf(" Plain lyric ")))
        assertEquals(
            listOf("Hello", "你好"),
            posterLyricTexts(listOf("Hello", "你好", "Hello", "第三行")),
        )
    }

    @Test fun `progress fraction handles invalid duration and clamps endpoints`() {
        assertEquals(0f, playerProgressFraction(positionMs = 1_000, durationMs = 0), 0f)
        assertEquals(0f, playerProgressFraction(positionMs = -1_000, durationMs = 10_000), 0f)
        assertEquals(0.25f, playerProgressFraction(positionMs = 2_500, durationMs = 10_000), 0f)
        assertEquals(1f, playerProgressFraction(positionMs = 12_000, durationMs = 10_000), 0f)
    }

    @Test fun `artwork ambience favors the dominant sampled hue and stays dark`() {
        val color = dominantArtworkColor(
            intArrayOf(
                0xFFFF3020.toInt(),
                0xFFFF3020.toInt(),
                0xFFFF3020.toInt(),
                0xFF2050FF.toInt(),
            ),
            fallbackKey = "track",
        )

        assertTrue(color.red > color.blue)
        assertTrue(maxOf(color.red, color.green, color.blue) <= 0.35f)
    }

    @Test fun `missing artwork ambience is stable and track specific`() {
        val first = fallbackAmbienceColor("Call Me Maybe|nihmune")
        assertEquals(first, fallbackAmbienceColor("Call Me Maybe|nihmune"))
        assertNotEquals(first, fallbackAmbienceColor("Another Track|Another Artist"))
    }

    @Test fun `artwork ambience ignores dominant black margins when color remains`() {
        val color = dominantArtworkColor(
            intArrayOf(
                0xFF050505.toInt(),
                0xFF050505.toInt(),
                0xFF050505.toInt(),
                0xFF20C060.toInt(),
                0xFF20C060.toInt(),
            ),
            fallbackKey = "track",
        )

        assertTrue(color.green > color.red)
        assertTrue(color.green > color.blue)
    }

    @Test fun `poster surface preserves hue while lifting ambience`() {
        val source = androidx.compose.ui.graphics.Color(0.1f, 0.2f, 0.34f)
        val surface = posterSurfaceColor(source)

        assertEquals(0.44f, maxOf(surface.red, surface.green, surface.blue), 0.001f)
        assertTrue(surface.blue > surface.green)
        assertTrue(surface.green > surface.red)
    }
}
