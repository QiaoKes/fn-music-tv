package com.fnmusic.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun `display text colors remain legible on the app background`() {
        assertTrue(contrastRatio(FnColors.Text, FnColors.Background) >= 7.0f)
        assertTrue(contrastRatio(FnColors.Muted, FnColors.Background) >= 4.5f)
        assertTrue(contrastRatio(FnColors.Warning, FnColors.Background) >= 4.5f)
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
