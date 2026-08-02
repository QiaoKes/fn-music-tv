package com.fnmusic.tv.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.platform.LocalDensity
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
import com.fnmusic.tv.core.model.lyric.LyricTimeline
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

private val LocalAuthenticatedDependencies = staticCompositionLocalOf<AuthenticatedAppDependencies> {
    error("Missing authenticated app dependencies")
}

internal val LocalLibraryRetainedState = staticCompositionLocalOf<LibraryRetainedStateStore> {
    error("Missing library retained state")
}

@Composable
internal fun AuthenticatedApp(
    container: AuthenticatedAppDependencies,
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
    onExitApplication: () -> Unit,
) {
    var stack by remember(session.user.guid) { mutableStateOf(listOf<LibraryRoute>(LibraryRoute.Home)) }
    var lastHomeBackAt by remember(session.user.guid) { mutableStateOf(0L) }
    val route = stack.last()
    val context = LocalContext.current
    val stateHolder = rememberSaveableStateHolder()
    val retainedScope = rememberCoroutineScope()
    val retainedState = remember(session.user.guid, retainedScope) {
        LibraryRetainedStateStore(retainedScope)
    }
    val routeStateLifecycle = remember(session.user.guid) { LibraryRouteStateLifecycle() }
    val playerVisualContinuity = remember(session.user.guid) { PlayerVisualContinuity() }
    val open: (LibraryRoute) -> Unit = { stack = stack + it }
    val root: (LibraryRoute) -> Unit = { stack = listOf(it) }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    LaunchedEffect(route.storageKey()) { lastHomeBackAt = 0L }
    LaunchedEffect(stack) {
        routeStateLifecycle.update(stack).forEach { removedRoute ->
            stateHolder.removeState(removedRoute.storageKey())
            retainedState.remove(removedRoute.retainedStateKeys())
        }
    }
    BackHandler {
        when {
            stack.size > 1 -> stack = stack.dropLast(1)
            route == LibraryRoute.My -> root(LibraryRoute.Home)
            route == LibraryRoute.Home -> {
                val now = SystemClock.elapsedRealtime()
                if (isHomeBackConfirmed(lastHomeBackAt, now)) {
                    lastHomeBackAt = 0L
                    onExitApplication()
                } else {
                    lastHomeBackAt = now
                    Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(playback.error) {
        if (playback.error?.requiresSessionVerification == true) {
            container.authenticatedActions.verifyCurrentSession()
        }
    }

    stateHolder.SaveableStateProvider(route.storageKey()) {
        CompositionLocalProvider(
            LocalAuthenticatedDependencies provides container,
            LocalLibraryRetainedState provides retainedState,
        ) {
            when (route) {
        LibraryRoute.Home -> BrowseHome(
            container,
            playback,
            onMy = { root(LibraryRoute.My) },
            onPlaylist = { open(LibraryRoute.PlaylistDetail(it)) },
            onAll = { open(LibraryRoute.AllPlaylists) },
            onPlayer = { open(LibraryRoute.Player(null)) },
        )
        LibraryRoute.My -> BrowseMy(
            container,
            session,
            playback,
            onHome = { root(LibraryRoute.Home) },
            onArtists = { open(LibraryRoute.Artists) },
            onAlbums = { open(LibraryRoute.Albums) },
            onAllTracks = { open(LibraryRoute.AllTracks) },
            onArtist = { open(LibraryRoute.ArtistDetail(it)) },
            onAlbum = { open(LibraryRoute.AlbumDetail(it)) },
            onSettings = { open(LibraryRoute.Settings) },
            onPlayer = { open(LibraryRoute.Player(null)) },
        )
        LibraryRoute.AllPlaylists -> AllPlaylists(container, onOpen = { open(LibraryRoute.PlaylistDetail(it)) })
        is LibraryRoute.PlaylistDetail -> TrackCollection(
            container = container,
            stateKey = "playlist:${route.playlist.guid.value}:tracks",
            title = route.playlist.name,
            coverId = route.playlist.coverId,
            loader = { container.musicRepository.playlistTracks(route.playlist.guid.value, it) },
            queueSource = { sort -> QueueSource.Playlist(route.playlist.guid.value, sort) },
            onPlayer = { open(LibraryRoute.Player(it)) },
        )
        LibraryRoute.Artists -> ArtistGrid(container, onOpen = { open(LibraryRoute.ArtistDetail(it)) })
        LibraryRoute.Albums -> AlbumGrid(container, onOpen = { open(LibraryRoute.AlbumDetail(it)) })
        LibraryRoute.AllTracks -> TrackCollection(
            container = container,
            stateKey = "all-tracks",
            title = "全部歌曲",
            coverId = null,
            loader = container.musicRepository::allTracks,
            queueSource = QueueSource::LibraryAllTracks,
            onPlayer = { open(LibraryRoute.Player(it)) },
            detailHeader = TrackDetailHeader(
                kind = "音乐库",
                onBack = back,
            ),
            primaryAction = TrackCollectionPrimaryAction.StartRoam {
                open(LibraryRoute.Player(null))
            },
        )
        is LibraryRoute.ArtistDetail -> ArtistDetail(
            container = container,
            artist = route.artist,
            onBack = back,
            onAlbum = { open(LibraryRoute.AlbumDetail(it)) },
            onPlayer = { open(LibraryRoute.Player(it)) },
        )
        is LibraryRoute.AlbumDetail -> TrackCollection(
            container = container,
            stateKey = "album:${route.album.guid.value}:tracks",
            title = route.album.name,
            subtitle = route.album.artistName.orEmpty(),
            coverId = route.album.coverId,
            loader = { container.musicRepository.albumTracks(route.album.guid.value, it) },
            queueSource = { sort -> QueueSource.Album(route.album.guid.value, sort) },
            onPlayer = { open(LibraryRoute.Player(it)) },
            detailHeader = TrackDetailHeader(
                kind = "专辑",
                extraMetadata = route.album.releaseDate,
                declaredTrackCount = route.album.trackCount,
                onBack = back,
            ),
        )
        is LibraryRoute.Player -> ImmersivePlayer(
            container = container,
            playback = playback,
            visualContinuity = playerVisualContinuity,
            onExitRoam = {
                retainedScope.launch {
                    stack = if (container.playbackController.exitRoamDurably()) {
                        stack.dropLast(1) + LibraryRoute.Player(null)
                    } else {
                        listOf(LibraryRoute.Home)
                    }
                }
            },
        )
                LibraryRoute.Settings -> SettingsScreen(container)
            }
        }
    }
}

@Composable
private fun LibraryTopBar(
    playback: PlaybackUiState,
    selectedHome: Boolean,
    onHome: () -> Unit,
    onMy: () -> Unit,
    onPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playback.hasMedia) {
            NowPlayingPill(playback, onPlayer, modifier)
        } else {
            Text("回声台", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        val tabColors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.Coral,
            focusedContentColor = FnColors.Background,
            pressedContainerColor = FnColors.Coral,
            pressedContentColor = FnColors.Background,
            disabledContainerColor = Color(0xFF382A27),
            disabledContentColor = Color(0xFFF0D9D1),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = onHome,
                enabled = !selectedHome,
                colors = tabColors,
                scale = ButtonDefaults.scale(focusedScale = 1.07f),
            ) { Text("首页", fontSize = 21.sp) }
            Button(
                onClick = onMy,
                enabled = selectedHome,
                colors = tabColors,
                scale = ButtonDefaults.scale(focusedScale = 1.07f),
            ) { Text("我的", fontSize = 21.sp) }
        }
    }
}

@Composable
internal fun NowPlayingPill(
    playback: PlaybackUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val shape = CircleShape
    val coverId = playback.coverId
    val fontScale = LocalDensity.current.fontScale
    val pillHeight = (42f + (fontScale - 1f).coerceAtLeast(0f) * 28f).dp
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = 186.dp, height = pillHeight)
            .semantics { contentDescription = "当前播放：${playback.title}" },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF232827),
            contentColor = FnColors.Text,
            focusedContainerColor = Color(0xFF343A38),
            focusedContentColor = FnColors.Text,
            pressedContainerColor = Color(0xFF3B413F),
            pressedContentColor = FnColors.Text,
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(0.5.dp, Color(0xFF454C49)), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, FnColors.Coral), shape = shape),
            pressedBorder = Border(BorderStroke(1.5.dp, FnColors.Coral), shape = shape),
        ),
        contentPadding = PaddingValues(start = 5.dp, top = 4.5.dp, end = 9.dp, bottom = 4.5.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (coverId != null) {
                RemoteArtwork(
                    container = LocalAuthenticatedDependencies.current,
                    coverId = coverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(27.dp),
                    shape = CircleShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = { NowPlayingArtworkFallback(playback.title) },
                )
            } else {
                NowPlayingArtworkFallback(playback.title)
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(3.5.dp).background(if (playback.isPlaying) FnColors.Coral else FnColors.Muted, CircleShape))
                    Spacer(Modifier.width(3.5.dp))
                    Text(
                        if (playback.isPlaying) "正在播放" else "已暂停",
                        color = Color(0xFFB7BBB7),
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(1.5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        playback.title.ifBlank { "正在播放" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = onTitleTextLayout,
                        modifier = Modifier.weight(1f),
                    )
                    if (playback.artist.isNotBlank()) {
                        Spacer(Modifier.width(4.5.dp))
                        Text(
                            playback.artist,
                            color = Color(0xFFA7ABA7),
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.62f, fill = false),
                        )
                    }
                }
            }
            Spacer(Modifier.width(5.dp))
            Text("›", color = Color(0xFFC6C9C5), fontSize = 17.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun NowPlayingArtworkFallback(title: String) {
    Box(
        Modifier.size(27.dp).background(Color(0xFF31413D), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).ifBlank { "音" }.uppercase(),
            color = FnColors.Teal,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BrowseHome(
    container: AuthenticatedAppDependencies,
    playback: PlaybackUiState,
    onMy: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onAll: () -> Unit,
    onPlayer: () -> Unit,
) {
    val retainedStore = LocalLibraryRetainedState.current
    val playlistState = retainedStore.list<Playlist>("playlists")
    val playlistSnapshot = playlistState.snapshot
    val playlists = playlistSnapshot.entries
    val playlistsLoaded = playlistSnapshot.initialLoadCompleted
    var actionError by remember { mutableStateOf<AppError?>(null) }
    var roamActionRunning by remember { mutableStateOf(false) }
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        retainedStore.loadListOnce(playlistState, container.musicRepository::playlists)
    }
    LaunchedEffect(playlistsLoaded, playback.hasMedia, focusedKey) {
        if (initialFocusRequested) return@LaunchedEffect
        val restoringNowPlaying = playback.hasMedia && focusedKey == "now-playing"
        if (!playlistsLoaded && !restoringNowPlaying) return@LaunchedEffect
        val availableKeys = buildList {
            if (playlistsLoaded) {
                add("roam")
                addAll(playlists.take(12).map { "playlist:${it.guid.value}" })
                add("all-playlists")
            }
            if (playback.hasMedia) add("now-playing")
        }
        focusedKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.firstOrNull()
        yield()
        if (focusedKey != null) {
            runCatching { contentFocus.requestFocus() }
            initialFocusRequested = true
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 38.dp)) {
        LibraryTopBar(
            playback,
            true,
            {},
            onMy,
            onPlayer = {
                focusedKey = "now-playing"
                onPlayer()
            },
            modifier = Modifier
                .then(if (focusedKey == "now-playing") Modifier.focusRequester(contentFocus) else Modifier)
                .onFocusChanged { if (it.isFocused) focusedKey = "now-playing" },
        )
        Spacer(Modifier.height(38.dp))
        Text("听点什么", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                PlaylistTile(
                    "随机漫游",
                    "从曲库里遇见下一首",
                    null,
                    FnColors.Teal,
                    showArtworkInitial = false,
                    modifier = Modifier
                        .then(if (focusedKey == "roam") Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = "roam" },
                ) {
                    if (playback.queueKind == QueueKind.Roam) {
                        onPlayer()
                        return@PlaylistTile
                    }
                    if (roamActionRunning || playback.roamBusy) return@PlaylistTile
                    roamActionRunning = true
                    scope.launch {
                        try {
                            if (container.playbackController.startRoam()) {
                                actionError = null
                                onPlayer()
                            } else {
                                actionError = container.playbackController.state.value.roamError ?: AppError.Unknown()
                            }
                        } finally {
                            roamActionRunning = false
                        }
                    }
                }
            }
            items(playlists.take(12), key = { it.guid.value }) { playlist ->
                val key = "playlist:${playlist.guid.value}"
                PlaylistTile(
                    playlist.name,
                    "歌单",
                    playlist.coverId,
                    FnColors.Coral,
                    modifier = Modifier
                        .then(if (focusedKey == key) Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = key },
                ) { onPlaylist(playlist) }
            }
            item {
                PlaylistTile(
                    "全部歌单",
                    "浏览完整列表",
                    null,
                    FnColors.Muted,
                    showArtworkInitial = false,
                    modifier = Modifier
                        .then(if (focusedKey == "all-playlists") Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = "all-playlists" },
                    onClick = onAll,
                )
            }
        }
        (actionError ?: playlistSnapshot.error)?.let { InlineError(it) }
    }
}

internal fun isHomeBackConfirmed(
    previousBackAt: Long,
    currentBackAt: Long,
    windowMs: Long = 2_000L,
): Boolean = previousBackAt > 0L && currentBackAt >= previousBackAt &&
    currentBackAt - previousBackAt <= windowMs

@Composable
private fun BrowseMy(
    container: AuthenticatedAppDependencies,
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
    onHome: () -> Unit,
    onArtists: () -> Unit,
    onAlbums: () -> Unit,
    onAllTracks: () -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onSettings: () -> Unit,
    onPlayer: () -> Unit,
) {
    val retainedStore = LocalLibraryRetainedState.current
    val artistState = retainedStore.paged<Artist>("grid:artists")
    val albumState = retainedStore.paged<Album>("grid:albums")
    val artists = artistState.snapshot.entries
    val albums = albumState.snapshot.entries
    val artistsLoaded = artistState.snapshot.initialLoadCompleted
    val albumsLoaded = albumState.snapshot.initialLoadCompleted
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = retainedStore.scope
    LaunchedEffect(Unit) {
        retainedStore.loadFirstPageOnce(artistState, container.musicRepository::artists) { it.guid.value }
        retainedStore.loadFirstPageOnce(albumState, container.musicRepository::albums) { it.guid.value }
    }
    LaunchedEffect(artistsLoaded, albumsLoaded, playback.hasMedia, focusedKey) {
        if (initialFocusRequested) return@LaunchedEffect
        val allContentLoaded = artistsLoaded && albumsLoaded
        val restoringChrome = focusedKey == "settings" || playback.hasMedia && focusedKey == "now-playing"
        if (!allContentLoaded && !restoringChrome) return@LaunchedEffect
        val availableKeys = buildList {
            if (allContentLoaded) {
                addAll(artists.take(8).map { "artist:${it.guid.value}" })
                add("all-artists")
                addAll(albums.take(8).map { "album:${it.guid.value}" })
                add("all-albums")
                add("all-tracks")
            }
            if (playback.hasMedia) add("now-playing")
            add("settings")
        }
        focusedKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.firstOrNull()
        yield()
        if (focusedKey != null) {
            runCatching { contentFocus.requestFocus() }
            initialFocusRequested = true
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 38.dp)) {
        LibraryTopBar(
            playback,
            false,
            onHome,
            {},
            onPlayer = {
                focusedKey = "now-playing"
                onPlayer()
            },
            modifier = Modifier
                .then(if (focusedKey == "now-playing") Modifier.focusRequester(contentFocus) else Modifier)
                .onFocusChanged { if (it.isFocused) focusedKey = "now-playing" },
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("我的音乐", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("${session.user.username} · ${session.server.name}", color = FnColors.Muted, fontSize = 19.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        focusedKey = "settings"
                        onSettings()
                    },
                    modifier = Modifier
                        .then(if (focusedKey == "settings") Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = "settings" },
                ) { Text("设置") }
                Button(onClick = {
                    scope.launch {
                        container.authenticatedActions.switchAccount()
                    }
                }) { Text("切换账号") }
            }
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                MediaBand(
                    "歌手",
                    artists.take(8).map {
                        BandEntry(it.name, "${it.trackCount ?: 0} 首歌曲", it.coverId, BandKind.Artist, "artist:${it.guid.value}") { onArtist(it) }
                    },
                    BandEntry("全部歌手", "浏览完整列表", null, BandKind.Artist, "all-artists", onArtists),
                    focusedKey,
                    contentFocus,
                    onFocused = { focusedKey = it },
                )
            }
            item {
                MediaBand(
                    "专辑",
                    albums.take(8).map {
                        BandEntry(it.name, it.artistName.orEmpty(), it.coverId, BandKind.Album, "album:${it.guid.value}") { onAlbum(it) }
                    },
                    BandEntry("全部专辑", "浏览完整列表", null, BandKind.Album, "all-albums", onAlbums),
                    focusedKey,
                    contentFocus,
                    onFocused = { focusedKey = it },
                )
            }
            item {
                MediaBand(
                    "音乐库",
                    emptyList(),
                    BandEntry("全部歌曲", "完整曲库", null, BandKind.Library, "all-tracks", onAllTracks),
                    focusedKey,
                    contentFocus,
                    onFocused = { focusedKey = it },
                )
            }
        }
    }
}

