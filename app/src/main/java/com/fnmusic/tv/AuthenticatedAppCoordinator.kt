package com.fnmusic.tv

import android.app.Application
import android.content.Intent
import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.data.repository.SessionRepository
import com.fnmusic.tv.core.data.repository.SessionState
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.playback.PlaybackController
import com.fnmusic.tv.core.playback.PlaybackService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface AuthenticatedAppActions {
    suspend fun verifyCurrentSession()
    suspend fun retrySessionRestore()
    suspend fun showLogin()
    suspend fun switchAccount()
    suspend fun clearAllEvictableCaches()
    suspend fun shutdownForExit()
}

internal class AuthenticatedAppCoordinator(
    private val application: Application,
    private val sessionRepository: SessionRepository,
    private val musicRepository: MusicRepository,
    private val appPreferences: AppPreferences,
    private val playbackController: PlaybackController,
    private val artworkBitmapCache: ArtworkBitmapCache,
    private val nowPlayingPresenter: NowPlayingPresenter,
    private val applicationScope: CoroutineScope,
) : AuthenticatedAppActions {
    private val started = AtomicBoolean(false)
    private var restoreJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        playbackController.connect()
        nowPlayingPresenter.start()
        applicationScope.launch {
            var boundNamespace: String? = null
            sessionRepository.state.collectLatest { state ->
                when (state) {
                    is SessionState.SignedIn -> {
                        val namespace = sessionRepository.cacheNamespace()
                        if (boundNamespace != namespace) {
                            artworkBitmapCache.clear()
                            musicRepository.clearFavoriteState()
                            appPreferences.bindNamespace(namespace)
                            musicRepository.applyArtworkBudget()
                            boundNamespace = namespace
                        }
                        playbackController.configure(sessionRepository.playbackCredentials())
                    }

                    is SessionState.SignedOut -> {
                        val departingNamespace = boundNamespace
                        boundNamespace = null
                        artworkBitmapCache.clear()
                        musicRepository.clearFavoriteState()
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

                    SessionState.Loading, is SessionState.Recovering -> Unit
                }
            }
        }
        launchSessionRestore()
    }

    override suspend fun verifyCurrentSession() {
        sessionRepository.verifyCurrentSession()
    }

    override suspend fun retrySessionRestore() {
        restoreJob?.cancelAndJoin()
        launchSessionRestore()
    }

    override suspend fun showLogin() {
        restoreJob?.cancelAndJoin()
        sessionRepository.showLogin()
    }

    override suspend fun switchAccount() {
        switchAuthenticatedAccount(
            clearPlaybackSession = playbackController::clearSessionDurably,
            clearArtwork = {
                artworkBitmapCache.clear()
                musicRepository.clearArtwork()
            },
            clearLocalNamespace = { musicRepository.clearLocalNamespace(includeEssential = false) },
            logout = sessionRepository::logout,
        )
    }

    override suspend fun clearAllEvictableCaches() {
        artworkBitmapCache.clear()
        musicRepository.clearAllEvictableCaches()
        nowPlayingPresenter.refreshCurrentPresentation()
    }

    override suspend fun shutdownForExit() = withContext(NonCancellable) {
        try {
            playbackController.stopForAppExit()
        } finally {
            application.stopService(Intent(application, PlaybackService::class.java))
        }
    }

    private fun launchSessionRestore() {
        restoreJob = applicationScope.launch { sessionRepository.restore() }
    }
}

internal suspend fun switchAuthenticatedAccount(
    clearPlaybackSession: suspend () -> Unit,
    clearArtwork: suspend () -> Unit,
    clearLocalNamespace: suspend () -> Unit,
    logout: suspend () -> Unit,
) {
    runCatching { clearPlaybackSession() }
    clearArtwork()
    clearLocalNamespace()
    logout()
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
