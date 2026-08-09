package com.fnmusic.tv.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import androidx.tv.material3.Button as TvMaterialButton
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.fnmusic.tv.AuthenticatedAppDependencies
import com.fnmusic.tv.NowPlayingPresentation
import com.fnmusic.tv.NowPlayingResourceState
import com.fnmusic.tv.decodeArtwork
import com.fnmusic.tv.core.data.repository.CurrentLyrics
import com.fnmusic.tv.core.data.repository.SessionState
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.playback.QueueKind
import com.fnmusic.tv.core.model.playback.PlayMode
import com.fnmusic.tv.core.model.playback.PlaybackQueueItem
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity
import com.fnmusic.tv.core.model.playback.MAX_ACTIVE_QUEUE_ITEMS
import com.fnmusic.tv.core.model.playback.QueuePageItem
import com.fnmusic.tv.core.model.playback.QueuePageSegment
import com.fnmusic.tv.core.model.playback.boundedQueueWindow
import com.fnmusic.tv.core.playback.PlaybackUiState
import com.fnmusic.tv.core.playback.PlaybackProgressState
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

private data class PlayerArtworkDecodeRequest(
    val key: PlayerArtworkKey,
    val bytes: ByteArray,
    val targetLongEdge: Int,
)

internal data class DecodedPlayerArtwork(
    val key: PlayerArtworkKey,
    val sourceBytes: ByteArray,
    val bitmap: Bitmap?,
    val ambienceColor: Color,
    val posterSurfaceColor: Color,
)

internal class PlayerVisualContinuity {
    val lyrics = PlayerVisualResourceContinuity<CurrentLyrics>(PlayerVisualRetentionScope.SameMedia)
    val artwork = PlayerVisualResourceContinuity<DecodedPlayerArtwork>(PlayerVisualRetentionScope.SameNamespace)
}

private data class ExtractedArtworkColors(
    val ambience: Color,
    val posterSurface: Color,
)

@Composable
private fun rememberCurrentArtwork(
    identity: NowPlayingIdentity?,
    playerStyle: PlayerStyle,
    presentation: PlayerPresentationProjection,
    continuity: PlayerVisualContinuity,
): DecodedPlayerArtwork? {
    val readyArtwork = when (val artwork = presentation.artwork) {
        is NowPlayingResourceState.Ready -> artwork.value
        else -> null
    }
    val request = remember(identity, playerStyle, presentation.playerStyle, readyArtwork) {
        if (identity == null || presentation.playerStyle != playerStyle || readyArtwork == null) {
            null
        } else {
            val variant = if (playerStyle == PlayerStyle.Poster) CoverVariant.Poster else CoverVariant.Player
            PlayerArtworkDecodeRequest(
                key = playerArtworkKey(identity, playerStyle),
                bytes = readyArtwork,
                targetLongEdge = variant.width ?: 1_200,
            )
        }
    }
    val decodedResource = when (val artwork = presentation.artwork) {
        is NowPlayingResourceState.Loading,
        is NowPlayingResourceState.Ready,
        -> NowPlayingResourceState.Loading
        NowPlayingResourceState.Absent -> NowPlayingResourceState.Absent
        is NowPlayingResourceState.RetryableFailure -> NowPlayingResourceState.RetryableFailure(artwork.error)
    }
    val retained = continuity.artwork.resolve(identity, decodedResource)
    val retainedArtwork = (retained as? NowPlayingResourceState.Ready)?.value
    val decoded by produceState(
        initialValue = retainedArtwork,
        identity?.namespace,
        request?.key,
        request?.bytes,
        presentation.artwork,
    ) {
        val currentRequest = request
        if (currentRequest == null) {
            if (decodedResource !is NowPlayingResourceState.Loading) value = null
            return@produceState
        }
        val currentArtwork = withContext(Dispatchers.Default) {
            val bitmap = decodeArtwork(currentRequest.bytes, currentRequest.targetLongEdge)
            val colors = bitmap?.let(::extractArtworkColors) ?: fallbackArtworkColors()
            DecodedPlayerArtwork(
                key = currentRequest.key,
                sourceBytes = currentRequest.bytes,
                bitmap = bitmap,
                ambienceColor = colors.ambience,
                posterSurfaceColor = colors.posterSurface,
            )
        }
        continuity.artwork.resolve(identity, NowPlayingResourceState.Ready(currentArtwork))
        value = currentArtwork
    }
    return when {
        identity == null -> null
        request == null && presentation.artwork !is NowPlayingResourceState.Loading -> null
        else -> decoded
    }
}

