package com.fnmusic.tv

import com.fnmusic.tv.core.data.preferences.AppPreferences
import com.fnmusic.tv.core.data.repository.CurrentLyrics
import com.fnmusic.tv.core.data.repository.CurrentResourceResult
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity
import com.fnmusic.tv.core.playback.PlaybackController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface NowPlayingResourceState<out T> {
    data object Loading : NowPlayingResourceState<Nothing>
    data class Ready<T>(val value: T) : NowPlayingResourceState<T>
    data object Absent : NowPlayingResourceState<Nothing>
    data class RetryableFailure(val error: AppError) : NowPlayingResourceState<Nothing>
}

data class NowPlayingPresentation(
    val identity: NowPlayingIdentity,
    val playerStyle: PlayerStyle,
    val metadata: NowPlayingResourceState<Track> = NowPlayingResourceState.Loading,
    val artwork: NowPlayingResourceState<ByteArray> = NowPlayingResourceState.Loading,
    val lyrics: NowPlayingResourceState<CurrentLyrics> = NowPlayingResourceState.Loading,
) {
    val canRetry: Boolean
        get() = metadata is NowPlayingResourceState.RetryableFailure ||
            artwork is NowPlayingResourceState.RetryableFailure ||
            lyrics is NowPlayingResourceState.RetryableFailure
}

