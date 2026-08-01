package com.fnmusic.tv.core.model.preferences

import com.fnmusic.tv.core.model.PlayerStyle

enum class CacheBudget(val megabytes: Int) {
    Small(32), Medium(64), Default(128), Large(256);

    val artworkBytes: Long get() = megabytes * 1024L * 1024L
}

data class AppPreferencesState(
    val playerStyle: PlayerStyle = PlayerStyle.Poster,
    val cacheBudget: CacheBudget = CacheBudget.Default,
)

data class CacheUsage(
    val artworkBytes: Long,
    val indexBytes: Long = 0,
) {
    val totalBytes: Long get() = artworkBytes + indexBytes
}
