package com.fnmusic.tv

import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.data.repository.SessionRepository
import com.fnmusic.tv.core.playback.PlaybackController
import com.fnmusic.tv.update.UpdateController

internal interface AuthenticatedAppDependencies {
    val appPreferences: AppPreferences
    val musicRepository: MusicRepository
    val playbackController: PlaybackController
    val nowPlayingPresenter: NowPlayingPresenter
    val artworkBitmapCache: ArtworkBitmapCache
    val authenticatedActions: AuthenticatedAppActions
    val updateController: UpdateController
}

internal interface AppUiDependencies : AuthenticatedAppDependencies {
    val sessionRepository: SessionRepository
}
