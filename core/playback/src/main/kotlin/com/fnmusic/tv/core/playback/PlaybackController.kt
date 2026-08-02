package com.fnmusic.tv.core.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.data.repository.MusicRepository
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.PlaybackCredentials
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity
import com.fnmusic.tv.core.model.playback.PlayMode
import com.fnmusic.tv.core.model.playback.PlaybackQueueItem
import com.fnmusic.tv.core.model.playback.QueueKind
import com.fnmusic.tv.core.model.playback.QueuePageItem
import com.fnmusic.tv.core.model.playback.QueuePageSegment
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.playback.RepeatBehavior
import com.fnmusic.tv.core.model.playback.SlidingQueueReducer
import com.fnmusic.tv.core.model.playback.SlidingQueueState
import com.fnmusic.tv.core.model.playback.mapping
import com.fnmusic.tv.core.model.playback.next
import java.util.ArrayList
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val title: String = "",
    val artist: String = "",
    val audioFormat: String = "",
    val mediaId: String = "",
    val coverId: String? = null,
    val artworkUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val currentIndex: Int = 0,
    val itemCount: Int = 0,
    val loadedPlayableCount: Int = 0,
    val queueKind: QueueKind = QueueKind.Normal,
    val queueItems: List<PlaybackQueueItem> = emptyList(),
    val playMode: PlayMode = PlayMode.ListRepeat,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val presentationRevision: Long = 0,
    val nowPlayingIdentity: NowPlayingIdentity? = null,
    val roamBusy: Boolean = false,
    val roamError: AppError? = null,
    val canRetryRoam: Boolean = false,
    val error: String? = null,
    val queueError: String? = null,
    val canRetryQueue: Boolean = false,
)

internal data class CapturedNowPlayingFields(
    val mediaId: String,
    val title: String,
    val artist: String,
    val audioFormat: String,
    val coverId: String?,
    val artworkUrl: String?,
)

internal fun captureNowPlayingFields(item: MediaItem?): CapturedNowPlayingFields? {
    item ?: return null
    val metadata = item.mediaMetadata
    return CapturedNowPlayingFields(
        mediaId = item.mediaId,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        audioFormat = metadata.extras?.getString(AUDIO_FORMAT_KEY).orEmpty(),
        coverId = metadata.extras?.getString(COVER_ID_KEY),
        artworkUrl = metadata.artworkUri?.toString(),
    )
}

data class PlaybackSnapshotCommitState(
    val namespace: String? = null,
    val requestedRevision: Long = 0L,
    val committedRevision: Long = 0L,
    val failure: String? = null,
) {
    val pending: Boolean get() = requestedRevision > committedRevision && failure == null
}

class PlaybackTransition internal constructor(
    val revision: Long,
    private val completion: Deferred<Unit>,
) {
    suspend fun awaitCommitted() = completion.await()
}