private enum class BandKind { Artist, Album, Library }

private data class BandEntry(
    val title: String,
    val subtitle: String,
    val coverId: String?,
    val kind: BandKind,
    val focusKey: String,
    val action: (() -> Unit)?,
)

@Composable
private fun MediaBand(
    title: String,
    entries: List<BandEntry>,
    terminalEntry: BandEntry,
    focusedKey: String?,
    focusRequester: FocusRequester,
    onFocused: (String) -> Unit,
) {
    Column {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries) { entry ->
                BandLockup(
                    entry,
                    Modifier.then(if (focusedKey == entry.focusKey) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocused(entry.focusKey) },
                )
            }
            item {
                BandLockup(
                    terminalEntry,
                    Modifier.then(if (focusedKey == terminalEntry.focusKey) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocused(terminalEntry.focusKey) },
                )
            }
        }
    }
}

@Composable
private fun BandLockup(entry: BandEntry, modifier: Modifier = Modifier) {
    when (entry.kind) {
        BandKind.Artist -> ArtistLockup(
            entry.title,
            entry.subtitle,
            entry.coverId,
            modifier = modifier,
            enabled = entry.action != null,
        ) { entry.action?.invoke() }
        BandKind.Album -> AlbumLockup(
            entry.title,
            entry.subtitle,
            entry.coverId,
            modifier = modifier,
            enabled = entry.action != null,
        ) { entry.action?.invoke() }
        BandKind.Library -> LibraryLockup(
            entry.title,
            entry.subtitle,
            modifier = modifier,
            enabled = entry.action != null,
        ) { entry.action?.invoke() }
    }
}