class NowPlayingPresenter private constructor(
    private val identityFlow: Flow<NowPlayingIdentity?>,
    private val playerStyleFlow: Flow<PlayerStyle>,
    private val currentIdentity: () -> NowPlayingIdentity?,
    private val currentPlayerStyle: () -> PlayerStyle,
    private val requestMetadata: suspend (String) -> CurrentResourceResult<Track>,
    private val requestLyrics: suspend (String) -> CurrentResourceResult<CurrentLyrics>,
    private val requestArtwork: suspend (String, CoverVariant) -> CurrentResourceResult<ByteArray>,
    private val enrichCurrentItem: (Track) -> Unit,
    private val applicationScope: CoroutineScope,
) {
    constructor(
        playbackController: PlaybackController,
        musicRepository: MusicRepository,
        appPreferences: AppPreferences,
        applicationScope: CoroutineScope,
    ) : this(
        identityFlow = playbackController.state.map { it.nowPlayingIdentity },
        playerStyleFlow = appPreferences.state.map { it.playerStyle },
        currentIdentity = { playbackController.state.value.nowPlayingIdentity },
        currentPlayerStyle = { appPreferences.state.value.playerStyle },
        requestMetadata = musicRepository::currentTrackMetadata,
        requestLyrics = musicRepository::currentLyrics,
        requestArtwork = musicRepository::currentArtwork,
        enrichCurrentItem = playbackController::enrichCurrentItem,
        applicationScope = applicationScope,
    )

    internal constructor(
        identities: StateFlow<NowPlayingIdentity?>,
        playerStyles: StateFlow<PlayerStyle>,
        currentTrackMetadata: suspend (String) -> CurrentResourceResult<Track>,
        currentLyrics: suspend (String) -> CurrentResourceResult<CurrentLyrics>,
        currentArtwork: suspend (String, CoverVariant) -> CurrentResourceResult<ByteArray>,
        enrichCurrentItem: (Track) -> Unit,
        applicationScope: CoroutineScope,
    ) : this(
        identityFlow = identities,
        playerStyleFlow = playerStyles,
        currentIdentity = identities::value,
        currentPlayerStyle = playerStyles::value,
        requestMetadata = currentTrackMetadata,
        requestLyrics = currentLyrics,
        requestArtwork = currentArtwork,
        enrichCurrentItem = enrichCurrentItem,
        applicationScope = applicationScope,
    )

    private val _state = MutableStateFlow<NowPlayingPresentation?>(null)
    val state: StateFlow<NowPlayingPresentation?> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val requestOrdinal = AtomicLong(0L)
    private val lock = Any()
    private var activeToken: PresentationToken? = null
    private var presentationJob: Job? = null
    private var retryJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            combine(
                identityFlow.distinctUntilChanged(),
                playerStyleFlow.distinctUntilChanged(),
                ::PresentationInput,
            ).collect { input -> transitionTo(input.identity, input.playerStyle) }
        }
    }

    fun retryCurrentPresentation(): Boolean {
        val retry: RetryRequest = synchronized(lock) {
            if (retryJob?.isActive == true) return false
            val token = activeToken ?: return false
            val current = _state.value ?: return false
            if (!isCurrentLocked(token) || current.identity.presentationKey() != token.key) return false

            val selection = RetrySelection(
                metadata = current.metadata is NowPlayingResourceState.RetryableFailure,
                artwork = current.artwork is NowPlayingResourceState.RetryableFailure,
                lyrics = current.lyrics is NowPlayingResourceState.RetryableFailure,
            )
            if (!selection.any) return false

            _state.value = current.copy(
                metadata = if (selection.metadata) NowPlayingResourceState.Loading else current.metadata,
                artwork = if (selection.artwork) NowPlayingResourceState.Loading else current.artwork,
                lyrics = if (selection.lyrics) NowPlayingResourceState.Loading else current.lyrics,
            )
            RetryRequest(token, selection)
        }

        val job = applicationScope.launch { retryResources(retry.token, retry.selection) }
        synchronized(lock) {
            if (isCurrentLocked(retry.token)) retryJob = job else job.cancel()
        }
        return true
    }

    fun refreshCurrentPresentation(): Boolean {
        val identity = currentIdentity() ?: return false
        transitionTo(identity, currentPlayerStyle())
        return true
    }

    private fun transitionTo(identity: NowPlayingIdentity?, playerStyle: PlayerStyle) {
        val previousPresentation: Job?
        val previousRetry: Job?
        val token: PresentationToken?
        synchronized(lock) {
            previousPresentation = presentationJob
            previousRetry = retryJob
            presentationJob = null
            retryJob = null
            token = identity?.let {
                PresentationToken(
                    ordinal = requestOrdinal.incrementAndGet(),
                    key = it.presentationKey(),
                    identity = it,
                    playerStyle = playerStyle,
                )
            }
            activeToken = token
            _state.value = token?.let {
                NowPlayingPresentation(identity = it.identity, playerStyle = it.playerStyle)
            }
        }
        previousPresentation?.cancel()
        previousRetry?.cancel()

        token ?: return
        val job = applicationScope.launch { loadAllResources(token) }
        synchronized(lock) {
            if (activeToken == token) presentationJob = job else job.cancel()
        }
    }

    private suspend fun loadAllResources(token: PresentationToken) = coroutineScope {
        val metadataResult = CompletableDeferred<CurrentResourceResult<Track>>()
        launch {
            val result = requestSafely { requestMetadata(token.identity.mediaId) }
            metadataResult.complete(result)
            applyMetadata(token, result)
        }
        launch {
            applyLyrics(token, requestSafely { requestLyrics(token.identity.mediaId) })
        }
        launch {
            val coverId = token.identity.coverId
                ?: metadataResult.await().matchingTrack(token.identity)?.coverId
            val result = if (coverId.isNullOrBlank()) {
                CurrentResourceResult.Absent
            } else {
                requestSafely { requestArtwork(coverId, token.playerStyle.coverVariant()) }
            }
            applyArtwork(token, result)
        }
    }

    private suspend fun retryResources(token: PresentationToken, selection: RetrySelection) = coroutineScope {
        val metadataResult = CompletableDeferred<CurrentResourceResult<Track>>()
        if (selection.metadata) {
            launch {
                val result = requestSafely { requestMetadata(token.identity.mediaId) }
                metadataResult.complete(result)
                applyMetadata(token, result)
            }
        }
        if (selection.lyrics) {
            launch {
                applyLyrics(token, requestSafely { requestLyrics(token.identity.mediaId) })
            }
        }
        if (selection.artwork) {
            launch {
                val currentMetadataCover = synchronized(lock) {
                    (_state.value?.metadata as? NowPlayingResourceState.Ready<Track>)?.value?.coverId
                }
                val retriedMetadataCover = if (selection.metadata) {
                    metadataResult.await().matchingTrack(token.identity)?.coverId
                } else {
                    null
                }
                val coverId = token.identity.coverId ?: currentMetadataCover ?: retriedMetadataCover
                val result = if (coverId.isNullOrBlank()) {
                    CurrentResourceResult.Absent
                } else {
                    requestSafely { requestArtwork(coverId, token.playerStyle.coverVariant()) }
                }
                applyArtwork(token, result)
            }
        }
    }

    private fun applyMetadata(token: PresentationToken, result: CurrentResourceResult<Track>) {
        val state = when (result) {
            is CurrentResourceResult.Ready -> {
                if (result.value.guid.value != token.identity.mediaId) {
                    NowPlayingResourceState.Ready(token.identity.initialTrack())
                } else {
                    val track = result.value.withIdentityFallback(token.identity)
                    if (isCurrent(token)) enrichCurrentItem(track)
                    NowPlayingResourceState.Ready(track)
                }
            }
            CurrentResourceResult.Absent -> NowPlayingResourceState.Ready(token.identity.initialTrack())
            is CurrentResourceResult.Failure -> if (result.retryable) {
                NowPlayingResourceState.RetryableFailure(result.error)
            } else {
                NowPlayingResourceState.Ready(token.identity.initialTrack())
            }
        }
        updatePresentation(token) { it.copy(metadata = state) }
    }

    private fun applyLyrics(token: PresentationToken, result: CurrentResourceResult<CurrentLyrics>) {
        updatePresentation(token) { current -> current.copy(lyrics = result.toPresentationState()) }
    }

    private fun applyArtwork(token: PresentationToken, result: CurrentResourceResult<ByteArray>) {
        updatePresentation(token) { current -> current.copy(artwork = result.toPresentationState()) }
    }

    private fun updatePresentation(
        token: PresentationToken,
        transform: (NowPlayingPresentation) -> NowPlayingPresentation,
    ) {
        synchronized(lock) {
            if (!isCurrentLocked(token)) return
            val current = _state.value ?: return
            if (current.identity.presentationKey() != token.key || current.playerStyle != token.playerStyle) return
            _state.value = transform(current)
        }
    }

    private fun isCurrent(token: PresentationToken): Boolean = synchronized(lock) { isCurrentLocked(token) }

    private fun isCurrentLocked(token: PresentationToken): Boolean =
        activeToken == token &&
            currentIdentity()?.presentationKey() == token.key &&
            currentPlayerStyle() == token.playerStyle

    private suspend fun <T> requestSafely(
        request: suspend () -> CurrentResourceResult<T>,
    ): CurrentResourceResult<T> = try {
        request()
    } catch (cause: CancellationException) {
        currentCoroutineContext().ensureActive()
        CurrentResourceResult.Failure(
            error = AppError.Unknown("now_playing_request_cancelled"),
            retryable = false,
        )
    } catch (cause: AppException) {
        CurrentResourceResult.Failure(
            error = cause.error,
            retryable = false,
        )
    } catch (_: Exception) {
        CurrentResourceResult.Failure(
            error = AppError.Unknown("now_playing_request"),
            retryable = false,
        )
    }

    private fun NowPlayingIdentity.presentationKey() = PresentationKey(
        namespace = namespace,
        mediaId = mediaId,
        presentationRevision = presentationRevision,
    )

    private data class PresentationInput(
        val identity: NowPlayingIdentity?,
        val playerStyle: PlayerStyle,
    )

    private data class PresentationKey(
        val namespace: String,
        val mediaId: String,
        val presentationRevision: Long,
    )

    private data class PresentationToken(
        val ordinal: Long,
        val key: PresentationKey,
        val identity: NowPlayingIdentity,
        val playerStyle: PlayerStyle,
    )

    private data class RetrySelection(
        val metadata: Boolean,
        val artwork: Boolean,
        val lyrics: Boolean,
    ) {
        val any: Boolean get() = metadata || artwork || lyrics
    }

    private data class RetryRequest(
        val token: PresentationToken,
        val selection: RetrySelection,
    )
}

