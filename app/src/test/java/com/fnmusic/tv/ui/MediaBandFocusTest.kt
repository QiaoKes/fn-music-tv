package com.fnmusic.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBandFocusTest {
    @Test
    fun returnsToLastFocusedEntry() {
        assertEquals(
            "artist:3",
            mediaBandReturnFocusKey(
                entryKeys = listOf("artist:1", "artist:2", "artist:3"),
                terminalKey = "all-artists",
                lastFocusedKey = "artist:3",
            ),
        )
        assertEquals(
            "all-artists",
            mediaBandReturnFocusKey(
                entryKeys = listOf("artist:1", "artist:2"),
                terminalKey = "all-artists",
                lastFocusedKey = "all-artists",
            ),
        )
    }

    @Test
    fun staleKeyFallsBackToFirstLiveEntry() {
        assertEquals(
            "album:1",
            mediaBandReturnFocusKey(
                entryKeys = listOf("album:1", "album:2"),
                terminalKey = "all-albums",
                lastFocusedKey = "album:removed",
            ),
        )
    }

    @Test
    fun emptyBandFallsBackToTerminalEntry() {
        assertEquals(
            "all-tracks",
            mediaBandReturnFocusKey(
                entryKeys = emptyList(),
                terminalKey = "all-tracks",
                lastFocusedKey = null,
            ),
        )
    }
}
