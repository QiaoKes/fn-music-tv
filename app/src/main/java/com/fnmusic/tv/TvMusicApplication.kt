package com.fnmusic.tv

import android.app.Application
import android.content.Intent
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.data.repository.SessionState
import com.fnmusic.tv.core.data.repository.SessionRepository
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.playback.PlaybackController
import com.fnmusic.tv.core.playback.PlaybackService
import com.fnmusic.tv.core.data.local.LocalStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvMusicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.startPlaybackRuntime()
    }
}

class AppContainer(private val application: Application) {
    val localStore = LocalStore(application)
    val sessionRepository = SessionRepository(application)
    val appPreferences = AppPreferences(application, localStore)
    val musicRepository = MusicRepository(application, sessionRepository, appPreferences, localStore)
    val playbackController = PlaybackController(application, localStore, musicRepository)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val nowPlayingPresenter = NowPlayingPresenter(
        playbackController,
        musicRepository,
        appPreferences,
        applicationScope,
    )
    private val playbackRuntimeStarted = AtomicBoolean(false)

    fun startPlaybackRuntime() {
        if (!playbackRuntimeStarted.compareAndSet(false, true)) return
        playbackController.connect()
        nowPlayingPresenter.start()
        applicationScope.launch {
            var boundNamespace: String? = null
            sessionRepository.state.collectLatest { state ->
                when (state) {
                    is SessionState.SignedIn -> {
                        val namespace = sessionRepository.cacheNamespace()
                        if (boundNamespace != namespace) {
                            appPreferences.bindNamespace(namespace)
                            musicRepository.applyArtworkBudget()
                            boundNamespace = namespace
                        }
                        playbackController.configure(sessionRepository.playbackCredentials())
                    }
                    is SessionState.SignedOut -> {
                        val departingNamespace = boundNamespace
                        boundNamespace = null
                        if (state.error == AppError.Unauthenticated || state.error == AppError.AccountDisabled) {
                            clearInvalidatedPlaybackSession(
                                departingNamespace = departingNamespace,
                                clearPlaybackSession = playbackController::clearSessionDurably,
                                invalidateNamespace = { namespace ->
                                    musicRepository.invalidateNamespace(namespace, includeEssential = true)
                                },
                                clearArtwork = musicRepository::clearArtwork,
                            )
                        }
                    }
                    SessionState.Loading -> Unit
                }
            }
        }
        applicationScope.launch { sessionRepository.restore() }
    }

    suspend fun shutdownForExit() = withContext(NonCancellable) {
        try {
            playbackController.stopForAppExit()
        } finally {
            application.stopService(Intent(application, PlaybackService::class.java))
        }
    }
}

internal suspend fun clearInvalidatedPlaybackSession(
    departingNamespace: String?,
    clearPlaybackSession: suspend () -> Unit,
    invalidateNamespace: suspend (String) -> Unit,
    clearArtwork: suspend () -> Unit,
) = withContext(NonCancellable) {
    clearPlaybackSession()
    if (departingNamespace == null) clearArtwork() else invalidateNamespace(departingNamespace)
}