class PlaybackController(
    private val context: Context,
    private val localStore: LocalStore,
    private val musicRepository: MusicRepository,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private val _snapshotCommitState = MutableStateFlow(PlaybackSnapshotCommitState())
    val snapshotCommitState: StateFlow<PlaybackSnapshotCommitState> = _snapshotCommitState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshotWriter = PlaybackSnapshotWriter(scope, localStore::savePlaybackSnapshot)
    private val snapshotPersistence = PlaybackSnapshotPersistence(scope, snapshotWriter)
    private val transitionLock = Any()
    private var latestTransition: PlaybackTransition? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var queuePageJob: Job? = null
    private var roamJob: Job? = null
    private var pendingCredentials: PlaybackCredentials? = null
    private var currentNamespace: String? = null
    private var restored = false
    private var generation = 0L
    private var snapshotRevision = 0L
    private var presentationRevision = 0L
    private var lastPresentationKey: PresentationKey? = null
    private var lastSnapshotAt = 0L
    private var queueKind = QueueKind.Normal
    private var playMode = PlayMode.ListRepeat
    private var shuffleOrderIds: List<String> = emptyList()
    private var pendingShuffle: PendingShuffleActivation? = null
    private var frozenQueueSnapshot: PlaybackSnapshot? = null
    private var queueSource: QueueSource? = null
    private var queueWindow: SlidingQueueState? = null
    private var failedDirection: QueueDirection? = null
    private var queueError: String? = null
    private var roamWindow: RoamWindow? = null
    private var currentRoamId: String? = null
    private var roamError: AppError? = null
    private var failedRoamDirection: RoamDirection? = null
    private val autoAdvanceGate = RoamAutoAdvanceGate()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let { player ->
                project(player, forcePresentation = mediaItem != null)
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    autoAdvanceGate.reset()
                    structuralSnapshot(player)
                }
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            controller?.let { project(it) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            controller?.let { project(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = controller ?: return
            project(player)
            if (playbackState == Player.STATE_ENDED && queueKind == QueueKind.Roam) {
                if (autoAdvanceGate.tryConsume(generation, player.currentMediaItem?.mediaId)) {
                    advanceRoam(RoamDirection.Next)
                }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            project(player)
        }
    }

    fun connect() {
        PlaybackTransportBridge.register { direction ->
            scope.launch {
                when (direction) {
                    PlaybackTransportDirection.Previous -> advanceRoam(RoamDirection.Previous)
                    PlaybackTransportDirection.Next -> advanceRoam(RoamDirection.Next)
                }
            }
        }
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Restoring)
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(listener)
                    pendingCredentials?.let(::configure)
                    project(mediaController)
                    startTicker()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun configure(credentials: PlaybackCredentials) {
        pendingCredentials = credentials
        val binding = playbackNamespaceBinding(currentNamespace, credentials.cacheNamespace)
        if (binding != PlaybackNamespaceBinding.Same) {
            beginStructuralTransition()
            if (binding == PlaybackNamespaceBinding.Rebind) resetActiveSessionForRebind(controller)
            restored = false
            currentNamespace = credentials.cacheNamespace
            _snapshotCommitState.value = PlaybackSnapshotCommitState(namespace = credentials.cacheNamespace)
            PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Restoring)
        }
        val player = controller ?: return
        val args = Bundle().apply {
            putString(PlaybackCommands.Token, credentials.rawAuthorization)
            putString(PlaybackCommands.CacheNamespace, credentials.cacheNamespace)
            putString(PlaybackCommands.AccessCode, credentials.accessCodeHeader)
            putBoolean(PlaybackCommands.RelayMode, credentials.relayMode)
        }
        val configureGeneration = generation
        val future = player.sendCustomCommand(PlaybackCommands.ConfigureAuthCommand, args)
        future.addListener(
            {
                val configured = runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS }
                    .getOrDefault(false)
                if (!configured || currentNamespace != credentials.cacheNamespace) return@addListener
                if (hasConfiguredQueue(restored, player.mediaItemCount)) {
                    restored = true
                    PlaybackTransportBridge.setOwnership(ownershipFor(queueKind))
                    project(player)
                    return@addListener
                }
                scope.launch {
                    val account = localStore.account(credentials.cacheNamespace)
                    if (
                        generation != configureGeneration ||
                        currentNamespace != credentials.cacheNamespace ||
                        player.mediaItemCount > 0
                    ) {
                        return@launch
                    }
                    restored = true
                    restoreQueue(player, account?.queueJson, account?.frozenQueueJson)
                    PlaybackTransportBridge.setOwnership(ownershipFor(queueKind))
                    project(player)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun playQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int = 0,
        autoPlay: Boolean = true,
        source: QueueSource? = null,
        windowStart: Int = 0,
        firstPage: Int = 1,
        lastPage: Int = firstPage + ((tracks.size - 1).coerceAtLeast(0) / PAGE_SIZE),
        knownTotal: Int? = null,
        segments: List<QueuePageSegment>? = null,
    ): PlaybackTransition? {
        val player = controller ?: return null
        if (tracks.isEmpty()) return null
        beginStructuralTransition()
        val selected = startIndex.coerceIn(tracks.indices)
        val activeIds = tracks.map { track -> track.track.guid.value }
        val previousShuffleOrder = shuffleOrderIds
        val restoreShuffle = playMode == PlayMode.Shuffle
        if (restoreShuffle) {
            playMode = PlayMode.ListRepeat
            shuffleOrderIds = emptyList()
        }
        queueKind = QueueKind.Normal
        roamWindow = null
        currentRoamId = null
        roamError = null
        failedRoamDirection = null
        frozenQueueSnapshot = null
        queueSource = source
        queueWindow = source?.let { queueSource ->
            segments?.takeIf(List<QueuePageSegment>::isNotEmpty)?.let { pages ->
                SlidingQueueState.fromSegments(pages, selected).also { window ->
                    require(window.guids == activeIds) { "Queue segments must exactly match playable tracks" }
                    require(window.sort == queueSource.sort) { "Queue segments must use the source sort order" }
                    require(window.windowStart == windowStart) { "Queue window start does not match its segments" }
                    require(window.firstPage == firstPage && window.lastPage == lastPage) {
                        "Queue page bounds do not match their segments"
                    }
                    require(window.knownTotal == knownTotal) { "Queue total does not match its segments" }
                }
            }
        }
        failedDirection = null
        queueError = null
        player.setMediaItems(tracks.map(::mediaItem), selected, 0L)
        applyModeLocally(player, playMode)
        player.prepare()
        if (autoPlay) player.play() else player.pause()
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
        project(player)
        val transition = structuralSnapshot(player)
        if (restoreShuffle) {
            val retained = previousShuffleOrder.filter(activeIds::contains)
            val added = activeIds.filterNot(retained::contains)
            requestShuffleActivation(
                player = player,
                order = retained + stableShuffle(added, snapshotRevision + 1L),
                activationRevision = snapshotRevision + 1L,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = true,
                persistFallbackOnReject = false,
            )
        }
        return transition
    }

    suspend fun startRoam(): Boolean {
        if (queueKind != QueueKind.Normal || roamJob?.isActive == true || _state.value.roamBusy) return false
        val player = controller ?: return false
        val expectedGeneration = generation
        val previousQueueError = queueError
        val previousFailedDirection = failedDirection
        updateRoamStatus(busy = true, error = null)
        val request = scope.async(start = CoroutineStart.LAZY) {
            var rollbackSnapshot: PlaybackSnapshot? = null
            var installedGeneration: Long? = null
            var installedRoamId: String? = null
            try {
                val initial = musicRepository.startRoam() ?: throw AppException(AppError.Empty)
                val resolved = resolveRoam(initial, RoamDirection.Next, expectedGeneration, currentCursor = null)
                if (generation != expectedGeneration) throw CancellationException("Playback session changed")
                rollbackSnapshot = if (player.mediaItemCount > 0) {
                    captureSnapshot(player, revision = snapshotRevision, includeFrozen = false)
                } else {
                    null
                }
                frozenQueueSnapshot = rollbackSnapshot
                beginStructuralTransition(cancelRoam = false)
                installedGeneration = generation
                installedRoamId = resolved.window.current.roamId
                queueKind = QueueKind.Roam
                queueSource = null
                queueWindow = null
                roamWindow = resolved.window
                currentRoamId = installedRoamId
                failedDirection = null
                queueError = null
                installRoamTrack(player, resolved.track, autoPlay = true)
                PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Roam)
                structuralSnapshot(player)?.awaitCommitted()
                updateRoamStatus(busy = false, error = null)
                true
            } catch (cause: CancellationException) {
                if (
                    installedGeneration == generation &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                    failedDirection = previousFailedDirection
                    queueError = previousQueueError
                }
                throw cause
            } catch (cause: Throwable) {
                if (
                    installedGeneration == generation &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                    failedDirection = previousFailedDirection
                    queueError = previousQueueError
                }
                updateRoamStatus(busy = false, error = cause.appError())
                false
            } finally {
                val running = currentCoroutineContext()[Job]
                if (roamJob === running) {
                    roamJob = null
                    updateRoamStatus(busy = false, error = roamError)
                }
                controller?.let(::project)
            }
        }
        roamJob = request
        request.start()
        return try {
            request.await()
        } catch (cause: CancellationException) {
            request.cancel(cause)
            throw cause
        }
    }

    fun enterRoam(track: PlaybackTrack): PlaybackTransition? {
        val player = controller ?: return null
        if (queueKind == QueueKind.Normal && player.mediaItemCount > 0) {
            frozenQueueSnapshot = captureSnapshot(player, revision = snapshotRevision, includeFrozen = false)
        }
        beginStructuralTransition()
        queueKind = QueueKind.Roam
        queueSource = null
        queueWindow = null
        currentRoamId = null
        installRoamTrack(player, track, autoPlay = true)
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Roam)
        return structuralSnapshot(player)
    }

    fun replaceRoamTrack(track: PlaybackTrack): PlaybackTransition? {
        val player = controller ?: return null
        installRoamTrack(player, track, autoPlay = true)
        return structuralSnapshot(player)
    }

    fun exitRoam(): Boolean = exitRoamTransition().restoredQueue

    suspend fun exitRoamDurably(): Boolean {
        val exit = exitRoamTransition()
        exit.transition?.awaitCommitted()
        return exit.restoredQueue
    }

    private fun exitRoamTransition(): RoamExitTransition {
        val player = controller ?: return RoamExitTransition(restoredQueue = false, transition = null)
        beginStructuralTransition()
        val snapshot = frozenQueueSnapshot?.takeIf { it.items.isNotEmpty() }
        queueKind = QueueKind.Normal
        roamWindow = null
        currentRoamId = null
        roamError = null
        failedRoamDirection = null
        frozenQueueSnapshot = null
        if (snapshot == null) {
            player.stop()
            player.clearMediaItems()
            queueSource = null
            queueWindow = null
            PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
            project(player)
            return RoamExitTransition(
                restoredQueue = false,
                transition = structuralSnapshot(player),
            )
        }
        val shuffleOrderToRestore = applySnapshot(player, snapshot, activatePersistedShuffle = false)
        player.pause()
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
        project(player)
        val transition = structuralSnapshot(player)
        shuffleOrderToRestore?.let { order ->
            requestShuffleActivation(
                player = player,
                order = order,
                activationRevision = snapshotRevision + 1L,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = true,
                persistFallbackOnReject = false,
            )
        }
        return RoamExitTransition(
            restoredQueue = true,
            transition = transition,
        )
    }

    fun retryRoam() {
        failedRoamDirection?.let(::advanceRoam)
    }

    fun playPause() {
        controller?.let { player ->
            when {
                player.isPlaying -> player.pause()
                player.playbackState == Player.STATE_ENDED -> {
                    player.seekToDefaultPosition()
                    player.play()
                }
                player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 -> {
                    player.prepare()
                    player.play()
                }
                else -> player.play()
            }
        }
    }

    fun next() {
        if (queueKind == QueueKind.Roam) advanceRoam(RoamDirection.Next)
        else controller?.seekToNextMediaItem()
    }

    fun previous() {
        if (queueKind == QueueKind.Roam) {
            advanceRoam(RoamDirection.Previous)
            return
        }
        controller?.let { player ->
            if (player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) player.seekTo(0L)
            else player.seekToPreviousMediaItem()
        }
    }

    fun seekBy(offsetMs: Long) = controller?.let { player ->
        val upperBound = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + offsetMs).coerceIn(0L, upperBound))
    }

    fun selectQueueItem(queueIndex: Int): PlaybackTransition? {
        val player = controller ?: return null
        if (queueKind != QueueKind.Normal || queueIndex !in 0 until player.mediaItemCount) return null
        player.seekTo(queueIndex, 0L)
        player.play()
        return structuralSnapshot(player)
    }

    fun cyclePlayMode() = setPlayMode(playMode.next())

    fun setPlayMode(mode: PlayMode) {
        val player = controller ?: return
        if (queueKind != QueueKind.Normal || mode == playMode || pendingShuffle != null) return
        if (mode != PlayMode.Shuffle) {
            playMode = mode
            shuffleOrderIds = emptyList()
            applyModeLocally(player, mode)
            project(player)
            structuralSnapshot(player)
            return
        }
        val ids = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }
        if (ids.isEmpty() || ids.distinct().size != ids.size) return
        val proposedRevision = snapshotRevision + 1L
        requestShuffleActivation(
            player = player,
            order = stableShuffle(ids, proposedRevision),
            activationRevision = proposedRevision,
            fallbackMode = playMode,
            persistOnAccept = true,
            persistFallbackOnReject = false,
        )
    }

    private fun requestShuffleActivation(
        player: MediaController,
        order: List<String>,
        activationRevision: Long,
        fallbackMode: PlayMode,
        persistOnAccept: Boolean,
        persistFallbackOnReject: Boolean,
    ) {
        val canonical = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }
        if (canonical.isEmpty() || canonical.distinct().size != canonical.size || canonical.toSet() != order.toSet()) {
            fallBackFromShuffle(player, fallbackMode, persistFallbackOnReject)
            return
        }
        val request = PendingShuffleActivation(
            generation = generation,
            baseRevision = snapshotRevision,
            activationRevision = activationRevision,
            canonicalIds = canonical,
            orderIds = order,
            fallbackMode = fallbackMode,
            persistOnAccept = persistOnAccept,
            persistFallbackOnReject = persistFallbackOnReject,
        )
        pendingShuffle = request
        val args = Bundle().apply {
            putStringArrayList(PlaybackCommands.MediaIds, ArrayList(order))
            putLong(PlaybackCommands.SnapshotRevision, activationRevision)
        }
        val future = player.sendCustomCommand(PlaybackCommands.SetShuffleOrderCommand, args)
        future.addListener(
            {
                if (pendingShuffle !== request) return@addListener
                if (generation != request.generation || queueKind != QueueKind.Normal) {
                    pendingShuffle = null
                    return@addListener
                }
                val result = runCatching { future.get() }.getOrNull()
                val acknowledgement = result
                    ?.takeIf { it.resultCode == SessionResult.RESULT_SUCCESS }
                    ?.let {
                        ShuffleAcknowledgement(
                            revision = it.extras.getLong(PlaybackCommands.SnapshotRevision, -1L),
                            orderIds = it.extras.getStringArrayList(PlaybackCommands.MediaIds).orEmpty(),
                        )
                    }
                val accepted = acknowledgement != null && request.accepts(
                    acknowledgement = acknowledgement,
                    currentGeneration = generation,
                    currentRevision = snapshotRevision,
                    currentCanonicalIds = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId },
                )
                if (!accepted) {
                    pendingShuffle = null
                    fallBackFromShuffle(player, request.fallbackMode, request.persistFallbackOnReject)
                    return@addListener
                }
                pendingShuffle = null
                playMode = PlayMode.Shuffle
                shuffleOrderIds = request.orderIds
                applyModeLocally(player, PlayMode.Shuffle)
                project(player)
                if (request.persistOnAccept) {
                    check(structuralSnapshot(player)?.revision == request.activationRevision) {
                        "Shuffle activation revision changed before persistence"
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun fallBackFromShuffle(player: MediaController, mode: PlayMode, persist: Boolean) {
        playMode = mode.takeUnless { it == PlayMode.Shuffle } ?: PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        applyModeLocally(player, playMode)
        project(player)
        if (persist) structuralSnapshot(player)
    }

    fun enrichCurrentItem(track: Track) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        if (index !in 0 until player.mediaItemCount || current.mediaId != track.guid.value) return
        val old = current.mediaMetadata
        val coverId = track.coverId ?: old.extras?.getString(COVER_ID_KEY)
        val updatedMetadata = old.buildUpon()
            .setTitle(track.title.ifBlank { old.title })
            .setArtist(track.artistName ?: old.artist)
            .setAlbumTitle(track.albumName ?: old.albumTitle)
            .setExtras(mediaExtras(track.audioFormat ?: old.extras?.getString(AUDIO_FORMAT_KEY), coverId))
            .build()
        val oldKey = presentationKey(current)
        val updated = current.buildUpon().setMediaMetadata(updatedMetadata).build()
        if (oldKey == presentationKey(updated)) return
        player.replaceMediaItem(index, updated)
        project(player)
        structuralSnapshot(player)
    }

    fun retryQueuePage() {
        val direction = failedDirection ?: return
        failedDirection = null
        queueError = null
        loadQueuePage(direction)
    }

    fun clearSession(): PlaybackTransition? {
        val namespace = currentNamespace
        beginStructuralTransition()
        controller?.run {
            stop()
            clearMediaItems()
            sendCustomCommand(PlaybackCommands.ClearAuthCommand, Bundle.EMPTY)
        }
        pendingCredentials = null
        restored = false
        currentNamespace = null
        frozenQueueSnapshot = null
        queueSource = null
        queueWindow = null
        queueKind = QueueKind.Normal
        playMode = PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        roamWindow = null
        currentRoamId = null
        failedDirection = null
        queueError = null
        roamError = null
        failedRoamDirection = null
        lastPresentationKey = null
        val transition = namespace?.let { value ->
            val clearRevision = ++snapshotRevision
            val acknowledgement = snapshotPersistence.submitStructural(
                PlaybackSnapshotWriteRequest(value, clearRevision, null),
            )
            trackTransition(value, clearRevision, acknowledgement) {
                localStore.clearNamespace(value, includeEssential = true)
            }
        }
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Restoring)
        _state.value = PlaybackUiState(connected = controller != null)
        return transition
    }

    suspend fun clearSessionDurably() {
        clearSession()?.awaitCommitted()
    }

    fun disconnect() {
        controller?.let { checkpointSnapshot(it, force = true) }
        beginStructuralTransition()
        releaseController()
    }

    suspend fun stopForAppExit() {
        beginStructuralTransition()
        val player = controller
        try {
            player?.pause()
            player?.let(::project)
            player?.let { structuralSnapshot(it, PlaybackPlayIntent.Pause) }?.awaitCommitted()
        } finally {
            try {
                player?.stop()
            } finally {
                releaseController()
            }
        }
    }

    private fun releaseController() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        PlaybackTransportBridge.unregister()
        _state.value = PlaybackUiState()
    }

    private fun project(player: Player, forcePresentation: Boolean = false) {
        queueWindow = queueWindow?.copy(currentIndex = player.currentMediaItemIndex.coerceAtLeast(0))
        val currentItem = player.currentMediaItem
        val captured = captureNowPlayingFields(currentItem)
        val key = currentItem?.let(::presentationKey)
        if (key != null && (forcePresentation || key != lastPresentationKey)) {
            presentationRevision++
            lastPresentationKey = key
        } else if (key == null) {
            lastPresentationKey = null
        }
        val mediaId = captured?.mediaId.orEmpty()
        val title = captured?.title.orEmpty()
        val artist = captured?.artist.orEmpty()
        val audioFormat = captured?.audioFormat.orEmpty()
        val coverId = captured?.coverId
        val identity = if (mediaId.isNotBlank()) {
            currentNamespace?.let { namespace ->
                NowPlayingIdentity(
                    namespace = namespace,
                    mediaId = mediaId,
                    presentationRevision = presentationRevision,
                    title = title,
                    artist = artist,
                    audioFormat = audioFormat,
                    coverId = coverId,
                )
            }
        } else {
            null
        }
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val queueItems = List(player.mediaItemCount.coerceAtMost(MAX_QUEUE_ITEMS)) { index ->
            val item = player.getMediaItemAt(index)
            PlaybackQueueItem(
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                queueIndex = index,
                isCurrent = index == currentIndex,
            )
        }
        _state.value = PlaybackUiState(
            connected = true,
            hasMedia = player.mediaItemCount > 0,
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
            title = title,
            artist = artist,
            audioFormat = audioFormat,
            mediaId = mediaId,
            coverId = coverId,
            artworkUrl = captured?.artworkUrl,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            currentIndex = currentIndex,
            itemCount = player.mediaItemCount,
            loadedPlayableCount = queueItems.size,
            queueKind = queueKind,
            queueItems = queueItems,
            playMode = playMode,
            canPrevious = if (queueKind == QueueKind.Roam) currentRoamId != null else player.hasPreviousMediaItem(),
            canNext = if (queueKind == QueueKind.Roam) currentRoamId != null else player.hasNextMediaItem(),
            presentationRevision = presentationRevision,
            nowPlayingIdentity = identity,
            roamBusy = roamJob?.isActive == true || _state.value.roamBusy,
            roamError = roamError,
            canRetryRoam = failedRoamDirection != null,
            error = player.playerError?.errorCodeName,
            queueError = queueError,
            canRetryQueue = failedDirection != null && queueWindow?.invalidated == false,
        )
        maybeLoadQueueEdge(player)
    }

    private fun updateProgress(player: Player) {
        _state.value = _state.value.copy(
            connected = true,
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            error = player.playerError?.errorCodeName,
        )
    }

    private fun maybeLoadQueueEdge(player: Player) {
        if (queueKind != QueueKind.Normal) return
        val window = queueWindow ?: return
        if (window.loading || window.invalidated || queueError != null || player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        when {
            index <= PREFETCH_DISTANCE && !window.reachedStart -> loadQueuePage(QueueDirection.Previous)
            index >= player.mediaItemCount - 1 - PREFETCH_DISTANCE && !window.reachedEnd -> loadQueuePage(QueueDirection.Next)
        }
    }

    private fun loadQueuePage(direction: QueueDirection) {
        val source = queueSource ?: return
        val base = queueWindow ?: return
        if (queuePageJob?.isActive == true || base.loading || base.invalidated) return
        val loading = SlidingQueueReducer.loading(base)
        if (!loading.loading) return
        queueWindow = loading
        val pageNumber = if (direction == QueueDirection.Next) loading.lastPage + 1 else loading.firstPage - 1
        val expectedGeneration = generation
        queuePageJob = scope.launch {
            var result = runCatching { musicRepository.queuePage(source, pageNumber) }
            QUEUE_RETRY_DELAYS.forEach { retryDelay ->
                if (result.isFailure && generation == expectedGeneration) {
                    delay(retryDelay)
                    result = runCatching { musicRepository.queuePage(source, pageNumber) }
                }
            }
            if (generation != expectedGeneration || queueKind != QueueKind.Normal) return@launch
            try {
                applyQueuePage(direction, result.getOrThrow())
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Throwable) {
                queueWindow = queueWindow?.let(SlidingQueueReducer::failed)
                failedDirection = direction
                queueError = "队列分页加载失败"
            }
            queuePageJob = null
            controller?.let(::project)
        }
    }

    private suspend fun applyQueuePage(direction: QueueDirection, page: com.fnmusic.tv.core.model.Page<Track>) {
        val player = controller ?: return
        val state = queueWindow ?: return
        val prepared = musicRepository.prepareQueue(page.items)
        val preparedIds = prepared.map { it.track.guid.value }.toHashSet()
        val segment = QueuePageSegment(
            page = page.page,
            rawRowCount = page.items.size,
            playableItems = page.items.mapIndexedNotNull { index, track ->
                track.guid.value.takeIf(preparedIds::contains)?.let { mediaId ->
                    QueuePageItem(mediaId, (page.page - 1) * page.pageSize + index)
                }
            },
            sort = page.sort,
            knownTotal = page.total,
            pageSize = page.pageSize,
        )
        val update = if (direction == QueueDirection.Next) {
            SlidingQueueReducer.append(state, segment)
        } else {
            SlidingQueueReducer.prepend(state, segment)
        }
        queueWindow = update.state
        if (update.state.invalidated) {
            failedDirection = null
            queueError = "歌单已更新，请重新载入"
            return
        }
        val items = prepared.map(::mediaItem)
        if (direction == QueueDirection.Next) {
            if (items.isNotEmpty()) player.addMediaItems(items)
            if (update.removeFromStart > 0) player.removeMediaItems(0, update.removeFromStart)
        } else {
            if (items.isNotEmpty()) player.addMediaItems(0, items)
            if (update.removeFromEnd > 0) {
                val from = (player.mediaItemCount - update.removeFromEnd).coerceAtLeast(0)
                player.removeMediaItems(from, player.mediaItemCount)
            }
        }
        val shuffleReapplyPending = playMode == PlayMode.Shuffle
        val shuffleTransition = if (shuffleReapplyPending) reapplyShuffleAfterQueueMutation(player) else null
        failedDirection = null
        queueError = null
        project(player)
        val transition = if (shuffleReapplyPending) {
            shuffleTransition
        } else {
            structuralSnapshot(player)
        }
        transition?.awaitCommitted()
    }

    private fun advanceRoam(direction: RoamDirection) {
        if (queueKind != QueueKind.Roam || roamJob?.isActive == true) return
        val player = controller ?: return
        val roamId = currentRoamId ?: roamWindow?.current?.roamId ?: return
        val expectedGeneration = generation
        failedRoamDirection = null
        roamError = null
        updateRoamStatus(busy = true, error = null)
        val request = scope.launch(start = CoroutineStart.LAZY) {
            var rollbackSnapshot: PlaybackSnapshot? = null
            var installedRoamId: String? = null
            try {
                val first = when (direction) {
                    RoamDirection.Next -> musicRepository.nextRoam(roamId)
                    RoamDirection.Previous -> musicRepository.previousRoam(roamId)
                }
                val resolved = resolveRoam(first, direction, expectedGeneration, currentCursor = roamId)
                if (generation != expectedGeneration || queueKind != QueueKind.Roam || currentRoamId != roamId) return@launch
                rollbackSnapshot = captureSnapshot(player, revision = snapshotRevision)
                installedRoamId = resolved.window.current.roamId
                roamWindow = resolved.window
                currentRoamId = installedRoamId
                installRoamTrack(player, resolved.track, autoPlay = true)
                structuralSnapshot(player)?.awaitCommitted()
                autoAdvanceGate.reset()
                updateRoamStatus(busy = false, error = null)
            } catch (cause: CancellationException) {
                if (
                    generation == expectedGeneration &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                }
                throw cause
            } catch (cause: Throwable) {
                if (
                    generation == expectedGeneration &&
                    queueKind == QueueKind.Roam &&
                    currentRoamId == installedRoamId
                ) {
                    restoreAfterFailedRoamMutation(player, rollbackSnapshot)
                }
                failedRoamDirection = direction
                updateRoamStatus(busy = false, error = cause.appError())
            } finally {
                val running = currentCoroutineContext()[Job]
                if (roamJob === running) {
                    roamJob = null
                    updateRoamStatus(busy = false, error = roamError)
                }
                project(player)
            }
        }
        roamJob = request
        request.start()
    }

    private suspend fun resolveRoam(
        initial: RoamWindow,
        direction: RoamDirection,
        expectedGeneration: Long,
        currentCursor: String?,
    ): ResolvedRoam = resolvePlayableRoam(
        initial = initial,
        direction = direction,
        currentCursor = currentCursor,
        ensureCurrent = {
            if (generation != expectedGeneration) throw CancellationException("Playback session changed")
        },
        prepare = { window -> musicRepository.prepare(window.current.track) },
        move = { requestedDirection, roamId ->
            when (requestedDirection) {
                RoamDirection.Next -> musicRepository.nextRoam(roamId)
                RoamDirection.Previous -> musicRepository.previousRoam(roamId)
            }
        },
    )

    private fun installRoamTrack(player: MediaController, track: PlaybackTrack, autoPlay: Boolean) {
        player.setMediaItem(mediaItem(track))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        player.prepare()
        if (autoPlay) player.play() else player.pause()
        project(player)
    }

    private fun restoreAfterFailedRoamMutation(player: MediaController, snapshot: PlaybackSnapshot?) {
        if (snapshot == null || snapshot.items.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            queueKind = QueueKind.Normal
            playMode = PlayMode.ListRepeat
            shuffleOrderIds = emptyList()
            queueSource = null
            queueWindow = null
            roamWindow = null
            currentRoamId = null
            frozenQueueSnapshot = null
            PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)
            project(player)
            return
        }

        val shuffleOrderToRestore = applySnapshot(player, snapshot, activatePersistedShuffle = false)
        if (snapshot.playIntent == PlaybackPlayIntent.Play) player.play() else player.pause()
        PlaybackTransportBridge.setOwnership(ownershipFor(snapshot.kind))
        project(player)
        shuffleOrderToRestore?.let { order ->
            requestShuffleActivation(
                player = player,
                order = order,
                activationRevision = snapshotRevision,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = false,
                persistFallbackOnReject = true,
            )
        }
    }

    private fun updateRoamStatus(busy: Boolean, error: AppError?) {
        roamError = error
        _state.value = _state.value.copy(
            roamBusy = busy,
            roamError = error,
            canRetryRoam = failedRoamDirection != null,
        )
    }

    private fun resetActiveSessionForRebind(player: MediaController?) {
        player?.run {
            stop()
            clearMediaItems()
        }
        pendingShuffle = null
        frozenQueueSnapshot = null
        queueSource = null
        queueWindow = null
        queueKind = QueueKind.Normal
        playMode = PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        roamWindow = null
        currentRoamId = null
        failedDirection = null
        queueError = null
        roamError = null
        failedRoamDirection = null
        lastPresentationKey = null
        _state.value = PlaybackUiState(connected = player != null)
    }

    private fun beginStructuralTransition(cancelRoam: Boolean = true) {
        generation++
        pendingShuffle = null
        queuePageJob?.cancel()
        queuePageJob = null
        if (cancelRoam) {
            roamJob?.cancel()
            roamJob = null
            updateRoamStatus(busy = false, error = null)
        }
        autoAdvanceGate.reset()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.let { player ->
                    updateProgress(player)
                    checkpointSnapshot(player)
                }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun mediaItem(playback: PlaybackTrack): MediaItem = MediaItem.Builder()
        .setMediaId(playback.track.guid.value)
        .setUri(playback.streamUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playback.track.title)
                .setArtist(playback.track.artistName)
                .setAlbumTitle(playback.track.albumName)
                .setArtworkUri(playback.artworkUrl?.let(Uri::parse))
                .setExtras(mediaExtras(playback.track.audioFormat, playback.track.coverId))
                .build(),
        )
        .build()

    private fun applyModeLocally(player: Player, mode: PlayMode) {
        player.repeatMode = when (mode.mapping.repeatBehavior) {
            RepeatBehavior.Off -> Player.REPEAT_MODE_OFF
            RepeatBehavior.One -> Player.REPEAT_MODE_ONE
            RepeatBehavior.All -> Player.REPEAT_MODE_ALL
        }
        player.shuffleModeEnabled = mode.mapping.shuffleEnabled
    }

    private fun stableShuffle(ids: List<String>, revision: Long): List<String> {
        val seed = revision xor (currentNamespace?.hashCode()?.toLong() ?: 0L)
        val random = Random(seed)
        return ids.toMutableList().apply {
            for (index in lastIndex downTo 1) {
                val other = random.nextInt(index + 1)
                val value = this[index]
                this[index] = this[other]
                this[other] = value
            }
        }
    }

    private fun reapplyShuffleAfterQueueMutation(player: MediaController): PlaybackTransition? {
        val canonical = List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }
        val retained = shuffleOrderIds.filter(canonical::contains)
        val added = canonical.filterNot(retained::contains)
        val order = retained + stableShuffle(added, snapshotRevision + 2L)
        playMode = PlayMode.ListRepeat
        shuffleOrderIds = emptyList()
        applyModeLocally(player, playMode)
        val transition = structuralSnapshot(player)
        requestShuffleActivation(
            player = player,
            order = order,
            activationRevision = snapshotRevision + 1L,
            fallbackMode = PlayMode.ListRepeat,
            persistOnAccept = true,
            persistFallbackOnReject = false,
        )
        return transition
    }

    private fun checkpointSnapshot(player: Player, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSnapshotAt < SNAPSHOT_INTERVAL_MS) return
        if (pendingShuffle != null) return
        lastSnapshotAt = now
        val namespace = currentNamespace ?: return
        val revision = ++snapshotRevision
        snapshotPersistence.enqueueCheckpoint(
            PlaybackSnapshotWriteRequest(
                namespace = namespace,
                revision = revision,
                payload = PlaybackSnapshotCodec.encode(captureSnapshot(player, revision)),
            ),
        )
    }

    private fun structuralSnapshot(
        player: Player,
        playIntentOverride: PlaybackPlayIntent? = null,
    ): PlaybackTransition? {
        lastSnapshotAt = System.currentTimeMillis()
        val namespace = currentNamespace ?: return null
        val revision = ++snapshotRevision
        val request = PlaybackSnapshotWriteRequest(
            namespace = namespace,
            revision = revision,
            payload = PlaybackSnapshotCodec.encode(
                captureSnapshot(player, revision, playIntentOverride = playIntentOverride),
            ),
        )
        return trackTransition(namespace, revision, snapshotPersistence.submitStructural(request))
    }

    suspend fun flushPlaybackSnapshot() {
        while (true) {
            val transition = synchronized(transitionLock) { latestTransition } ?: return
            transition.awaitCommitted()
            if (synchronized(transitionLock) { latestTransition === transition }) return
        }
    }

    private fun trackTransition(
        namespace: String,
        revision: Long,
        acknowledgement: Deferred<PlaybackSnapshotWriteResult>,
        afterCommit: suspend () -> Unit = {},
    ): PlaybackTransition {
        val previous = _snapshotCommitState.value.takeIf { it.namespace == namespace }
        _snapshotCommitState.value = PlaybackSnapshotCommitState(
            namespace = namespace,
            requestedRevision = revision,
            committedRevision = previous?.committedRevision ?: 0L,
        )
        val completion = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                acknowledgement.await()
                afterCommit()
                val current = _snapshotCommitState.value
                if (current.namespace == namespace && current.requestedRevision <= revision) {
                    _snapshotCommitState.value = current.copy(
                        committedRevision = maxOf(current.committedRevision, revision),
                        failure = null,
                    )
                }
            } catch (failure: Throwable) {
                val current = _snapshotCommitState.value
                if (current.namespace == namespace && current.requestedRevision <= revision) {
                    _snapshotCommitState.value = current.copy(failure = failure.message ?: failure.javaClass.simpleName)
                }
                throw failure
            }
        }
        return PlaybackTransition(revision, completion).also { transition ->
            synchronized(transitionLock) { latestTransition = transition }
        }
    }

    private fun captureSnapshot(
        player: Player,
        revision: Long,
        includeFrozen: Boolean = true,
        playIntentOverride: PlaybackPlayIntent? = null,
    ): PlaybackSnapshot = PlaybackSnapshot(
        generation = generation,
        revision = revision,
        items = List(player.mediaItemCount.coerceAtMost(MAX_QUEUE_ITEMS)) { index -> player.getMediaItemAt(index) },
        index = player.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = player.currentPosition.coerceAtLeast(0),
        source = queueSource,
        window = queueWindow,
        kind = queueKind,
        mode = playMode,
        shuffleOrder = shuffleOrderIds.takeIf { queueKind == QueueKind.Normal }.orEmpty(),
        roamWindow = roamWindow,
        currentRoamId = currentRoamId,
        frozen = frozenQueueSnapshot.takeIf { includeFrozen },
        playIntent = playIntentOverride
            ?: if (player.playWhenReady) PlaybackPlayIntent.Play else PlaybackPlayIntent.Pause,
    )

    private suspend fun restoreQueue(player: MediaController, encoded: String?, legacyFrozen: String?) {
        val snapshot = encoded?.let { PlaybackSnapshotCodec.decode(it, legacyFrozen) } ?: return
        if (snapshot.items.isEmpty()) return
        applySnapshot(player, snapshot)
        player.pause()
        if (snapshot.legacy) structuralSnapshot(player)?.awaitCommitted()
    }

    private fun applySnapshot(
        player: MediaController,
        snapshot: PlaybackSnapshot,
        activatePersistedShuffle: Boolean = true,
    ): List<String>? {
        if (snapshot.items.isEmpty()) return null
        player.setMediaItems(snapshot.items, snapshot.index.coerceIn(snapshot.items.indices), snapshot.positionMs)
        queueSource = snapshot.source
        queueWindow = snapshot.window
        queueKind = snapshot.kind
        val restoreShuffle = snapshot.kind == QueueKind.Normal && snapshot.mode == PlayMode.Shuffle
        playMode = if (restoreShuffle) PlayMode.ListRepeat else snapshot.mode
        shuffleOrderIds = if (restoreShuffle) emptyList() else snapshot.shuffleOrder
        roamWindow = snapshot.roamWindow
        currentRoamId = snapshot.currentRoamId
        frozenQueueSnapshot = snapshot.frozen
        snapshotRevision = maxOf(snapshotRevision, snapshot.revision)
        player.prepare()
        applyModeLocally(player, if (queueKind == QueueKind.Roam) PlayMode.Sequence else playMode)
        if (restoreShuffle && activatePersistedShuffle) {
            requestShuffleActivation(
                player = player,
                order = snapshot.shuffleOrder,
                activationRevision = snapshot.revision,
                fallbackMode = PlayMode.ListRepeat,
                persistOnAccept = false,
                persistFallbackOnReject = true,
            )
        }
        return snapshot.shuffleOrder.takeIf { restoreShuffle }
    }

    private fun mediaExtras(audioFormat: String?, coverId: String?): Bundle = Bundle().apply {
        audioFormat?.takeIf(String::isNotBlank)?.let { putString(AUDIO_FORMAT_KEY, it) }
        coverId?.takeIf(String::isNotBlank)?.let { putString(COVER_ID_KEY, it) }
    }


    private fun presentationKey(item: MediaItem): PresentationKey {
        val captured = requireNotNull(captureNowPlayingFields(item))
        return PresentationKey(
            mediaId = captured.mediaId,
            title = captured.title,
            artist = captured.artist,
            audioFormat = captured.audioFormat,
            coverId = captured.coverId,
        )
    }

    private fun ownershipFor(kind: QueueKind): PlaybackTransportOwnership = when (kind) {
        QueueKind.Normal -> PlaybackTransportOwnership.Normal
        QueueKind.Roam -> PlaybackTransportOwnership.Roam
    }

    private fun Throwable.appError(): AppError = (this as? AppException)?.error ?: AppError.Unknown()

    private data class PresentationKey(
        val mediaId: String,
        val title: String,
        val artist: String,
        val audioFormat: String,
        val coverId: String?,
    )

    private data class RoamExitTransition(
        val restoredQueue: Boolean,
        val transition: PlaybackTransition?,
    )

    private enum class QueueDirection { Previous, Next }

    private companion object {
        const val PAGE_SIZE = 50
        const val PREFETCH_DISTANCE = 15
        const val MAX_QUEUE_ITEMS = 250
        const val PROGRESS_TICK_MS = 250L
        const val SNAPSHOT_INTERVAL_MS = 5_000L
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        val QUEUE_RETRY_DELAYS = longArrayOf(500L, 1_000L, 2_000L)
    }
}

const val AUDIO_FORMAT_KEY = "com.fnmusic.tv.AUDIO_FORMAT"
const val COVER_ID_KEY = "com.fnmusic.tv.COVER_ID"
