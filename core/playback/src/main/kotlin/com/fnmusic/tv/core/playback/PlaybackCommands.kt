package com.fnmusic.tv.core.playback

import androidx.media3.session.SessionCommand

internal object PlaybackCommands {
    const val ConfigureAuth = "com.fnmusic.tv.CONFIGURE_AUTH"
    const val ClearAuth = "com.fnmusic.tv.CLEAR_AUTH"
    const val SetShuffleOrder = "com.fnmusic.tv.SET_SHUFFLE_ORDER"
    const val Token = "raw_authorization"
    const val AccessCode = "access_code"
    const val RelayMode = "relay_mode"
    const val CacheNamespace = "cache_namespace"
    const val MediaIds = "media_ids"
    const val SnapshotRevision = "snapshot_revision"
    val ConfigureAuthCommand = SessionCommand(ConfigureAuth, android.os.Bundle.EMPTY)
    val ClearAuthCommand = SessionCommand(ClearAuth, android.os.Bundle.EMPTY)
    val SetShuffleOrderCommand = SessionCommand(SetShuffleOrder, android.os.Bundle.EMPTY)
}