@Composable
private fun AllPlaylists(container: AuthenticatedAppDependencies, onOpen: (Playlist) -> Unit) {
    val retainedStore = LocalLibraryRetainedState.current
    val playlistState = retainedStore.list<Playlist>("playlists")
    val playlists = playlistState.snapshot.entries
    LaunchedEffect(Unit) {
        retainedStore.loadListOnce(playlistState, container.musicRepository::playlists)
    }
    GridPage("全部歌单", playlists, { it.guid.value }) { playlist, modifier ->
        PlaylistTile(playlist.name, "歌单", playlist.coverId, FnColors.Coral, modifier = modifier) { onOpen(playlist) }
    }
}

@Composable
private fun ArtistGrid(container: AuthenticatedAppDependencies, onOpen: (Artist) -> Unit) {
    PagedGrid("artists", "全部歌手", loader = container.musicRepository::artists, key = { it.guid.value }) { artist, modifier ->
        ArtistLockup(artist.name, "${artist.trackCount ?: 0} 首歌曲", artist.coverId, modifier = modifier) { onOpen(artist) }
    }
}

@Composable
private fun AlbumGrid(container: AuthenticatedAppDependencies, onOpen: (Album) -> Unit) {
    PagedGrid("albums", "全部专辑", loader = container.musicRepository::albums, key = { it.guid.value }) { album, modifier ->
        AlbumLockup(album.name, album.artistName.orEmpty(), album.coverId, modifier = modifier) { onOpen(album) }
    }
}

