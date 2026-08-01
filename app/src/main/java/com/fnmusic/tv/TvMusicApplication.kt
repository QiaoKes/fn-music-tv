package com.fnmusic.tv

import android.app.Application
import com.fnmusic.tv.core.data.repository.SessionRepository
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.playback.PlaybackController
import com.fnmusic.tv.core.data.local.LocalStore

class TvMusicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val localStore = LocalStore(application)
    val sessionRepository = SessionRepository(application)
    val appPreferences = AppPreferences(application, localStore)
    val musicRepository = MusicRepository(application, sessionRepository, appPreferences, localStore)
    val playbackController = PlaybackController(application, localStore, musicRepository)
}