@Composable
internal fun ImmersivePlayer(
    container: AuthenticatedAppDependencies,
    playback: PlaybackUiState,
    visualContinuity: PlayerVisualContinuity,
    onExitRoam: () -> Unit,
) {
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val nowPlayingPresentation by container.nowPlayingPresenter.state.collectAsStateWithLifecycle()
    val presentation = projectPlayerPresentation(playback.nowPlayingIdentity, nowPlayingPresentation)
    val metadata = when (val state = presentation.metadata) {
        is NowPlayingResourceState.Ready -> state.value
        else -> null
    }
    val displayedLyrics = visualContinuity.lyrics.resolve(playback.nowPlayingIdentity, presentation.lyrics)
    val currentLyrics = when (val state = displayedLyrics) {
        is NowPlayingResourceState.Ready -> state.value
        else -> null
    }
    val syncedLyrics = currentLyrics?.syncedLyrics
    val staticLyric = currentLyrics?.document?.content?.takeIf { syncedLyrics == null }
    val lyricsLoading = displayedLyrics is NowPlayingResourceState.Loading
    val lyricsFailed = displayedLyrics is NowPlayingResourceState.RetryableFailure
    val favoriteLibraryState by container.musicRepository.favoriteState.collectAsStateWithLifecycle()
    val currentFavorite = favoriteLibraryState.statuses[playback.mediaId] ?: metadata?.isFavorite ?: false
    val favoritePending = playback.mediaId in favoriteLibraryState.pending
    var controlsVisible by remember { mutableStateOf(true) }
    var queueVisible by remember { mutableStateOf(false) }
    var consumeBackKeyUp by remember { mutableStateOf(false) }
    var consumeCenterKeyUp by remember { mutableStateOf(false) }
    var interactionEpoch by remember { mutableStateOf(0) }
    val playerFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val previousFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val modeFocus = remember { FocusRequester() }
    val favoriteFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val exitRoamFocus = remember { FocusRequester() }
    val statusRetryFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val roaming = playback.queueKind == QueueKind.Roam
    val playbackProgress = container.playbackController.progress.collectAsStateWithLifecycle()
    val actionScope = rememberCoroutineScope()
    fun revealControls() {
        controlsVisible = true
        interactionEpoch++
    }
    fun dismissPlayerChrome() {
        queueVisible = false
        controlsVisible = false
        playerFocus.requestFocus()
    }
    val title = metadata?.title?.takeUnless(String::isBlank)
        ?: playback.title.takeUnless(String::isBlank)
        ?: "尚未选择歌曲"
    val artist = metadata?.artistName?.takeUnless(String::isBlank)
        ?: playback.artist
    val audioFormat = metadata?.audioFormat?.takeUnless(String::isBlank)
        ?: playback.audioFormat
    val poster = preferences.playerStyle == PlayerStyle.Poster
    val artwork = rememberCurrentArtwork(
        identity = playback.nowPlayingIdentity,
        playerStyle = preferences.playerStyle,
        presentation = presentation,
        continuity = visualContinuity,
    )
    val artworkBitmap = artwork?.bitmap
    val ambienceColor = artwork?.ambienceColor ?: fallbackAmbienceColor()
    val posterPanelColor = artwork?.posterSurfaceColor ?: posterSurfaceColor(ambienceColor)
    val previousEnabled = playback.canPrevious && !playback.roamBusy
    val nextEnabled = playback.canNext && !playback.roamBusy
    val statusRetryAvailable = playerStatus(
        roamError = playback.roamError,
        canRetryRoam = playback.canRetryRoam,
        queueError = playback.queueError,
        canRetryQueue = playback.canRetryQueue,
        presentationError = presentation.retryableFailure,
        canRetryPresentation = presentation.canRetry,
        playbackError = playback.error?.displayName,
    )?.retry != null
    LaunchedEffect(interactionEpoch, controlsVisible, queueVisible) {
        if (controlsVisible && !queueVisible) {
            delay(5_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, queueVisible) {
        when {
            queueVisible -> Unit
            controlsVisible -> playFocus.requestFocus()
            else -> playerFocus.requestFocus()
        }
    }
    LaunchedEffect(roaming) {
        if (!roaming && controlsVisible) playFocus.requestFocus()
    }
    LaunchedEffect(queueVisible, playback.queueItems.isEmpty()) {
        if (queueVisible && playback.queueItems.isEmpty()) {
            queueVisible = false
            controlsVisible = true
            yield()
            runCatching { queueFocus.requestFocus() }
        }
    }
    BackHandler(queueVisible || controlsVisible) {
        dismissPlayerChrome()
    }
    Box(
        Modifier.fillMaxSize()
            .background(FnColors.Background)
            .focusRequester(playerFocus)
            .dismissPlayerChromeOnBack(
                chromeVisible = controlsVisible || queueVisible,
                consumeBackKeyUp = consumeBackKeyUp,
                setConsumeBackKeyUp = { consumeBackKeyUp = it },
                onDismiss = ::dismissPlayerChrome,
            )
            .revealPlayerChromeOnKey(
                chromeVisible = controlsVisible,
                shouldConsumeCenterKeyUp = { consumeCenterKeyUp },
                setConsumeCenterKeyUp = { consumeCenterKeyUp = it },
                onCenter = container.playbackController::playPause,
                onReveal = ::revealControls,
            )
            .pointerInput(controlsVisible, queueVisible) {
                if (!controlsVisible && !queueVisible) {
                    detectTapGestures { revealControls() }
                }
            }
            .focusable(),
    ) {
        if (poster) {
            PlayerPosterBackdrop(posterPanelColor, Modifier.fillMaxSize())
        } else {
            PlayerBackdrop(ambienceColor, Modifier.fillMaxSize())
        }
        PlayerMainContent(
            poster = poster,
            controlsVisible = controlsVisible,
            artworkBitmap = artworkBitmap,
            placeholderAccent = ambienceColor,
            posterPanelColor = posterPanelColor,
            title = title,
            artist = artist,
            audioFormat = audioFormat,
            isPlaying = playback.isPlaying,
            lyricIdentity = playback.nowPlayingIdentity,
            syncedLyrics = syncedLyrics,
            playbackProgress = playbackProgress,
            staticLyric = staticLyric,
            lyricsLoading = lyricsLoading,
            lyricsFailed = lyricsFailed,
            playbackError = playback.error?.displayName,
            queueError = playback.queueError,
            canRetryQueue = playback.canRetryQueue,
            onRetryQueue = container.playbackController::retryQueuePage,
            roamError = playback.roamError,
            canRetryRoam = playback.canRetryRoam,
            onRetryRoam = container.playbackController::retryRoam,
            presentationError = presentation.retryableFailure,
            canRetryPresentation = presentation.canRetry,
            onRetryPresentation = { container.nowPlayingPresenter.retryCurrentPresentation() },
            statusRetryFocus = statusRetryFocus,
            statusRetryReturnFocus = progressFocus,
            onStatusInteraction = ::revealControls,
        )
        if (controlsVisible && !queueVisible) {
            PlaybackProgressValues(playbackProgress) { positionMs, durationMs ->
                PlayerControlOverlay(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = playback.isPlaying,
                    roaming = roaming,
                    previousEnabled = previousEnabled,
                    nextEnabled = nextEnabled,
                    progressFocus = progressFocus,
                    previousFocus = previousFocus,
                    playFocus = playFocus,
                    nextFocus = nextFocus,
                    favoriteFocus = favoriteFocus,
                    modeFocus = modeFocus,
                    queueFocus = queueFocus,
                    exitRoamFocus = exitRoamFocus,
                    statusRetryFocus = statusRetryFocus,
                    statusRetryAvailable = statusRetryAvailable,
                    playMode = playback.playMode,
                    favorite = currentFavorite,
                    favoriteEnabled = playback.mediaId.isNotBlank() && !favoritePending,
                    queueCount = playback.loadedPlayableCount,
                    onInteraction = ::revealControls,
                    onSeek = container.playbackController::seekBy,
                    onPrevious = {
                        revealControls()
                        container.playbackController.previous()
                    },
                    onPlayPause = {
                        revealControls()
                        container.playbackController.playPause()
                    },
                    onNext = {
                        revealControls()
                        container.playbackController.next()
                    },
                    onToggleFavorite = {
                        val trackGuid = playback.mediaId
                        if (trackGuid.isBlank() || favoritePending) return@PlayerControlOverlay
                        actionScope.launch {
                            container.musicRepository.toggleFavorite(trackGuid, currentFavorite)
                                .onFailure {
                                    Toast.makeText(context, "收藏操作失败，请重试", Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    onCyclePlayMode = { container.playbackController.cyclePlayMode() },
                    onOpenQueue = {
                        if (playback.queueItems.isEmpty()) {
                            Toast.makeText(context, "当前播放列表为空", Toast.LENGTH_SHORT).show()
                        } else {
                            queueVisible = true
                        }
                    },
                    onExitRoam = onExitRoam,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        if (queueVisible) {
            PlaybackQueueOverlay(
                items = playback.queueItems,
                loadedCount = playback.loadedPlayableCount,
                queueError = playback.queueError,
                canRetry = playback.canRetryQueue,
                onRetry = container.playbackController::retryQueuePage,
                onSelect = container.playbackController::selectQueueItem,
                onRemove = container.playbackController::removeQueueItem,
                onInteraction = ::revealControls,
            )
        }
    }
}

@Composable
private fun PlaybackProgressValues(
    progress: State<PlaybackProgressState>,
    content: @Composable (positionMs: Long, durationMs: Long) -> Unit,
) {
    val value = progress.value
    content(value.positionMs, value.durationMs)
}

internal fun Modifier.dismissPlayerChromeOnBack(
    chromeVisible: Boolean,
    consumeBackKeyUp: Boolean,
    setConsumeBackKeyUp: (Boolean) -> Unit,
    onDismiss: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    when {
        event.key != Key.Back -> false
        event.type == KeyEventType.KeyDown && chromeVisible -> {
            setConsumeBackKeyUp(true)
            onDismiss()
            true
        }
        consumeBackKeyUp -> {
            if (event.type == KeyEventType.KeyUp) setConsumeBackKeyUp(false)
            true
        }
        else -> false
    }
}

internal fun Modifier.revealPlayerChromeOnKey(
    chromeVisible: Boolean,
    shouldConsumeCenterKeyUp: () -> Boolean,
    setConsumeCenterKeyUp: (Boolean) -> Unit,
    onCenter: () -> Unit,
    onReveal: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val isCenter = event.key == Key.Enter || event.key == Key.DirectionCenter
    when {
        isCenter && shouldConsumeCenterKeyUp() -> {
            if (event.type == KeyEventType.KeyUp) setConsumeCenterKeyUp(false)
            true
        }
        event.type != KeyEventType.KeyDown || chromeVisible -> false
        isCenter -> {
            setConsumeCenterKeyUp(true)
            onCenter()
            onReveal()
            true
        }
        event.key == Key.DirectionLeft ||
            event.key == Key.DirectionRight ||
            event.key == Key.DirectionUp ||
            event.key == Key.DirectionDown -> {
            onReveal()
            true
        }
        else -> false
    }
}

@Composable
private fun PlayerBackdrop(targetColor: Color, modifier: Modifier = Modifier) {
    var fromColor by remember { mutableStateOf(targetColor) }
    var toColor by remember { mutableStateOf(targetColor) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(targetColor) {
        fromColor = androidx.compose.ui.graphics.lerp(fromColor, toColor, progress.value)
        toColor = targetColor
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 650, easing = LinearEasing))
    }
    val animatedColor = androidx.compose.ui.graphics.lerp(fromColor, toColor, progress.value)
    Canvas(modifier) {
        val centerColor = androidx.compose.ui.graphics.lerp(animatedColor, FnColors.Background, 0.58f)
        val rightColor = androidx.compose.ui.graphics.lerp(animatedColor, FnColors.Background, 0.65f)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to animatedColor,
                0.52f to centerColor,
                1f to rightColor,
            ),
        )
        drawRect(Color.Black.copy(alpha = 0.25f))
        drawRect(Color.White.copy(alpha = 0.025f))
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                startY = size.height * 0.55f,
                endY = size.height,
            ),
        )
    }
}

@Composable
private fun PlayerPosterBackdrop(targetColor: Color, modifier: Modifier = Modifier) {
    var fromColor by remember { mutableStateOf(targetColor) }
    var toColor by remember { mutableStateOf(targetColor) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(targetColor) {
        fromColor = androidx.compose.ui.graphics.lerp(fromColor, toColor, progress.value)
        toColor = targetColor
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 650, easing = LinearEasing))
    }
    val animatedColor = androidx.compose.ui.graphics.lerp(fromColor, toColor, progress.value)
    Canvas(modifier) {
        drawRect(
            brush = Brush.horizontalGradient(
                0f to animatedColor,
                1f to androidx.compose.ui.graphics.lerp(animatedColor, FnColors.Background, 0.05f),
            ),
        )
    }
}

@Composable
private fun PlayerMainContent(
    poster: Boolean,
    controlsVisible: Boolean,
    artworkBitmap: Bitmap?,
    placeholderAccent: Color,
    posterPanelColor: Color,
    title: String,
    artist: String,
    audioFormat: String,
    isPlaying: Boolean,
    lyricIdentity: NowPlayingIdentity?,
    syncedLyrics: SyncedLyrics?,
    playbackProgress: State<PlaybackProgressState>,
    staticLyric: String?,
    lyricsLoading: Boolean,
    lyricsFailed: Boolean,
    playbackError: String?,
    queueError: String?,
    canRetryQueue: Boolean,
    onRetryQueue: () -> Unit,
    roamError: AppError?,
    canRetryRoam: Boolean,
    onRetryRoam: () -> Unit,
    presentationError: AppError?,
    canRetryPresentation: Boolean,
    onRetryPresentation: () -> Unit,
    statusRetryFocus: FocusRequester,
    statusRetryReturnFocus: FocusRequester,
    onStatusInteraction: () -> Unit,
) {
    val placeholder = title.take(1).ifBlank { "音" }
    if (poster) {
        Box(Modifier.fillMaxSize()) {
            if (artworkBitmap != null) {
                Image(
                    artworkBitmap.asImageBitmap(),
                    null,
                    Modifier.fillMaxWidth(0.58f).fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                PlayerArtworkPlaceholder(
                    placeholder,
                    placeholderAccent,
                    Modifier.fillMaxWidth(0.58f).fillMaxHeight(),
                    RectangleShape,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0.34f to Color.Transparent,
                        0.50f to posterPanelColor,
                    ),
                ),
            )
            Box(
                Modifier.fillMaxWidth(0.50f).fillMaxHeight().align(Alignment.CenterEnd)
                    .background(
                        Brush.horizontalGradient(
                            0f to posterPanelColor,
                            1f to androidx.compose.ui.graphics.lerp(
                                posterPanelColor,
                                FnColors.Background,
                                0.10f,
                            ),
                        ),
                    ),
            )
            PlayerDetails(
                title = title,
                artist = artist,
                audioFormat = audioFormat,
                isPlaying = isPlaying,
                lyricIdentity = lyricIdentity,
                syncedLyrics = syncedLyrics,
                playbackProgress = playbackProgress,
                staticLyric = staticLyric,
                lyricsLoading = lyricsLoading,
                lyricsFailed = lyricsFailed,
                playbackError = playbackError,
                queueError = queueError,
                canRetryQueue = canRetryQueue,
                onRetryQueue = onRetryQueue,
                roamError = roamError,
                canRetryRoam = canRetryRoam,
                onRetryRoam = onRetryRoam,
                presentationError = presentationError,
                canRetryPresentation = canRetryPresentation,
                onRetryPresentation = onRetryPresentation,
                statusRetryFocus = statusRetryFocus,
                statusRetryReturnFocus = statusRetryReturnFocus,
                onStatusInteraction = onStatusInteraction,
                poster = true,
                modifier = Modifier.fillMaxWidth(0.46f).fillMaxHeight().align(Alignment.CenterEnd)
                    .padding(
                        start = 10.dp,
                        end = 40.dp,
                        top = 64.dp,
                        bottom = if (controlsVisible) 132.dp else 48.dp,
                    ),
                controlsVisible = controlsVisible,
            )
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier.weight(0.49f).fillMaxHeight().padding(end = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                val discSize = minOf(maxWidth, maxHeight).coerceAtMost(430.dp)
                DiscArtwork(artworkBitmap, placeholder, placeholderAccent, isPlaying, discSize)
            }
            PlayerDetails(
                title = title,
                artist = artist,
                audioFormat = audioFormat,
                isPlaying = isPlaying,
                lyricIdentity = lyricIdentity,
                syncedLyrics = syncedLyrics,
                playbackProgress = playbackProgress,
                staticLyric = staticLyric,
                lyricsLoading = lyricsLoading,
                lyricsFailed = lyricsFailed,
                playbackError = playbackError,
                queueError = queueError,
                canRetryQueue = canRetryQueue,
                onRetryQueue = onRetryQueue,
                roamError = roamError,
                canRetryRoam = canRetryRoam,
                onRetryRoam = onRetryRoam,
                presentationError = presentationError,
                canRetryPresentation = canRetryPresentation,
                onRetryPresentation = onRetryPresentation,
                statusRetryFocus = statusRetryFocus,
                statusRetryReturnFocus = statusRetryReturnFocus,
                onStatusInteraction = onStatusInteraction,
                poster = false,
                modifier = Modifier.weight(0.51f).fillMaxHeight()
                    .padding(
                        start = 26.dp,
                        end = 44.dp,
                        top = 30.dp,
                        bottom = if (controlsVisible) 132.dp else 48.dp,
                    ),
                controlsVisible = controlsVisible,
            )
        }
    }
}

@Composable
private fun PlayerDetails(
    title: String,
    artist: String,
    audioFormat: String,
    isPlaying: Boolean,
    lyricIdentity: NowPlayingIdentity?,
    syncedLyrics: SyncedLyrics?,
    playbackProgress: State<PlaybackProgressState>,
    staticLyric: String?,
    lyricsLoading: Boolean,
    lyricsFailed: Boolean,
    playbackError: String?,
    queueError: String?,
    canRetryQueue: Boolean,
    onRetryQueue: () -> Unit,
    roamError: AppError?,
    canRetryRoam: Boolean,
    onRetryRoam: () -> Unit,
    presentationError: AppError?,
    canRetryPresentation: Boolean,
    onRetryPresentation: () -> Unit,
    statusRetryFocus: FocusRequester,
    statusRetryReturnFocus: FocusRequester,
    onStatusInteraction: () -> Unit,
    poster: Boolean,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = if (poster) 22.sp else 32.sp,
                lineHeight = if (poster) 28.sp else 38.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (audioFormat.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                AudioFormatBadge(audioFormat, poster)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            artist.ifBlank { "未知演唱者" },
            color = FnColors.Muted,
            fontSize = if (poster) 16.sp else 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(if (poster) 44.dp else 34.dp))
        SmoothLyricProgress(
            progress = playbackProgress,
            isPlaying = isPlaying,
            identity = lyricIdentity,
        ) { positionMs ->
            key(lyricIdentity?.namespace, lyricIdentity?.mediaId, syncedLyrics) {
                TvLyrics(
                    lyrics = syncedLyrics,
                    positionMs = positionMs,
                    staticLyric = staticLyric,
                    loading = lyricsLoading,
                    failed = lyricsFailed,
                    poster = poster,
                    controlsVisible = controlsVisible,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val status = playerStatus(
            roamError = roamError,
            canRetryRoam = canRetryRoam,
            queueError = queueError,
            canRetryQueue = canRetryQueue,
            presentationError = presentationError,
            canRetryPresentation = canRetryPresentation,
            playbackError = playbackError,
        )
        if (status != null) {
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status.message, color = FnColors.Coral, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val retryAction = when (status.retry) {
                    PlayerStatusRetry.Roam -> onRetryRoam
                    PlayerStatusRetry.Queue -> onRetryQueue
                    PlayerStatusRetry.Presentation -> onRetryPresentation
                    null -> null
                }
                if (retryAction != null) {
                    PlayerStatusRetryButton(
                        focusRequester = statusRetryFocus,
                        returnFocusRequester = statusRetryReturnFocus,
                        onInteraction = onStatusInteraction,
                        onRetry = retryAction,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerStatusRetryButton(
    focusRequester: FocusRequester,
    returnFocusRequester: FocusRequester,
    onInteraction: () -> Unit,
    onRetry: () -> Unit,
) {
    Button(
        onClick = {
            onInteraction()
            returnFocusRequester.requestFocus()
            onRetry()
        },
        modifier = Modifier.height(40.dp)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                down = returnFocusRequester
            }
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onInteraction() }
            .semantics { contentDescription = "重试播放状态" },
    ) { Text("重试", maxLines = 1) }
}

@Composable
private fun AudioFormatBadge(audioFormat: String, poster: Boolean) {
    val color = FnColors.Text.copy(alpha = 0.72f)
    Box(
        Modifier.border(1.dp, color, RoundedCornerShape(2.dp))
            .padding(horizontal = if (poster) 5.dp else 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            audioFormat,
            color = color,
            fontSize = if (poster) 10.sp else 12.sp,
            lineHeight = if (poster) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayerArtworkPlaceholder(text: String, accent: Color, modifier: Modifier, shape: Shape) {
    val background = androidx.compose.ui.graphics.lerp(accent, FnColors.Background, 0.58f)
    Box(modifier.clip(shape).background(background), contentAlignment = Alignment.Center) {
        Text(
            text.take(1).ifBlank { "音" },
            color = FnColors.Text.copy(alpha = 0.9f),
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun InitialArtworkPlaceholder(
    text: String,
    accent: Color,
    modifier: Modifier,
    shape: Shape,
) {
    val background = androidx.compose.ui.graphics.lerp(accent, FnColors.Background, 0.76f)
    BoxWithConstraints(
        modifier = modifier.clip(shape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        val glyphSize = when {
            maxWidth <= 60.dp -> 18.sp
            maxWidth <= 96.dp -> 25.sp
            maxWidth <= 220.dp -> 52.sp
            else -> 72.sp
        }
        Box(
            Modifier
                .fillMaxSize(0.58f)
                .background(accent.copy(alpha = 0.18f), CircleShape)
                .border(1.dp, accent.copy(alpha = 0.24f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text.trim().take(1).ifBlank { "音" }.uppercase(),
                color = accent.copy(alpha = 0.96f),
                fontSize = glyphSize,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DiscArtwork(
    artworkBitmap: Bitmap?,
    placeholder: String,
    placeholderAccent: Color,
    isPlaying: Boolean,
    size: androidx.compose.ui.unit.Dp,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            rotation.snapTo(rotation.value % 360f)
            rotation.animateTo(rotation.value + 360f, tween(durationMillis = 24_000, easing = LinearEasing))
        }
    }
    val recordSize = size * 0.84f
    val labelSize = size * 0.44f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(recordSize).graphicsLayer { rotationZ = rotation.value }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = minOf(this.size.width, this.size.height) / 2f
                drawCircle(Color(0xFF101313), radius)
                drawCircle(Color(0xFF242928), radius * 0.96f, style = Stroke(width = 3.dp.toPx()))
                for (ring in 1..14) {
                    val ringRadius = radius * (0.52f + ring * 0.029f)
                    drawCircle(Color.White.copy(alpha = if (ring % 3 == 0) 0.09f else 0.045f), ringRadius, style = Stroke(width = 1.dp.toPx()))
                }
                drawArc(
                    color = FnColors.Teal.copy(alpha = 0.42f),
                    startAngle = -18f,
                    sweepAngle = 36f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(radius * 0.18f, radius * 0.18f),
                    size = androidx.compose.ui.geometry.Size(radius * 1.64f, radius * 1.64f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            if (artworkBitmap != null) {
                Image(
                    artworkBitmap.asImageBitmap(),
                    null,
                    Modifier.size(labelSize).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                PlayerArtworkPlaceholder(placeholder, placeholderAccent, Modifier.size(labelSize), CircleShape)
            }
            Box(Modifier.size(14.dp).background(Color(0xFFCDD4D0), CircleShape))
        }
        Canvas(Modifier.fillMaxSize()) {
            val pivot = androidx.compose.ui.geometry.Offset(this.size.width * 0.68f, this.size.height * 0.12f)
            val elbow = androidx.compose.ui.geometry.Offset(this.size.width * 0.72f, this.size.height * 0.35f)
            val needle = androidx.compose.ui.geometry.Offset(this.size.width * 0.82f, this.size.height * 0.46f)
            drawCircle(Color(0xFF252B2A), 15.dp.toPx(), pivot)
            drawCircle(Color(0xFFE5E8E3), 9.dp.toPx(), pivot)
            val arm = Path().apply {
                moveTo(pivot.x, pivot.y)
                lineTo(elbow.x, elbow.y)
                lineTo(needle.x, needle.y)
            }
            drawPath(arm, Color(0xFFD9DDD8), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
            drawLine(Color(0xFF8CA39E), needle, needle + androidx.compose.ui.geometry.Offset(12.dp.toPx(), 7.dp.toPx()), 7.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun TvLyrics(
    lyrics: SyncedLyrics?,
    positionMs: Long,
    staticLyric: String?,
    loading: Boolean,
    failed: Boolean,
    poster: Boolean,
    modifier: Modifier = Modifier,
    controlsVisible: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(defaultLyricsViewportHeight(poster, controlsVisible))
            .focusProperties {
                canFocus = false
                onEnter = { cancelFocusChange() }
            }
            .focusGroup(),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            lyrics != null && lyrics.lines.isNotEmpty() -> {
                val listState = rememberLazyListState()
                val positionState = rememberUpdatedState(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                val currentPosition = remember { { positionState.value } }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides TextStyle(
                        color = FnColors.Text,
                        fontSize = if (poster) 20.sp else 23.sp,
                        lineHeight = if (poster) 24.sp else 27.sp,
                    ),
                ) {
                    KaraokeLyricsView(
                        listState = listState,
                        lyrics = lyrics,
                        currentPosition = currentPosition,
                        onLineClicked = {},
                        onLinePressed = {},
                        modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
                        normalLineTextStyle = TextStyle(
                            color = FnColors.Text,
                            fontSize = if (poster) 24.sp else 27.sp,
                            lineHeight = if (poster) 29.sp else 32.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        accompanimentLineTextStyle = TextStyle(
                            color = FnColors.Text,
                            fontSize = if (poster) 19.sp else 21.sp,
                            lineHeight = if (poster) 23.sp else 25.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        textColor = FnColors.Text,
                        blendMode = BlendMode.SrcOver,
                        useBlurEffect = false,
                        showTranslation = true,
                        showPhonetic = false,
                        offset = if (poster) 42.dp else 48.dp,
                        keepAliveZone = 0.dp,
                        breathingDotsDefaults = KaraokeBreathingDotsDefaults(
                            number = 1,
                            size = 0.dp,
                            margin = 0.dp,
                        ),
                    )
                }
            }
            !staticLyric.isNullOrBlank() -> Text(
                staticLyric,
                fontSize = if (poster) 22.sp else 24.sp,
                lineHeight = if (poster) 28.sp else 31.sp,
                maxLines = 8,
                overflow = TextOverflow.Clip,
            )
            loading -> Text(
                "歌词加载中",
                color = FnColors.Text.copy(alpha = 0.4f),
                fontSize = if (poster) 20.sp else 26.sp,
            )
            failed -> Text(
                "歌词暂时无法加载",
                color = FnColors.Text.copy(alpha = 0.4f),
                fontSize = if (poster) 20.sp else 26.sp,
            )
            else -> Text(
                "纯音乐或暂无歌词",
                color = FnColors.Text.copy(alpha = 0.4f),
                fontSize = if (poster) 20.sp else 26.sp,
            )
        }
    }
}

private fun defaultLyricsViewportHeight(poster: Boolean, controlsVisible: Boolean) = when {
    controlsVisible && poster -> 240.dp
    controlsVisible -> 264.dp
    poster -> 300.dp
    else -> 288.dp
}

private data class LyricPositionAnchor(
    val positionMs: Long,
    val observedAtMs: Long,
    val durationMs: Long,
)

@Composable
private fun SmoothLyricProgress(
    progress: State<PlaybackProgressState>,
    isPlaying: Boolean,
    identity: NowPlayingIdentity?,
    content: @Composable (positionMs: Long) -> Unit,
) {
    val snapshot = progress.value
    val anchor = remember(identity, snapshot.positionMs, snapshot.durationMs, isPlaying) {
        LyricPositionAnchor(
            positionMs = snapshot.positionMs,
            observedAtMs = SystemClock.uptimeMillis(),
            durationMs = snapshot.durationMs,
        )
    }
    val currentAnchor by rememberUpdatedState(anchor)
    var interpolatedPositionMs by remember(identity) { mutableLongStateOf(snapshot.positionMs) }

    LaunchedEffect(identity, isPlaying, snapshot.durationMs) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            withFrameNanos {
                val latest = currentAnchor
                interpolatedPositionMs = interpolatedLyricPosition(
                    anchorPositionMs = latest.positionMs,
                    anchorObservedAtMs = latest.observedAtMs,
                    frameObservedAtMs = SystemClock.uptimeMillis(),
                    durationMs = latest.durationMs,
                    isPlaying = true,
                )
            }
        }
    }

    content(if (isPlaying) interpolatedPositionMs else snapshot.positionMs)
}

@Composable
internal fun PlaybackQueueOverlay(
    items: List<PlaybackQueueItem>,
    loadedCount: Int,
    queueError: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onInteraction: () -> Unit,
) {
    val listState = rememberLazyListState()
    var focusedRowKey by remember { mutableStateOf<String?>(null) }
    var requestedFocusKey by remember { mutableStateOf<String?>(null) }
    val requesterKeys = remember(items.map(PlaybackQueueItem::mediaId)) {
        val occurrences = mutableMapOf<String, Int>()
        items.map { item ->
            val occurrence = occurrences[item.mediaId] ?: 0
            occurrences[item.mediaId] = occurrence + 1
            "${item.mediaId}:$occurrence"
        }
    }
    val relocationKey = remember(requesterKeys) {
        queueRelocationFocusTargetKey(
            requesterKeys = requesterKeys,
            currentIndex = initialQueueFocusIndex(items),
            previouslyFocusedKey = focusedRowKey,
        )
    }
    LaunchedEffect(requesterKeys, relocationKey) {
        val targetIndex = relocationKey?.let(requesterKeys::indexOf) ?: -1
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            requestedFocusKey = relocationKey
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f))) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(0.43f).align(Alignment.CenterEnd)
                .background(Color(0xF3121717)).padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text("当前播放 ($loadedCount)", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(
                    items = items,
                    key = { index, _ -> requesterKeys[index] },
                ) { index, item ->
                    val rowKey = requesterKeys[index]
                    val requester = remember(rowKey) { FocusRequester() }
                    val deleteRequester = remember(rowKey) { FocusRequester() }
                    LaunchedEffect(requestedFocusKey, rowKey) {
                        if (requestedFocusKey == rowKey) requester.requestFocus()
                    }
                    val rowShape = RoundedCornerShape(5.dp)
                    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                onInteraction()
                                onSelect(item.queueIndex)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = deleteRequester
                                }
                                .focusRequester(requester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focusedRowKey = rowKey
                                        if (requestedFocusKey == rowKey) requestedFocusKey = null
                                        onInteraction()
                                    }
                                }
                                .semantics {
                                    contentDescription = "${index + 1}. ${item.title} ${item.artist}" +
                                        if (item.isCurrent) "，正在播放" else ""
                                },
                            shape = ButtonDefaults.shape(rowShape, rowShape, rowShape, rowShape, rowShape),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = if (item.isCurrent) Color.White.copy(alpha = 0.055f) else Color.Transparent,
                                contentColor = FnColors.Text,
                                focusedContainerColor = Color.White.copy(alpha = 0.075f),
                                focusedContentColor = FnColors.Text,
                                pressedContainerColor = Color.White.copy(alpha = 0.10f),
                                pressedContentColor = FnColors.Text,
                            ),
                            border = ButtonDefaults.border(
                                border = Border(
                                    BorderStroke(
                                        1.dp,
                                        if (item.isCurrent) Color.White.copy(alpha = 0.30f) else Color.Transparent,
                                    ),
                                    shape = rowShape,
                                ),
                                focusedBorder = Border(BorderStroke(1.25.dp, Color.White.copy(alpha = 0.86f)), shape = rowShape),
                                pressedBorder = Border(BorderStroke(1.25.dp, Color.White.copy(alpha = 0.72f)), shape = rowShape),
                            ),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Row(
                                Modifier.fillMaxSize().padding(start = 12.dp, end = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                            Text(
                                "${index + 1}",
                                color = FnColors.Muted,
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.width(30.dp),
                            )
                            Column(
                                Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    item.title.ifBlank { "未知歌曲" },
                                    fontSize = 16.sp,
                                    lineHeight = 19.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    item.artist.ifBlank { "未知演唱者" },
                                    color = FnColors.Muted,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (item.isCurrent) {
                                Text(
                                    "正在播放",
                                    color = FnColors.Coral,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                            }
                        }
                        Button(
                            onClick = {
                                onInteraction()
                                focusedRowKey = rowKey
                                onRemove(item.queueIndex)
                            },
                            modifier = Modifier.size(40.dp)
                                .focusProperties {
                                    left = requester
                                    right = FocusRequester.Cancel
                                }
                                .focusRequester(deleteRequester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        focusedRowKey = rowKey
                                        onInteraction()
                                    }
                                }
                                .semantics { contentDescription = "从播放队列删除 ${item.title}" },
                            shape = ButtonDefaults.shape(rowShape, rowShape, rowShape, rowShape, rowShape),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = FnColors.Muted.copy(alpha = 0.82f),
                                focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                focusedContentColor = FnColors.Coral,
                                pressedContainerColor = Color.White.copy(alpha = 0.14f),
                                pressedContentColor = FnColors.Coral,
                            ),
                            border = ButtonDefaults.border(
                                focusedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.34f)), shape = rowShape),
                                pressedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)), shape = rowShape),
                            ),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            QueueDeleteIcon()
                        }
                    }
                }
                if (queueError != null && canRetry) {
                    item(key = "queue-retry") {
                        Button(
                            onClick = {
                                onInteraction()
                                onRetry()
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                }
                                .onFocusChanged { if (it.isFocused) onInteraction() },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("队列加载失败，重试", lineHeight = 20.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueDeleteIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(size.width * 0.24f, size.height * 0.24f),
            androidx.compose.ui.geometry.Offset(size.width * 0.76f, size.height * 0.76f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(size.width * 0.76f, size.height * 0.24f),
            androidx.compose.ui.geometry.Offset(size.width * 0.24f, size.height * 0.76f),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
internal fun PlayerControlOverlay(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    roaming: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    progressFocus: FocusRequester,
    previousFocus: FocusRequester,
    playFocus: FocusRequester,
    nextFocus: FocusRequester,
    favoriteFocus: FocusRequester,
    modeFocus: FocusRequester,
    queueFocus: FocusRequester,
    exitRoamFocus: FocusRequester,
    statusRetryFocus: FocusRequester,
    statusRetryAvailable: Boolean,
    playMode: PlayMode,
    favorite: Boolean,
    favoriteEnabled: Boolean,
    queueCount: Int,
    onInteraction: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onExitRoam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var progressFocused by remember { mutableStateOf(false) }
    val fraction = playerProgressFraction(positionMs, durationMs)
    Column(
        modifier.fillMaxWidth().height(124.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.38f to Color(0x2B090D0C),
                    1f to Color(0xA3090D0C),
                ),
            )
            .padding(start = 47.dp, top = 8.dp, end = 47.dp, bottom = 8.dp),
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(48.dp)
                .focusProperties {
                    up = if (statusRetryAvailable) statusRetryFocus else FocusRequester.Cancel
                    down = playFocus
                }
                .focusRequester(progressFocus)
                .onFocusChanged {
                    progressFocused = it.isFocused
                    if (it.isFocused) onInteraction()
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onSeek(-10_000)
                            onInteraction()
                            true
                        }
                        Key.DirectionRight -> {
                            onSeek(10_000)
                            onInteraction()
                            true
                        }
                        Key.DirectionDown -> {
                            onInteraction()
                            playFocus.requestFocus()
                            true
                        }
                        Key.DirectionUp -> {
                            onInteraction()
                            if (statusRetryAvailable) statusRetryFocus.requestFocus()
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(positionMs, durationMs) {
                    if (durationMs > 0L) {
                        detectTapGestures { offset ->
                            val targetMs = (durationMs * (offset.x / size.width).coerceIn(0f, 1f)).toLong()
                            onSeek(targetMs - positionMs)
                            onInteraction()
                        }
                    }
                }
                .focusable()
                .background(if (progressFocused) Color.White.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(3.dp))
                .graphicsLayer {
                    scaleX = if (progressFocused) 1.02f else 1f
                    scaleY = if (progressFocused) 1.04f else 1f
                }
                .semantics { contentDescription = "播放进度 ${formatDuration(positionMs)} / ${formatDuration(durationMs)}" },
        ) {
            val centerY = size.height / 2f
            val playedX = size.width * fraction
            drawLine(Color.White.copy(alpha = 0.28f), androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(size.width, centerY), 2.dp.toPx(), StrokeCap.Round)
            drawLine(FnColors.Coral, androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(playedX, centerY), 2.dp.toPx(), StrokeCap.Round)
            if (progressFocused) drawCircle(FnColors.Coral.copy(alpha = 0.24f), 8.dp.toPx(), androidx.compose.ui.geometry.Offset(playedX, centerY))
            drawCircle(Color.White, if (progressFocused) 5.dp.toPx() else 3.5.dp.toPx(), androidx.compose.ui.geometry.Offset(playedX, centerY))
        }
        Row(
            Modifier.fillMaxWidth().height(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(formatDuration(positionMs), color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp, lineHeight = 10.sp)
            Text(formatDuration(durationMs), color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Row(
                Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerSideActionButton(
                    glyph = if (favorite) PlayerSideActionGlyph.HeartFilled else PlayerSideActionGlyph.HeartOutline,
                    description = if (favorite) "取消收藏当前歌曲" else "收藏当前歌曲",
                    focusRequester = favoriteFocus,
                    upFocus = progressFocus,
                    rightFocus = when {
                        !roaming -> modeFocus
                        previousEnabled -> previousFocus
                        else -> playFocus
                    },
                    selected = favorite,
                    onFocus = onInteraction,
                    onClick = {
                        onInteraction()
                        if (favoriteEnabled) onToggleFavorite()
                    },
                )
                if (!roaming) {
                    PlayerSideActionButton(
                        glyph = playModeGlyph(playMode),
                        description = "播放模式：${playModeLabel(playMode)}",
                        focusRequester = modeFocus,
                        upFocus = progressFocus,
                        leftFocus = favoriteFocus,
                        rightFocus = if (previousEnabled) previousFocus else playFocus,
                        onFocus = onInteraction,
                        onClick = {
                            onInteraction()
                            onCyclePlayMode()
                        },
                    )
                }
            }
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerTransportButton(
                    glyph = TransportGlyph.Previous,
                    description = "上一首",
                    enabled = previousEnabled,
                    focusRequester = previousFocus,
                    upFocus = progressFocus,
                    leftFocus = if (roaming) favoriteFocus else modeFocus,
                    rightFocus = playFocus,
                    onFocus = onInteraction,
                    onClick = onPrevious,
                )
                PlayerTransportButton(
                    glyph = if (isPlaying) TransportGlyph.Pause else TransportGlyph.Play,
                    description = if (isPlaying) "暂停" else "播放",
                    focusRequester = playFocus,
                    upFocus = progressFocus,
                    leftFocus = when {
                        previousEnabled -> previousFocus
                        roaming -> favoriteFocus
                        else -> modeFocus
                    },
                    rightFocus = when {
                        nextEnabled -> nextFocus
                        roaming -> exitRoamFocus
                        else -> queueFocus
                    },
                    emphasized = true,
                    onFocus = onInteraction,
                    onClick = onPlayPause,
                )
                PlayerTransportButton(
                    glyph = TransportGlyph.Next,
                    description = "下一首",
                    enabled = nextEnabled,
                    focusRequester = nextFocus,
                    upFocus = progressFocus,
                    leftFocus = playFocus,
                    rightFocus = if (roaming) exitRoamFocus else queueFocus,
                    onFocus = onInteraction,
                    onClick = onNext,
                )
            }
            PlayerSideActionButton(
                label = "退出漫游".takeIf { roaming },
                glyph = PlayerSideActionGlyph.Queue.takeUnless { roaming },
                description = if (roaming) "退出漫游" else "播放队列，共 $queueCount 首",
                focusRequester = if (roaming) exitRoamFocus else queueFocus,
                upFocus = progressFocus,
                leftFocus = if (nextEnabled) nextFocus else playFocus,
                onFocus = onInteraction,
                onClick = {
                    onInteraction()
                    if (roaming) onExitRoam() else onOpenQueue()
                },
                emphasized = roaming,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun PlayerSideActionButton(
    description: String,
    focusRequester: FocusRequester,
    upFocus: FocusRequester,
    modifier: Modifier = Modifier,
    label: String? = null,
    glyph: PlayerSideActionGlyph? = null,
    leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
    emphasized: Boolean = false,
    selected: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val buttonWidth = if (glyph == null) 104.dp else 36.dp
    Box(
        modifier
            .size(width = if (glyph == null) 104.dp else 48.dp, height = 48.dp)
            .playerTouchTarget(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TvMaterialButton(
            onClick = onClick,
            modifier = Modifier.size(width = buttonWidth, height = 29.dp)
                .focusProperties {
                    up = upFocus
                    down = FocusRequester.Cancel
                    left = leftFocus ?: FocusRequester.Cancel
                    right = rightFocus ?: FocusRequester.Cancel
                }
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .semantics {
                    contentDescription = description
                    if (glyph == PlayerSideActionGlyph.HeartOutline || glyph == PlayerSideActionGlyph.HeartFilled) {
                        this.selected = selected
                    }
                },
            scale = ButtonDefaults.scale(focusedScale = 1.1f),
            colors = ButtonDefaults.colors(
                containerColor = if (emphasized) Color(0x66382A27) else Color.Transparent,
                contentColor = when {
                    selected -> Color(0xFFFF3B4D)
                    emphasized -> Color(0xFFF0D9D1)
                    else -> FnColors.Text
                },
                focusedContainerColor = if (selected) Color(0xFFF8F3EE) else FnColors.Coral,
                focusedContentColor = if (selected) Color(0xFFFF3B4D) else FnColors.Background,
                pressedContainerColor = if (selected) Color(0xFFEDE7E2) else FnColors.Coral,
                pressedContentColor = if (selected) Color(0xFFFF3B4D) else FnColors.Background,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (glyph != null) {
                PlayerSideActionIcon(glyph)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        label.orEmpty(),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun Modifier.playerTouchTarget(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(onClick) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                waitForUpOrCancellation(pass = PointerEventPass.Initial)?.let { up ->
                    up.consume()
                    onClick()
                }
            }
        }
    }

private enum class PlayerSideActionGlyph {
    HeartOutline,
    HeartFilled,
    RepeatAll,
    Shuffle,
    RepeatOne,
    Sequence,
    Queue,
}

private fun playModeGlyph(mode: PlayMode): PlayerSideActionGlyph = when (mode) {
    PlayMode.ListRepeat -> PlayerSideActionGlyph.RepeatAll
    PlayMode.Shuffle -> PlayerSideActionGlyph.Shuffle
    PlayMode.SingleRepeat -> PlayerSideActionGlyph.RepeatOne
    PlayMode.Sequence -> PlayerSideActionGlyph.Sequence
}

@Composable
private fun PlayerSideActionIcon(glyph: PlayerSideActionGlyph) {
    val iconColor = LocalContentColor.current
    Canvas(Modifier.size(17.dp)) {
        val stroke = 1.7.dp.toPx()
        fun line(startX: Float, startY: Float, endX: Float, endY: Float) {
            drawLine(
                color = iconColor,
                start = androidx.compose.ui.geometry.Offset(size.width * startX, size.height * startY),
                end = androidx.compose.ui.geometry.Offset(size.width * endX, size.height * endY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        fun rightArrow(tipX: Float, tipY: Float) {
            line(tipX - 0.16f, tipY - 0.13f, tipX, tipY)
            line(tipX - 0.16f, tipY + 0.13f, tipX, tipY)
        }
        when (glyph) {
            PlayerSideActionGlyph.HeartOutline,
            PlayerSideActionGlyph.HeartFilled,
            -> {
                val heart = heartPath(size)
                if (glyph == PlayerSideActionGlyph.HeartFilled) {
                    drawPath(heart, iconColor)
                } else {
                    drawPath(
                        heart,
                        iconColor,
                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            PlayerSideActionGlyph.RepeatAll,
            PlayerSideActionGlyph.RepeatOne,
            -> {
                line(0.20f, 0.32f, 0.80f, 0.32f)
                line(0.80f, 0.32f, 0.66f, 0.19f)
                line(0.80f, 0.32f, 0.66f, 0.45f)
                line(0.80f, 0.68f, 0.20f, 0.68f)
                line(0.20f, 0.68f, 0.34f, 0.55f)
                line(0.20f, 0.68f, 0.34f, 0.81f)
                if (glyph == PlayerSideActionGlyph.RepeatOne) {
                    line(0.49f, 0.44f, 0.56f, 0.39f)
                    line(0.56f, 0.39f, 0.56f, 0.61f)
                }
            }
            PlayerSideActionGlyph.Shuffle -> {
                line(0.15f, 0.28f, 0.32f, 0.28f)
                line(0.32f, 0.28f, 0.70f, 0.72f)
                line(0.70f, 0.72f, 0.84f, 0.72f)
                rightArrow(0.84f, 0.72f)
                line(0.15f, 0.72f, 0.32f, 0.72f)
                line(0.32f, 0.72f, 0.70f, 0.28f)
                line(0.70f, 0.28f, 0.84f, 0.28f)
                rightArrow(0.84f, 0.28f)
            }
            PlayerSideActionGlyph.Sequence -> {
                line(0.18f, 0.50f, 0.82f, 0.50f)
                rightArrow(0.82f, 0.50f)
                line(0.18f, 0.29f, 0.44f, 0.29f)
                line(0.18f, 0.71f, 0.44f, 0.71f)
            }
            PlayerSideActionGlyph.Queue -> {
                line(0.15f, 0.27f, 0.58f, 0.27f)
                line(0.15f, 0.50f, 0.58f, 0.50f)
                line(0.15f, 0.73f, 0.58f, 0.73f)
                val play = Path().apply {
                    moveTo(size.width * 0.69f, size.height * 0.32f)
                    lineTo(size.width * 0.89f, size.height * 0.50f)
                    lineTo(size.width * 0.69f, size.height * 0.68f)
                    close()
                }
                drawPath(play, iconColor)
            }
        }
    }
}

internal fun playModeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.ListRepeat -> "列表循环"
    PlayMode.Shuffle -> "随机播放"
    PlayMode.SingleRepeat -> "单曲循环"
    PlayMode.Sequence -> "顺序播放"
}

internal fun initialQueueFocusIndex(items: List<PlaybackQueueItem>): Int =
    items.indexOfFirst(PlaybackQueueItem::isCurrent).coerceAtLeast(0)

internal fun queueFocusTargetKey(
    requesterKeys: List<String>,
    currentIndex: Int,
    previouslyFocusedKey: String?,
): String? = previouslyFocusedKey?.takeIf(requesterKeys::contains)
    ?: requesterKeys.getOrNull(currentIndex)
    ?: requesterKeys.firstOrNull()

internal fun queueRelocationFocusTargetKey(
    requesterKeys: List<String>,
    currentIndex: Int,
    previouslyFocusedKey: String?,
): String? = if (previouslyFocusedKey != null && previouslyFocusedKey in requesterKeys) {
    null
} else {
    queueFocusTargetKey(requesterKeys, currentIndex, previouslyFocusedKey)
}

private enum class TransportGlyph { Previous, Play, Pause, Next }

@Composable
private fun PlayerTransportButton(
    glyph: TransportGlyph,
    description: String,
    focusRequester: FocusRequester,
    upFocus: FocusRequester,
    leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).playerTouchTarget(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        TvMaterialButton(
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.size(if (emphasized) 36.dp else 29.dp)
                .focusProperties {
                    up = upFocus
                    down = FocusRequester.Cancel
                    left = leftFocus ?: FocusRequester.Cancel
                    right = rightFocus ?: FocusRequester.Cancel
                }
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .semantics { contentDescription = description },
            scale = ButtonDefaults.scale(focusedScale = 1.1f),
            colors = ButtonDefaults.colors(
                containerColor = if (emphasized) FnColors.Text else Color.Transparent,
                contentColor = if (emphasized) FnColors.Background else FnColors.Text,
                focusedContainerColor = FnColors.Coral,
                focusedContentColor = FnColors.Background,
                pressedContainerColor = FnColors.Coral,
                pressedContentColor = FnColors.Background,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = FnColors.Muted.copy(alpha = 0.45f),
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            val iconColor = LocalContentColor.current
            Canvas(Modifier.size(if (emphasized) 18.dp else 14.dp)) {
                val stroke = 2.5.dp.toPx()
                when (glyph) {
                    TransportGlyph.Play -> {
                        val path = Path().apply {
                            moveTo(size.width * 0.28f, size.height * 0.16f)
                            lineTo(size.width * 0.78f, size.height * 0.5f)
                            lineTo(size.width * 0.28f, size.height * 0.84f)
                            close()
                        }
                        drawPath(path, iconColor)
                    }
                    TransportGlyph.Pause -> {
                        drawRoundRect(iconColor, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.23f, size.height * 0.16f), size = androidx.compose.ui.geometry.Size(size.width * 0.18f, size.height * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke))
                        drawRoundRect(iconColor, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.59f, size.height * 0.16f), size = androidx.compose.ui.geometry.Size(size.width * 0.18f, size.height * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke))
                    }
                    TransportGlyph.Previous, TransportGlyph.Next -> {
                        val reverse = glyph == TransportGlyph.Previous
                        val near = if (reverse) size.width * 0.68f else size.width * 0.32f
                        val far = if (reverse) size.width * 0.3f else size.width * 0.7f
                        val path = Path().apply {
                            moveTo(near, size.height * 0.18f)
                            lineTo(far, size.height * 0.5f)
                            lineTo(near, size.height * 0.82f)
                            close()
                        }
                        drawPath(path, iconColor)
                        val lineX = if (reverse) size.width * 0.24f else size.width * 0.76f
                        drawLine(iconColor, androidx.compose.ui.geometry.Offset(lineX, size.height * 0.18f), androidx.compose.ui.geometry.Offset(lineX, size.height * 0.82f), stroke, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

internal data class ArtworkPaletteSwatch(
    val rgb: Int,
    val population: Int,
)

private fun extractArtworkColors(bitmap: Bitmap): ExtractedArtworkColors = runCatching {
    val globalPalette = Palette.from(bitmap)
        .maximumColorCount(24)
        .generate()
    val edgeLeft = (bitmap.width * 0.62f).toInt().coerceIn(0, bitmap.width - 1)
    val edgePalette = Palette.from(bitmap)
        .maximumColorCount(16)
        .setRegion(edgeLeft, 0, bitmap.width, bitmap.height)
        .generate()
    val globalSwatches = globalPalette.swatches.map { ArtworkPaletteSwatch(it.rgb, it.population) }
    val edgeSwatches = edgePalette.swatches.map { ArtworkPaletteSwatch(it.rgb, it.population) }
    ExtractedArtworkColors(
        ambience = artworkAmbienceColor(globalSwatches),
        posterSurface = artworkPosterSurfaceColor(globalSwatches, edgeSwatches),
    )
}.getOrDefault(fallbackArtworkColors())

private fun fallbackArtworkColors(): ExtractedArtworkColors {
    val ambience = fallbackAmbienceColor()
    return ExtractedArtworkColors(ambience, posterSurfaceColor(ambience))
}

internal fun artworkAmbienceColor(swatches: List<ArtworkPaletteSwatch>): Color {
    val candidates = swatches.filter { it.population > 0 }
    if (candidates.isEmpty()) return fallbackAmbienceColor()
    var weightedRed = 0f
    var weightedGreen = 0f
    var weightedBlue = 0f
    var totalWeight = 0f
    candidates.forEach { swatch ->
        val red = (swatch.rgb ushr 16 and 0xFF) / 255f
        val green = (swatch.rgb ushr 8 and 0xFF) / 255f
        val blue = (swatch.rgb and 0xFF) / 255f
        val peak = maxOf(red, green, blue)
        val floor = minOf(red, green, blue)
        val saturation = if (peak == 0f) 0f else (peak - floor) / peak
        val lightness = (peak + floor) / 2f
        val usefulLightness = (1f - kotlin.math.abs(lightness - 0.52f) / 0.52f).coerceIn(0f, 1f)
        val extremeWeight = when {
            peak < 0.08f -> 0.05f
            floor > 0.94f -> 0.12f
            else -> 1f
        }
        val weight = swatch.population *
            (0.12f + saturation * 0.88f) *
            (0.35f + usefulLightness * 0.65f) *
            extremeWeight
        weightedRed += red * weight
        weightedGreen += green * weight
        weightedBlue += blue * weight
        totalWeight += weight
    }
    if (totalWeight <= 0f) return fallbackAmbienceColor()
    return normalizedAmbienceColor(
        red = weightedRed / totalWeight,
        green = weightedGreen / totalWeight,
        blue = weightedBlue / totalWeight,
    )
}

internal fun fallbackAmbienceColor(): Color = Color(0xFF29312F)

private const val POSTER_EDGE_MAX_INFLUENCE = 0.35f
private const val POSTER_EDGE_DISTANCE_LIMIT = 0.18f
private const val POSTER_MAX_CHROMA = 0.09f
private const val POSTER_MIN_LIGHTNESS = 0.32f
private const val POSTER_MAX_LIGHTNESS = 0.50f
private const val POSTER_TEXT_CONTRAST = 4.8f

private data class OklabColor(
    val lightness: Float,
    val a: Float,
    val b: Float,
)

internal fun artworkPosterSurfaceColor(
    globalSwatches: List<ArtworkPaletteSwatch>,
    edgeSwatches: List<ArtworkPaletteSwatch>,
): Color {
    val global = weightedOklabColor(globalSwatches)
        ?: return posterSurfaceColor(fallbackAmbienceColor())
    val edge = weightedOklabColor(edgeSwatches) ?: global
    val edgeInfluence = posterEdgeInfluence(global, edge)
    return toneMapPosterColor(lerpOklab(global, edge, edgeInfluence))
}

private fun weightedOklabColor(swatches: List<ArtworkPaletteSwatch>): OklabColor? {
    var weightedLightness = 0f
    var weightedA = 0f
    var weightedB = 0f
    var totalWeight = 0f
    swatches.filter { it.population > 0 }.forEach { swatch ->
        val color = rgbIntToOklab(swatch.rgb)
        val extremeWeight = when {
            color.lightness < 0.04f -> 0.35f
            color.lightness > 0.97f -> 0.45f
            else -> 1f
        }
        val weight = swatch.population * extremeWeight
        weightedLightness += color.lightness * weight
        weightedA += color.a * weight
        weightedB += color.b * weight
        totalWeight += weight
    }
    if (totalWeight <= 0f) return null
    return OklabColor(
        lightness = weightedLightness / totalWeight,
        a = weightedA / totalWeight,
        b = weightedB / totalWeight,
    )
}

private fun posterEdgeInfluence(global: OklabColor, edge: OklabColor): Float {
    val lightnessDistance = (global.lightness - edge.lightness) * 0.35f
    val aDistance = global.a - edge.a
    val bDistance = global.b - edge.b
    val distance = sqrt(
        lightnessDistance * lightnessDistance +
            aDistance * aDistance +
            bDistance * bDistance,
    )
    val agreement = (1f - distance / POSTER_EDGE_DISTANCE_LIMIT).coerceIn(0f, 1f)
    return POSTER_EDGE_MAX_INFLUENCE * agreement * agreement
}

private fun lerpOklab(start: OklabColor, stop: OklabColor, fraction: Float): OklabColor {
    val safeFraction = fraction.coerceIn(0f, 1f)
    return OklabColor(
        lightness = start.lightness + (stop.lightness - start.lightness) * safeFraction,
        a = start.a + (stop.a - start.a) * safeFraction,
        b = start.b + (stop.b - start.b) * safeFraction,
    )
}

private fun toneMapPosterColor(source: OklabColor): Color {
    val chroma = sqrt(source.a * source.a + source.b * source.b)
    val chromaScale = if (chroma > POSTER_MAX_CHROMA) POSTER_MAX_CHROMA / chroma else 1f
    var mapped = OklabColor(
        lightness = source.lightness.coerceIn(POSTER_MIN_LIGHTNESS, POSTER_MAX_LIGHTNESS),
        a = source.a * chromaScale,
        b = source.b * chromaScale,
    )
    var surface = oklabToColor(mapped)
    while (
        colorContrastRatio(FnColors.Text, surface) < POSTER_TEXT_CONTRAST &&
        mapped.lightness > POSTER_MIN_LIGHTNESS
    ) {
        mapped = mapped.copy(
            lightness = (mapped.lightness - 0.01f).coerceAtLeast(POSTER_MIN_LIGHTNESS),
        )
        surface = oklabToColor(mapped)
    }
    return surface
}

internal fun perceptualChroma(color: Color): Float {
    val oklab = colorToOklab(color)
    return sqrt(oklab.a * oklab.a + oklab.b * oklab.b)
}

internal fun perceptualColorDistance(first: Color, second: Color): Float {
    val firstOklab = colorToOklab(first)
    val secondOklab = colorToOklab(second)
    val lightnessDistance = firstOklab.lightness - secondOklab.lightness
    val aDistance = firstOklab.a - secondOklab.a
    val bDistance = firstOklab.b - secondOklab.b
    return sqrt(
        lightnessDistance * lightnessDistance +
            aDistance * aDistance +
            bDistance * bDistance,
    )
}

private fun normalizedAmbienceColor(red: Float, green: Float, blue: Float): Color {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val hue = rgbHue(red, green, blue)
    val sourceSaturation = if (max == 0f) 0f else delta / max
    if (sourceSaturation < 0.12f) {
        val average = (red + green + blue) / 3f
        val value = (0.27f + average * 0.06f).coerceIn(0.27f, 0.33f)
        return Color(
            red = (value + (red - average) * 0.08f).coerceIn(0f, 1f),
            green = (value + (green - average) * 0.08f).coerceIn(0f, 1f),
            blue = (value + (blue - average) * 0.08f).coerceIn(0f, 1f),
        )
    }
    val saturation = (sourceSaturation * 0.52f).coerceIn(0.1f, 0.32f)
    return hsvColor(hue, saturation, value = 0.34f)
}

private fun rgbHue(red: Float, green: Float, blue: Float): Float {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    return when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta) % 6f)
        max == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
}

private fun colorContrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun rgbIntToOklab(rgb: Int): OklabColor = colorToOklab(
    Color(
        red = (rgb ushr 16 and 0xFF) / 255f,
        green = (rgb ushr 8 and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
    ),
)

private fun colorToOklab(color: Color): OklabColor {
    val red = srgbToLinear(color.red)
    val green = srgbToLinear(color.green)
    val blue = srgbToLinear(color.blue)
    val l = 0.41222146f * red + 0.53633255f * green + 0.051445995f * blue
    val m = 0.2119035f * red + 0.6806995f * green + 0.10739696f * blue
    val s = 0.08830246f * red + 0.28171885f * green + 0.6299787f * blue
    val lRoot = Math.cbrt(l.toDouble()).toFloat()
    val mRoot = Math.cbrt(m.toDouble()).toFloat()
    val sRoot = Math.cbrt(s.toDouble()).toFloat()
    return OklabColor(
        lightness = 0.21045426f * lRoot + 0.7936178f * mRoot - 0.004072047f * sRoot,
        a = 1.9779985f * lRoot - 2.4285922f * mRoot + 0.4505937f * sRoot,
        b = 0.025904037f * lRoot + 0.78277177f * mRoot - 0.80867577f * sRoot,
    )
}

private fun oklabToColor(color: OklabColor): Color {
    val lRoot = color.lightness + 0.39633778f * color.a + 0.21580376f * color.b
    val mRoot = color.lightness - 0.105561346f * color.a - 0.06385417f * color.b
    val sRoot = color.lightness - 0.08948418f * color.a - 1.2914855f * color.b
    val l = lRoot * lRoot * lRoot
    val m = mRoot * mRoot * mRoot
    val s = sRoot * sRoot * sRoot
    return Color(
        red = linearToSrgb(4.0767417f * l - 3.3077116f * m + 0.23096994f * s),
        green = linearToSrgb(-1.268438f * l + 2.6097574f * m - 0.34131938f * s),
        blue = linearToSrgb(-0.0041960863f * l - 0.7034186f * m + 1.7076147f * s),
    )
}

private fun srgbToLinear(component: Float): Float {
    val safeComponent = component.coerceIn(0f, 1f)
    return if (safeComponent <= 0.04045f) {
        safeComponent / 12.92f
    } else {
        ((safeComponent + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
}

private fun linearToSrgb(component: Float): Float {
    val srgb = if (component <= 0.0031308f) {
        component * 12.92f
    } else {
        1.055f * component.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }
    return srgb.coerceIn(0f, 1f)
}

private fun hsvColor(hue: Float, saturation: Float, value: Float): Color {
    val chroma = value * saturation
    val segment = hue / 60f
    val x = chroma * (1f - kotlin.math.abs(segment % 2f - 1f))
    val (red, green, blue) = when (segment.toInt().coerceIn(0, 5)) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val match = value - chroma
    return Color(red + match, green + match, blue + match)
}

internal fun posterSurfaceColor(color: Color): Color {
    return toneMapPosterColor(colorToOklab(color))
}

internal fun interpolatedLyricPosition(
    anchorPositionMs: Long,
    anchorObservedAtMs: Long,
    frameObservedAtMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
): Long {
    val basePositionMs = anchorPositionMs.coerceAtLeast(0L)
    val elapsedMs = if (isPlaying) {
        (frameObservedAtMs - anchorObservedAtMs).coerceAtLeast(0L)
    } else {
        0L
    }
    val positionMs = basePositionMs + elapsedMs
    return if (durationMs > 0L) positionMs.coerceAtMost(durationMs) else positionMs
}

internal fun playerProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