@Composable
private fun <T> PagedGrid(
    stateKey: String,
    title: String,
    loader: suspend (Int) -> Page<T>,
    key: (T) -> String,
    item: @Composable (T, Modifier) -> Unit,
) {
    val retainedStore = LocalLibraryRetainedState.current
    val retained = retainedStore.paged<T>("grid:$stateKey")
    val snapshot = retained.snapshot
    val entries = snapshot.entries
    val page = snapshot.page
    val hasNext = snapshot.hasNext
    val loading = retained.loading
    var focusedKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(stateKey) { mutableStateOf(false) }
    val contentFocus = remember(stateKey) { FocusRequester() }
    val gridState = rememberLazyGridState()
    fun load(target: Int) {
        if (retained.loading) return
        retained.loading = true
        retainedStore.scope.launch {
            runCatching { loader(target) }
                .onSuccess { retained.snapshot = retainLoadedPage(retained.snapshot, it, key) }
                .onFailure {
                    retained.snapshot = retained.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = retained.snapshot.initialLoadCompleted || target == 1,
                    )
                }
            retained.loading = false
        }
    }
    LaunchedEffect(stateKey) {
        if (shouldLoadInitialPage(retained.snapshot)) {
            load(1)
        }
    }
    LaunchedEffect(snapshot.initialLoadCompleted, entries, focusedKey) {
        if (!snapshot.initialLoadCompleted || entries.isEmpty() || initialFocusRequested) return@LaunchedEffect
        val keys = entries.map(key)
        focusedKey = focusedKey?.takeIf(keys::contains) ?: keys.first()
        yield()
        runCatching { contentFocus.requestFocus() }
        initialFocusRequested = true
    }
    Column(Modifier.fillMaxSize().padding(64.dp, 44.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        snapshot.error?.let { InlineError(it) }
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries, key = key) { entry ->
                val entryKey = key(entry)
                item(
                    entry,
                    Modifier.then(if (focusedKey == entryKey) Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = entryKey },
                )
            }
            if (entries.isEmpty() && snapshot.error != null) {
                item { LoadMoreBlock(1, loading) { load(1) } }
            }
            if (hasNext) item { LoadMoreBlock(page + 1, loading) { load(page + 1) } }
        }
    }
}

@Composable
private fun <T> GridPage(
    title: String,
    entries: List<T>,
    key: (T) -> String,
    item: @Composable (T, Modifier) -> Unit,
) {
    var focusedKey by rememberSaveable(title) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(title) { mutableStateOf(false) }
    val contentFocus = remember(title) { FocusRequester() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(entries, focusedKey) {
        if (entries.isEmpty() || initialFocusRequested) return@LaunchedEffect
        val keys = entries.map(key)
        focusedKey = focusedKey?.takeIf(keys::contains) ?: keys.first()
        yield()
        runCatching { contentFocus.requestFocus() }
        initialFocusRequested = true
    }
    Column(Modifier.fillMaxSize().padding(64.dp, 44.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries, key = key) { entry ->
                val entryKey = key(entry)
                item(
                    entry,
                    Modifier.then(if (focusedKey == entryKey) Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = entryKey },
                )
            }
        }
    }
}

private enum class ArtistDetailSection { Songs, Albums }

private data class TrackDetailTab(
    val key: String,
    val label: String,
    val selected: Boolean,
    val onFocus: () -> Unit,
    val onSelect: () -> Unit,
)

private data class TrackDetailHeader(
    val kind: String,
    val declaredTrackCount: Int? = null,
    val extraMetadata: String? = null,
    val tabs: List<TrackDetailTab> = emptyList(),
    val onBack: () -> Unit,
)

private sealed interface TrackCollectionPrimaryAction {
    data object PlayAll : TrackCollectionPrimaryAction
    data class StartRoam(val onStarted: () -> Unit) : TrackCollectionPrimaryAction
}

@Composable
private fun ArtistDetail(
    container: AuthenticatedAppDependencies,
    artist: Artist,
    onBack: () -> Unit,
    onAlbum: (Album) -> Unit,
    onPlayer: (Track) -> Unit,
) {
    val stateKey = "artist:${artist.guid.value}"
    val retainedStore = LocalLibraryRetainedState.current
    val albumState = retainedStore.paged<Album>("$stateKey:albums")
    val albumSnapshot = albumState.snapshot
    val albums = albumSnapshot.entries
    var selectedSection by rememberSaveable(stateKey) { mutableStateOf(ArtistDetailSection.Songs) }
    var focusedArea by rememberSaveable(stateKey) { mutableStateOf("songs") }
    fun loadAlbums(target: Int) {
        if (albumState.loading || target > 1 && !albumState.snapshot.hasNext) return
        albumState.loading = true
        retainedStore.scope.launch {
            runCatching { container.musicRepository.artistAlbums(artist.guid.value, target) }
                .onSuccess {
                    albumState.snapshot = retainLoadedPage(albumState.snapshot, it) { album -> album.guid.value }
                }
                .onFailure {
                    albumState.snapshot = albumState.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = albumState.snapshot.initialLoadCompleted || target == 1,
                    )
                }
            albumState.loading = false
        }
    }
    LaunchedEffect(stateKey) {
        if (shouldLoadInitialPage(albumState.snapshot)) loadAlbums(1)
    }
    val tabs = ArtistDetailSection.entries.map { section ->
        TrackDetailTab(
            key = "artist-tab:${section.name}",
            label = if (section == ArtistDetailSection.Songs) "歌曲" else "专辑",
            selected = selectedSection == section,
            onFocus = { focusedArea = "tabs" },
            onSelect = {
                selectedSection = section
                focusedArea = "tabs"
            },
        )
    }
    TrackCollection(
        container = container,
        stateKey = "$stateKey:tracks",
        title = artist.name,
        coverId = artist.coverId,
        loader = { container.musicRepository.artistTracks(artist.guid.value, it) },
        queueSource = { sort -> QueueSource.Artist(artist.guid.value, sort) },
        onPlayer = onPlayer,
        initialFocusEnabled = selectedSection == ArtistDetailSection.Songs && focusedArea == "songs",
        onFocusOwnerChanged = { focusedArea = "songs" },
        detailHeader = TrackDetailHeader(
            kind = "歌手",
            declaredTrackCount = artist.trackCount,
            extraMetadata = artist.albumCount?.let { "$it 张专辑" },
            tabs = tabs,
            onBack = onBack,
        ),
        showTrackList = selectedSection == ArtistDetailSection.Songs,
        alternateContent = {
            ArtistAlbumGrid(
                stateKey = "$stateKey:album-grid",
                snapshot = albumSnapshot,
                loading = albumState.loading,
                initialFocusEnabled = selectedSection == ArtistDetailSection.Albums && focusedArea == "albums",
                onLoad = ::loadAlbums,
                onFocused = { focusedArea = "albums" },
                onAlbum = onAlbum,
            )
        },
    )
}

