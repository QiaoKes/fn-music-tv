package com.fnmusic.tv.core.model.preferences

import com.fnmusic.tv.core.model.PlayerStyle

enum class CacheBudget(val megabytes: Int) {
    Small(128), Medium(256), Default(512), Large(1024);

    val mediaBytes: Long get() = megabytes * 1024L * 1024L * 3L / 4L
    val artworkBytes: Long get() = megabytes * 1024L * 1024L / 4L
}

data class AppPreferencesState(
    val playerStyle: PlayerStyle = PlayerStyle.Poster,
    val cacheBudget: CacheBudget = CacheBudget.Default,
)

data class CacheUsage(
    val mediaBytes: Long,
    val artworkBytes: Long,
    val indexBytes: Long = 0,
) {
    val totalBytes: Long get() = mediaBytes + artworkBytes
}
