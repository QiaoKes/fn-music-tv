package com.fnmusic.tv.core.playback

import androidx.media3.session.SessionCommand

internal object PlaybackCommands {
    const val ConfigureAuth = "com.fnmusic.tv.CONFIGURE_AUTH"
    const val ClearAuth = "com.fnmusic.tv.CLEAR_AUTH"
    const val ClearCache = "com.fnmusic.tv.CLEAR_CACHE"
    const val Token = "raw_authorization"
    const val CacheNamespace = "cache_namespace"
    val ConfigureAuthCommand = SessionCommand(ConfigureAuth, android.os.Bundle.EMPTY)
    val ClearAuthCommand = SessionCommand(ClearAuth, android.os.Bundle.EMPTY)
    val ClearCacheCommand = SessionCommand(ClearCache, android.os.Bundle.EMPTY)
}
