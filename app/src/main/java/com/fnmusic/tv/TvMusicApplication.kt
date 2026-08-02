package com.fnmusic.tv

import android.app.Application
import com.fnmusic.tv.core.data.repository.SessionRepository
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.playback.PlaybackController
import com.fnmusic.tv.core.data.local.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TvMusicApplication : Application() {
    internal val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.startPlaybackRuntime()
    }
}

internal class AppContainer(private val application: Application) : AppUiDependencies {
    private val localStore = LocalStore(application)
    override val sessionRepository = SessionRepository(application)
    override val appPreferences = AppPreferences(application, localStore)
    override val musicRepository = MusicRepository(application, sessionRepository, appPreferences, localStore)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val artworkBitmapCache = ArtworkBitmapCache(
        scope = applicationScope,
        loader = { coverId, variant ->
            musicRepository.artwork(coverId, variant)?.let { bytes ->
                decodeArtwork(bytes, variant.width ?: 1_200)
            }
        },
    )
    override val playbackController = PlaybackController(
        application,
        LocalPlaybackSessionStore(localStore),
        RepositoryPlaybackContentSource(musicRepository),
    )
    override val nowPlayingPresenter = NowPlayingPresenter(
        playbackController,
        musicRepository,
        appPreferences,
        applicationScope,
    )
    private val coordinator = AuthenticatedAppCoordinator(
        application = application,
        sessionRepository = sessionRepository,
        musicRepository = musicRepository,
        appPreferences = appPreferences,
        playbackController = playbackController,
        artworkBitmapCache = artworkBitmapCache,
        nowPlayingPresenter = nowPlayingPresenter,
        applicationScope = applicationScope,
    )
    override val authenticatedActions: AuthenticatedAppActions = coordinator

    fun startPlaybackRuntime() {
        coordinator.start()
    }

    suspend fun shutdownForExit() = coordinator.shutdownForExit()
}