private fun NowPlayingIdentity.initialTrack() = Track(
    guid = TrackGuid(mediaId),
    title = title,
    artistName = artist.takeIf(String::isNotBlank),
    albumName = null,
    coverId = coverId,
    durationMs = null,
    isCue = false,
    audioFormat = audioFormat.takeIf(String::isNotBlank),
)

private fun Track.withIdentityFallback(identity: NowPlayingIdentity) = copy(
    title = title.ifBlank { identity.title },
    artistName = artistName ?: identity.artist.takeIf(String::isNotBlank),
    coverId = coverId ?: identity.coverId,
    audioFormat = audioFormat ?: identity.audioFormat.takeIf(String::isNotBlank),
)

private fun CurrentResourceResult<Track>.matchingTrack(identity: NowPlayingIdentity): Track? =
    (this as? CurrentResourceResult.Ready<Track>)?.value?.takeIf { it.guid.value == identity.mediaId }

private fun PlayerStyle.coverVariant(): CoverVariant = when (this) {
    PlayerStyle.Cover -> CoverVariant.Player
    PlayerStyle.Poster -> CoverVariant.Poster
}

private fun <T> CurrentResourceResult<T>.toPresentationState(): NowPlayingResourceState<T> = when (this) {
    is CurrentResourceResult.Ready -> NowPlayingResourceState.Ready(value)
    CurrentResourceResult.Absent -> NowPlayingResourceState.Absent
    is CurrentResourceResult.Failure -> if (retryable) {
        NowPlayingResourceState.RetryableFailure(error)
    } else {
        NowPlayingResourceState.Absent
    }
}
