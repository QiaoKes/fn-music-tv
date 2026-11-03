package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

/** Where the active lyric line is pinned inside the viewport during auto scrolling. */
enum class LyricsScrollAnchor {
    /** Anchor the active item's top edge at [KaraokeLyricsView.offset] from the viewport top. */
    Top,

    /** Anchor the active item's center at the vertical middle of the viewport. */
    Center,
}