@Composable
private fun ArtistAlbumGrid(
    stateKey: String,
    snapshot: RetainedPageSnapshot<Album>,
    loading: Boolean,
    initialFocusEnabled: Boolean,
    onLoad: (Int) -> Unit,
    onFocused: () -> Unit,
    onAlbum: (Album) -> Unit,
) {
    val albums = snapshot.entries
    var focusedKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(stateKey) { mutableStateOf(false) }
    val restoredFocus = remember(stateKey) { FocusRequester() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(snapshot.initialLoadCompleted, albums, initialFocusEnabled, focusedKey) {
        if (!initialFocusEnabled || !snapshot.initialLoadCompleted || albums.isEmpty() || initialFocusRequested) return@LaunchedEffect
        val availableKeys = albums.map { it.guid.value }
        val targetKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.first()
        if (focusedKey != targetKey) {
            focusedKey = targetKey
            return@LaunchedEffect
        }
        repeat(3) {
            withFrameNanos { }
            if (runCatching { restoredFocus.requestFocus() }.getOrDefault(false)) {
                initialFocusRequested = true
                return@LaunchedEffect
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (albums.isEmpty() && loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("正在加载专辑", color = FnColors.Muted, fontSize = 16.sp, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
        items(albums, key = { it.guid.value }) { album ->
            val index = albums.indexOf(album)
            DetailAlbumCard(
                album = album,
                modifier = Modifier
                    .then(if (focusedKey == album.guid.value) Modifier.focusRequester(restoredFocus) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedKey = album.guid.value
                            onFocused()
                            if (snapshot.hasNext && index >= albums.size - 6) onLoad(snapshot.page + 1)
                        }
                    },
                onClick = {
                    focusedKey = album.guid.value
                    onAlbum(album)
                },
            )
        }
        if (albums.isEmpty() && !loading && snapshot.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(onClick = { onLoad(1) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("专辑加载失败，重试")
                }
            }
        }
        if (snapshot.hasNext) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(
                    enabled = !loading,
                    onClick = { onLoad(snapshot.page + 1) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(if (loading) "正在加载" else "加载更多专辑")
                }
            }
        }
    }
}

@Composable
private fun DetailAlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val container = LocalAuthenticatedDependencies.current
    val albumCoverId = album.coverId
    val shape = RoundedCornerShape(6.dp)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(106.dp)
            .onFocusChanged { state ->
                if (state.isFocused && albumCoverId != null) {
                    container.artworkBitmapCache.prefetch(albumCoverId, CoverVariant.Grid)
                }
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.Surface,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(9.dp),
    ) {
        val contentColor = LocalContentColor.current
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val artworkShape = RoundedCornerShape(4.dp)
            if (albumCoverId != null) {
                RemoteArtwork(
                    container = container,
                    coverId = albumCoverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(88.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        GeometricArtworkPlaceholder(album.name, FnColors.Coral, Modifier.fillMaxSize(), artworkShape)
                    },
                )
            } else {
                GeometricArtworkPlaceholder(album.name, FnColors.Coral, Modifier.size(88.dp), artworkShape)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    album.name,
                    color = contentColor,
                    fontSize = 16.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = listOfNotNull(
                    album.releaseDate?.takeIf(String::isNotBlank),
                    album.trackCount?.let { "$it 首" },
                ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        metadata,
                        color = contentColor.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackCollection(
    container: AuthenticatedAppDependencies,
    stateKey: String,
    title: String,
    subtitle: String = "",
    coverId: String? = null,
    loader: suspend (Int) -> Page<Track>,
    queueSource: (String) -> QueueSource,
    onPlayer: (Track) -> Unit,
    initialFocusEnabled: Boolean = true,
    onFocusOwnerChanged: () -> Unit = {},
    detailHeader: TrackDetailHeader? = null,
    primaryAction: TrackCollectionPrimaryAction = TrackCollectionPrimaryAction.PlayAll,
    showTrackList: Boolean = true,
    alternateContent: @Composable () -> Unit = {},
) {
    val retainedStore = LocalLibraryRetainedState.current
    val retained = retainedStore.tracks(stateKey)
    val snapshot = retained.snapshot
    val tracks = snapshot.tracks
    val loadedPages = snapshot.loadedPages
    val page = snapshot.page
    val hasNext = snapshot.hasNext
    val loading = retained.loading
    val error = snapshot.error
    val expectedTotal = snapshot.expectedTotal
    val expectedSort = snapshot.expectedSort
    var focusedKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember(stateKey) { mutableStateOf(false) }
    val restoredFocus = remember(stateKey) { FocusRequester() }
    val listState = rememberLazyListState()
    val actionScope = rememberCoroutineScope()
    var primaryActionRunning by remember(stateKey) { mutableStateOf(false) }
    fun setError(value: AppError?) {
        retained.snapshot = retained.snapshot.copy(error = value)
    }
    fun load(target: Int) {
        if (retained.loading) return
        retained.loading = true
        retainedStore.scope.launch {
            runCatching { loader(target) }
                .onSuccess {
                    retained.snapshot = retainTrackCollectionPage(retained.snapshot, it, target)
                }
                .onFailure {
                    retained.snapshot = retained.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = retained.snapshot.initialLoadCompleted || target == 1,
                    )
                }
            retained.loading = false
        }
    }
    fun play(index: Int) {
        val target = tracks.getOrNull(index)?.takeIf(::isTrackPlayable) ?: return
        val window = exactTrackQueueWindow(loadedPages, index) ?: run {
            setError(AppError.CollectionChanged)
            return
        }
        val queue = container.musicRepository.prepareQueue(window.items)
        val queueIds = queue.map { it.track.guid.value }
        val segmentIds = window.segments.flatMap(QueuePageSegment::mediaIds)
        val queueIndex = queue.indexOfFirst { it.track.guid == target.guid }
        if (queueIds != segmentIds || queueIndex < 0) {
            setError(AppError.TranscodeUnavailable)
            return
        }
        if (queue.isEmpty()) {
            setError(AppError.TranscodeUnavailable)
            return
        }
        val sort = expectedSort ?: run {
            setError(AppError.CollectionChanged)
            return
        }
        actionScope.launch {
            val transition = runCatching {
                container.playbackController.playQueue(
                    tracks = queue,
                    startIndex = queueIndex,
                    source = queueSource(sort),
                    windowStart = window.segments.first().sourceStartIndex,
                    firstPage = window.segments.first().page,
                    lastPage = window.segments.last().page,
                    knownTotal = expectedTotal,
                    segments = window.segments,
                )
            }.getOrElse {
                setError(AppError.Unknown(it.message))
                return@launch
            }
            if (transition == null) {
                setError(AppError.TranscodeUnavailable)
                return@launch
            }
            runCatching { transition.awaitCommitted() }
                .onSuccess {
                    setError(null)
                    onPlayer(target)
                }
                .onFailure { setError(AppError.Unknown(it.message)) }
        }
    }
    fun runPrimaryAction() {
        when (val action = primaryAction) {
            TrackCollectionPrimaryAction.PlayAll -> play(tracks.indexOfFirst(::isTrackPlayable))
            is TrackCollectionPrimaryAction.StartRoam -> {
                if (primaryActionRunning) return
                if (container.playbackController.state.value.queueKind == QueueKind.Roam) {
                    action.onStarted()
                    return
                }
                primaryActionRunning = true
                actionScope.launch {
                    if (container.playbackController.startRoam()) {
                        setError(null)
                        action.onStarted()
                    } else {
                        setError(container.playbackController.state.value.roamError ?: AppError.Unknown())
                    }
                    primaryActionRunning = false
                }
            }
        }
    }
    LaunchedEffect(stateKey) {
        if (!retained.snapshot.initialLoadCompleted) {
            load(1)
        }
    }
    val playableTracks = tracks.filter(::isTrackPlayable)
    val primaryActionEnabled = when (primaryAction) {
        TrackCollectionPrimaryAction.PlayAll -> playableTracks.isNotEmpty()
        is TrackCollectionPrimaryAction.StartRoam -> true
    }
    val primaryActionLabel = when (primaryAction) {
        TrackCollectionPrimaryAction.PlayAll -> "播放全部"
        is TrackCollectionPrimaryAction.StartRoam -> "随机漫游"
    }
    LaunchedEffect(snapshot.initialLoadCompleted, tracks, focusedKey, initialFocusEnabled, showTrackList, primaryActionEnabled) {
        if (!initialFocusEnabled || !snapshot.initialLoadCompleted || initialFocusRequested) return@LaunchedEffect
        val availableKeys = buildList {
            if (primaryActionEnabled) add("primary-action")
            detailHeader?.tabs?.filter { it.selected }?.forEach { add(it.key) }
            if (showTrackList) addAll(playableTracks.map { it.guid.value })
            if (detailHeader != null) add("detail-back")
        }
        val targetKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.firstOrNull()
        if (focusedKey != targetKey) {
            focusedKey = targetKey
            return@LaunchedEffect
        }
        if (targetKey != null) {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { restoredFocus.requestFocus() }.getOrDefault(false)) {
                    initialFocusRequested = true
                    return@LaunchedEffect
                }
            }
        }
    }
    if (detailHeader != null) {
        DetailTrackCollection(
            container = container,
            header = detailHeader,
            title = title,
            subtitle = subtitle,
            coverId = coverId,
            trackCount = detailHeader.declaredTrackCount ?: expectedTotal?.takeIf { it > 0 },
            tracks = tracks,
            loading = loading,
            error = error,
            hasNext = hasNext,
            listState = listState,
            focusedKey = focusedKey,
            restoredFocus = restoredFocus,
            primaryActionLabel = primaryActionLabel,
            primaryActionEnabled = primaryActionEnabled,
            showTrackList = showTrackList,
            onFocusKey = {
                focusedKey = it
                onFocusOwnerChanged()
            },
            onPrimaryAction = ::runPrimaryAction,
            onTrackFocused = { index, key ->
                focusedKey = key
                onFocusOwnerChanged()
                if (hasNext && index >= tracks.size - 15) load(page + 1)
            },
            onTrack = ::play,
            onLoadMore = { load(page + 1) },
            alternateContent = alternateContent,
        )
    } else {
        LegacyTrackCollection(
            container = container,
            title = title,
            subtitle = subtitle,
            coverId = coverId,
            tracks = tracks,
            loading = loading,
            error = error,
            hasNext = hasNext,
            listState = listState,
            focusedKey = focusedKey,
            restoredFocus = restoredFocus,
            primaryActionEnabled = primaryActionEnabled,
            onFocusKey = {
                focusedKey = it
                onFocusOwnerChanged()
            },
            onPrimaryAction = ::runPrimaryAction,
            onTrackFocused = { index, key ->
                focusedKey = key
                onFocusOwnerChanged()
                if (hasNext && index >= tracks.size - 15) load(page + 1)
            },
            onTrack = ::play,
            onLoadMore = { load(page + 1) },
        )
    }
}

@Composable
private fun LegacyTrackCollection(
    container: AuthenticatedAppDependencies,
    title: String,
    subtitle: String,
    coverId: String?,
    tracks: List<Track>,
    loading: Boolean,
    error: AppError?,
    hasNext: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusedKey: String?,
    restoredFocus: FocusRequester,
    primaryActionEnabled: Boolean,
    onFocusKey: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onTrackFocused: (Int, String) -> Unit,
    onTrack: (Int) -> Unit,
    onLoadMore: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 38.dp), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
        Column(Modifier.width(330.dp)) {
            val artworkShape = RoundedCornerShape(8.dp)
            if (coverId != null) {
                RemoteArtwork(
                    container = container,
                    coverId = coverId,
                    variant = CoverVariant.Grid,
                    modifier = Modifier.size(300.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        GeometricArtworkPlaceholder(
                            text = title,
                            accent = FnColors.Coral,
                            modifier = Modifier.fillMaxSize(),
                            shape = artworkShape,
                            showInitial = false,
                        )
                    },
                )
            } else {
                GeometricArtworkPlaceholder(
                    text = title,
                    accent = FnColors.Coral,
                    modifier = Modifier.size(300.dp),
                    shape = artworkShape,
                    showInitial = false,
                )
            }
            Text(title, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = FnColors.Muted, fontSize = 21.sp)
            Spacer(Modifier.height(18.dp))
            Button(
                enabled = primaryActionEnabled,
                onClick = onPrimaryAction,
                modifier = Modifier
                    .then(if (focusedKey == "primary-action") Modifier.focusRequester(restoredFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocusKey("primary-action") },
            ) {
                Text("播放全部")
            }
            error?.let { InlineError(it) }
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(tracks, key = { _, track -> track.guid.value }) { index, track ->
                TrackRow(
                    track,
                    enabled = isTrackPlayable(track),
                    modifier = Modifier
                        .then(if (focusedKey == track.guid.value) Modifier.focusRequester(restoredFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) onTrackFocused(index, track.guid.value) },
                ) { onTrack(index) }
            }
            if (hasNext) item {
                Button(enabled = !loading, onClick = onLoadMore, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                    Text(if (loading) "正在加载" else "加载更多")
                }
            }
        }
    }
}

@Composable
private fun DetailTrackCollection(
    container: AuthenticatedAppDependencies,
    header: TrackDetailHeader,
    title: String,
    subtitle: String,
    coverId: String?,
    trackCount: Int?,
    tracks: List<Track>,
    loading: Boolean,
    error: AppError?,
    hasNext: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusedKey: String?,
    restoredFocus: FocusRequester,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean,
    showTrackList: Boolean,
    onFocusKey: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onTrackFocused: (Int, String) -> Unit,
    onTrack: (Int) -> Unit,
    onLoadMore: () -> Unit,
    alternateContent: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(184.dp), verticalAlignment = Alignment.Top) {
            DetailBackButton(
                modifier = Modifier
                    .then(if (focusedKey == "detail-back") Modifier.focusRequester(restoredFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocusKey("detail-back") },
                onClick = header.onBack,
            )
            Spacer(Modifier.width(18.dp))
            val artworkShape = RoundedCornerShape(6.dp)
            if (coverId != null) {
                RemoteArtwork(
                    container = container,
                    coverId = coverId,
                    variant = CoverVariant.Grid,
                    modifier = Modifier.size(184.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        GeometricArtworkPlaceholder(title, FnColors.Coral, Modifier.fillMaxSize(), artworkShape, showInitial = false)
                    },
                )
            } else {
                GeometricArtworkPlaceholder(
                    text = title,
                    accent = FnColors.Coral,
                    modifier = Modifier.size(184.dp),
                    shape = artworkShape,
                    showInitial = false,
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(header.kind, color = FnColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text(
                    title,
                    fontSize = 31.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = buildList {
                    if (subtitle.isNotBlank()) add(subtitle)
                    trackCount?.let { add("$it 首歌曲") }
                    header.extraMetadata?.takeIf(String::isNotBlank)?.let(::add)
                }.joinToString("  ·  ")
                if (metadata.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(metadata, color = FnColors.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    header.tabs.forEach { tab ->
                        DetailTabButton(
                            label = tab.label,
                            selected = tab.selected,
                            modifier = Modifier
                                .then(if (focusedKey == tab.key) Modifier.focusRequester(restoredFocus) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        onFocusKey(tab.key)
                                        tab.onFocus()
                                    }
                                },
                            onClick = tab.onSelect,
                        )
                    }
                    val primaryShape = RoundedCornerShape(6.dp)
                    Button(
                        enabled = primaryActionEnabled,
                        onClick = onPrimaryAction,
                        modifier = Modifier
                            .then(if (focusedKey == "primary-action") Modifier.focusRequester(restoredFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocusKey("primary-action") }
                            .height(44.dp),
                        shape = ButtonDefaults.shape(
                            primaryShape,
                            primaryShape,
                            primaryShape,
                            primaryShape,
                            primaryShape,
                        ),
                        scale = ButtonDefaults.scale(focusedScale = 1.035f),
                        colors = ButtonDefaults.colors(
                            containerColor = FnColors.Surface,
                            contentColor = FnColors.Text,
                            focusedContainerColor = FnColors.FocusFill,
                            focusedContentColor = FnColors.Text,
                            pressedContainerColor = FnColors.FocusFill,
                            pressedContentColor = FnColors.Text,
                        ),
                        border = ButtonDefaults.border(
                            border = Border(BorderStroke(1.dp, FnColors.Coral.copy(alpha = 0.76f)), shape = primaryShape),
                            focusedBorder = Border(BorderStroke(1.5.dp, FnColors.Coral), shape = primaryShape),
                            pressedBorder = Border(BorderStroke(1.5.dp, FnColors.Coral), shape = primaryShape),
                        ),
                        contentPadding = PaddingValues(horizontal = 19.dp, vertical = 0.dp),
                    ) {
                        Text("▶  $primaryActionLabel", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        if (showTrackList) {
            DetailTrackColumnHeader()
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.09f)))
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (error != null) {
                    item { InlineError(error) }
                }
                if (tracks.isEmpty() && loading) {
                    item {
                        Text("正在加载歌曲", color = FnColors.Muted, fontSize = 16.sp, modifier = Modifier.padding(vertical = 20.dp))
                    }
                }
                itemsIndexed(tracks, key = { _, track -> track.guid.value }) { index, track ->
                    DetailTrackRow(
                        index = index,
                        track = track,
                        enabled = isTrackPlayable(track),
                        modifier = Modifier
                            .then(if (focusedKey == track.guid.value) Modifier.focusRequester(restoredFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onTrackFocused(index, track.guid.value) },
                        onClick = { onTrack(index) },
                    )
                }
                if (hasNext) {
                    item {
                        Button(
                            enabled = !loading,
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 5.dp),
                        ) {
                            Text(if (loading) "正在加载" else "加载更多")
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { alternateContent() }
        }
    }
}

@Composable
private fun DetailBackButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = CircleShape
    Button(
        onClick = onClick,
        modifier = modifier.size(46.dp).semantics { contentDescription = "返回" },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.Surface,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text("‹", fontSize = 36.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DetailTabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp).semantics { this.selected = selected },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) FnColors.Coral.copy(alpha = 0.2f) else Color.Transparent,
            contentColor = if (selected) FnColors.Coral else FnColors.Muted,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun DetailTrackColumnHeader() {
    Row(
        Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", color = FnColors.Muted, fontSize = 12.sp, modifier = Modifier.width(54.dp))
        Text("歌曲", color = FnColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("歌手", color = FnColors.Muted, fontSize = 12.sp, modifier = Modifier.width(220.dp))
        Text("时长", color = FnColors.Muted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
    }
}

@Composable
private fun DetailTrackRow(
    index: Int,
    track: Track,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .semantics {
                contentDescription = "${index + 1}. ${track.title} ${track.artistName.orEmpty()}"
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
            pressedContainerColor = FnColors.FocusFill,
            pressedContentColor = FnColors.Text,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = FnColors.Muted.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 0.dp),
    ) {
        val contentColor = LocalContentColor.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = contentColor.copy(alpha = 0.66f),
                fontSize = 13.sp,
                modifier = Modifier.width(54.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(track.title, color = contentColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                track.albumName?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = contentColor.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                track.artistName.orEmpty(),
                color = contentColor.copy(alpha = 0.72f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(220.dp),
            )
            Text(
                if (track.isCue) "需兼容" else formatDuration(track.durationMs ?: 0),
                color = contentColor.copy(alpha = 0.72f),
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp),
            )
        }
    }
}

internal data class ExactTrackQueueWindow(
    val items: List<Track>,
    val segments: List<QueuePageSegment>,
)

internal fun exactTrackQueueWindow(
    pages: List<Page<Track>>,
    selectedIndex: Int,
    maxSize: Int = MAX_ACTIVE_QUEUE_ITEMS,
): ExactTrackQueueWindow? {
    val first = pages.firstOrNull() ?: return null
    if (first.pageSize <= 0 || maxSize < first.pageSize) return null
    val rawWindowSize = maxSize / first.pageSize * first.pageSize
    if (pages.withIndex().any { (index, page) ->
            page.page != first.page + index ||
                page.pageSize != first.pageSize ||
                page.total != first.total ||
                page.sort != first.sort ||
                page.items.size > page.pageSize
        }
    ) return null

    val allItems = pages.flatMap(Page<Track>::items)
    val selected = allItems.getOrNull(selectedIndex)?.takeIf(::isTrackPlayable) ?: return null
    val bounded = boundedQueueWindow(
        items = allItems,
        selectedIndex = selectedIndex,
        maxSize = rawWindowSize,
        pageSize = first.pageSize,
    )
    val windowStart = bounded.startIndex
    val windowEnd = windowStart + bounded.items.size
    var loadedOffset = 0
    val retainedPages = buildList {
        pages.forEach { page ->
            val pageStart = loadedOffset
            val pageEnd = pageStart + page.items.size
            loadedOffset = pageEnd
            if (pageEnd <= windowStart || pageStart >= windowEnd) return@forEach
            if (pageStart < windowStart || pageEnd > windowEnd) return null
            add(page)
        }
    }
    if (retainedPages.flatMap(Page<Track>::items) != bounded.items) return null
    val playableIds = bounded.items.filter(::isTrackPlayable).map { it.guid.value }
    if (playableIds.distinct().size != playableIds.size) return null

    val segments = retainedPages.map { page ->
        val sourceStartIndex = (page.page - 1) * page.pageSize
        QueuePageSegment(
            page = page.page,
            rawRowCount = page.items.size,
            playableItems = page.items.mapIndexedNotNull { index, track ->
                track.takeIf(::isTrackPlayable)?.let {
                    QueuePageItem(it.guid.value, sourceStartIndex + index)
                }
            },
            sort = page.sort,
            knownTotal = page.total,
            pageSize = page.pageSize,
            sourceStartIndex = sourceStartIndex,
        )
    }
    if (segments.flatMap(QueuePageSegment::mediaIds) != playableIds) return null
    if (selected.guid.value !in playableIds) return null
    return ExactTrackQueueWindow(items = bounded.items, segments = segments)
}

private fun isTrackPlayable(track: Track): Boolean =
    !track.isCue && (track.accessStatus == null || track.accessStatus == 0)

@Composable
private fun TrackRow(track: Track, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(enabled = enabled, onClick = onClick, modifier = modifier.fillMaxWidth().height(72.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val artworkShape = RoundedCornerShape(5.dp)
            val trackCoverId = track.coverId
            if (trackCoverId != null) {
                RemoteArtwork(
                    container = LocalAuthenticatedDependencies.current,
                    coverId = trackCoverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(52.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        GeometricArtworkPlaceholder(
                            text = track.title,
                            accent = FnColors.Teal,
                            modifier = Modifier.fillMaxSize(),
                            shape = artworkShape,
                            showInitial = false,
                        )
                    },
                )
            } else {
                GeometricArtworkPlaceholder(
                    text = track.title,
                    accent = FnColors.Teal,
                    modifier = Modifier.size(52.dp),
                    shape = artworkShape,
                    showInitial = false,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artistName.orEmpty(), color = FnColors.Muted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (track.isCue) "需兼容播放" else formatDuration(track.durationMs ?: 0), color = FnColors.Muted, fontSize = 17.sp)
        }
    }
}

@Composable
private fun PlaylistTile(
    title: String,
    subtitle: String,
    coverId: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    showArtworkInitial: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(8.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.size(width = 193.dp, height = 142.dp),
        shape = ButtonDefaults.shape(
            shape = cardShape,
            focusedShape = cardShape,
            pressedShape = cardShape,
            disabledShape = cardShape,
            focusedDisabledShape = cardShape,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF1B201F),
            contentColor = FnColors.Text,
            focusedContainerColor = Color(0xFF303634),
            focusedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            PlaylistTileArtwork(
                title = title,
                coverId = coverId,
                accent = accent,
                modifier = Modifier.fillMaxWidth().height(108.dp),
                showInitial = showArtworkInitial,
            )
            Row(
                Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 9.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    maxLines = if (subtitle.isBlank()) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = FnColors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PlaylistTileArtwork(
    title: String,
    coverId: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    showInitial: Boolean = true,
) {
    val container = LocalAuthenticatedDependencies.current
    val shape = RectangleShape
    if (coverId != null) {
        RemoteArtwork(
            container = container,
            coverId = coverId,
            variant = CoverVariant.Grid,
            modifier = modifier,
            shape = shape,
            contentScale = ContentScale.Crop,
            placeholderContent = {
                GeometricArtworkPlaceholder(title, accent, Modifier.fillMaxSize(), shape, showInitial)
            },
        )
    } else {
        GeometricArtworkPlaceholder(title, accent, modifier, shape, showInitial)
    }
}

@Composable
private fun ArtistLockup(
    title: String,
    subtitle: String,
    coverId: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val container = LocalAuthenticatedDependencies.current
    val shape = RoundedCornerShape(8.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .size(width = 170.dp, height = 95.dp)
            .onFocusChanged { state ->
                if (state.isFocused && coverId != null) {
                    container.artworkBitmapCache.prefetch(coverId, CoverVariant.Grid)
                }
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = lockupButtonColors(),
        contentPadding = PaddingValues(7.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (coverId != null) {
                RemoteArtwork(
                    container = container,
                    coverId = coverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(61.dp),
                    shape = CircleShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = { ArtistAvatarPlaceholder(title) },
                )
            } else {
                ArtistAvatarPlaceholder(title)
            }
            Spacer(Modifier.width(10.dp))
            LockupLabels(title, subtitle, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ArtistAvatarPlaceholder(title: String) {
    Box(
        Modifier.size(61.dp).background(Color(0xFF2D4A46), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.trim().take(1).uppercase(), color = FnColors.Teal, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AlbumLockup(
    title: String,
    subtitle: String,
    coverId: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val container = LocalAuthenticatedDependencies.current
    val shape = RoundedCornerShape(8.dp)
    val artworkShape = RoundedCornerShape(4.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .size(width = 165.dp, height = 95.dp)
            .onFocusChanged { state ->
                if (state.isFocused && coverId != null) {
                    container.artworkBitmapCache.prefetch(coverId, CoverVariant.Grid)
                }
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = lockupButtonColors(),
        contentPadding = PaddingValues(7.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (coverId != null) {
                RemoteArtwork(
                    container = container,
                    coverId = coverId,
                    variant = CoverVariant.Compact,
                    modifier = Modifier.size(73.dp),
                    shape = artworkShape,
                    contentScale = ContentScale.Crop,
                    placeholderContent = {
                        GeometricArtworkPlaceholder(title, FnColors.Coral, Modifier.fillMaxSize(), artworkShape)
                    },
                )
            } else {
                GeometricArtworkPlaceholder(title, FnColors.Coral, Modifier.size(73.dp), artworkShape)
            }
            Spacer(Modifier.width(9.dp))
            LockupLabels(title, subtitle, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LibraryLockup(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val artworkShape = RoundedCornerShape(6.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.size(width = 170.dp, height = 95.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = lockupButtonColors(),
        contentPadding = PaddingValues(7.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            GeometricArtworkPlaceholder(title, FnColors.Teal, Modifier.size(61.dp), artworkShape)
            Spacer(Modifier.width(10.dp))
            LockupLabels(title, subtitle, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LockupLabels(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = FnColors.Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun lockupButtonColors() = ButtonDefaults.colors(
    containerColor = Color(0xFF1B201F),
    contentColor = FnColors.Text,
    focusedContainerColor = Color(0xFF303634),
    focusedContentColor = FnColors.Text,
)

@Composable
private fun LoadMoreBlock(nextPage: Int, loading: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Button(
        enabled = !loading,
        onClick = onClick,
        modifier = Modifier.size(width = 170.dp, height = 95.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = lockupButtonColors(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(if (loading) "正在加载" else "加载更多", fontSize = 12.sp)
            Text("第 $nextPage 页", color = FnColors.Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun RemoteArtwork(
    container: AuthenticatedAppDependencies,
    coverId: String,
    variant: CoverVariant,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    contentScale: ContentScale = ContentScale.Fit,
    placeholderContent: (@Composable () -> Unit)? = null,
) {
    val bitmap = rememberRemoteArtworkBitmap(container, coverId, variant)
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, modifier.clip(shape), contentScale = contentScale)
    } else {
        Box(modifier.background(FnColors.Surface, shape), contentAlignment = Alignment.Center) {
            if (placeholderContent != null) {
                placeholderContent()
            } else {
                GeometricArtworkPlaceholder(
                    text = "",
                    accent = FnColors.Teal,
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    showInitial = false,
                )
            }
        }
    }
}

@Composable
private fun rememberRemoteArtworkBitmap(
    container: AuthenticatedAppDependencies,
    coverId: String?,
    variant: CoverVariant,
): Bitmap? {
    val initialBitmap = remember(container, coverId, variant) {
        coverId?.let { container.artworkBitmapCache.peek(it, variant) }
    }
    val bitmap by produceState(initialBitmap, container, coverId, variant) {
        value = coverId?.let { container.artworkBitmapCache.get(it, variant) }
    }
    return if (coverId == null) null else bitmap
}

@Composable
private fun InlineError(error: AppError) {
    Text(appErrorMessage(error), color = FnColors.Coral, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
}

internal fun appErrorMessage(error: AppError): String = when (error) {
    AppError.NetworkUnavailable -> "NAS 暂时不可用"
    AppError.Empty -> "暂无内容"
    AppError.UnavailableTrack -> "歌曲不可访问"
    AppError.TranscodeUnavailable -> "兼容播放参数尚未确认"
    AppError.CollectionChanged -> "列表已更新，请返回后重新载入"
    else -> "加载失败，请重试"
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
