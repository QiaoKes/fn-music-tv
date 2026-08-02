package com.fnmusic.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.fnmusic.tv.AppContainer
import com.fnmusic.tv.NowPlayingPresentation
import com.fnmusic.tv.NowPlayingResourceState
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
import com.fnmusic.tv.core.model.SharedLibrary
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
import com.fnmusic.tv.core.model.preferences.CacheBudget
import com.fnmusic.tv.core.model.preferences.CacheUsage
import com.fnmusic.tv.core.playback.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

private sealed interface LibraryRoute {
    data object Home : LibraryRoute
    data object My : LibraryRoute
    data object AllPlaylists : LibraryRoute
    data class PlaylistDetail(val playlist: Playlist) : LibraryRoute
    data object Artists : LibraryRoute
    data object Albums : LibraryRoute
    data object AllTracks : LibraryRoute
    data class ArtistDetail(val artist: Artist) : LibraryRoute
    data class AlbumDetail(val album: Album) : LibraryRoute
    data class Player(val track: Track?) : LibraryRoute
    data object Settings : LibraryRoute
}

private val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("Missing AppContainer") }

internal data class RetainedPageSnapshot<T>(
    val entries: List<T> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val error: AppError? = null,
    val initialLoadCompleted: Boolean = false,
)

internal fun <T> retainLoadedPage(
    current: RetainedPageSnapshot<T>,
    loaded: Page<T>,
    key: (T) -> String,
): RetainedPageSnapshot<T> = current.copy(
    entries = (current.entries + loaded.items).distinctBy(key),
    page = loaded.page,
    hasNext = loaded.hasNext,
    error = null,
    initialLoadCompleted = current.initialLoadCompleted || loaded.page == 1,
)

internal fun shouldLoadInitialPage(snapshot: RetainedPageSnapshot<*>): Boolean =
    !snapshot.initialLoadCompleted

internal data class RetainedTrackCollectionSnapshot(
    val tracks: List<Track> = emptyList(),
    val loadedPages: List<Page<Track>> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val error: AppError? = null,
    val expectedTotal: Int? = null,
    val expectedSort: String? = null,
    val initialLoadCompleted: Boolean = false,
)

internal fun retainTrackCollectionPage(
    current: RetainedTrackCollectionSnapshot,
    loaded: Page<Track>,
    targetPage: Int,
): RetainedTrackCollectionSnapshot {
    val existing = current.tracks.asSequence().map { it.guid.value }.toHashSet()
    val firstPage = current.loadedPages.firstOrNull()
    val drifted = loaded.page != targetPage || loaded.items.size > loaded.pageSize || targetPage > 1 && (
        current.expectedTotal != loaded.total ||
            current.expectedSort != loaded.sort ||
            firstPage?.pageSize != loaded.pageSize ||
            loaded.items.any { it.guid.value in existing }
        )
    if (drifted) {
        return current.copy(
            hasNext = false,
            error = AppError.CollectionChanged,
            initialLoadCompleted = current.initialLoadCompleted || targetPage == 1,
        )
    }
    return current.copy(
        tracks = current.tracks + loaded.items,
        loadedPages = current.loadedPages + loaded,
        page = targetPage,
        hasNext = loaded.hasNext,
        error = null,
        expectedTotal = loaded.total,
        expectedSort = loaded.sort,
        initialLoadCompleted = current.initialLoadCompleted || targetPage == 1,
    )
}

private class RetainedPagedGridState<T> {
    var snapshot by mutableStateOf(RetainedPageSnapshot<T>())
    var loading by mutableStateOf(false)
}

private class RetainedTrackCollectionState {
    var snapshot by mutableStateOf(RetainedTrackCollectionSnapshot())
    var loading by mutableStateOf(false)
}

private class LibraryRetainedStateStore(val scope: CoroutineScope) {
    private val pagedStates = mutableMapOf<String, RetainedPagedGridState<*>>()
    private val trackStates = mutableMapOf<String, RetainedTrackCollectionState>()

    @Suppress("UNCHECKED_CAST")
    fun <T> paged(key: String): RetainedPagedGridState<T> =
        pagedStates.getOrPut(key) { RetainedPagedGridState<T>() } as RetainedPagedGridState<T>

    fun tracks(key: String): RetainedTrackCollectionState =
        trackStates.getOrPut(key) { RetainedTrackCollectionState() }
}

private val LocalLibraryRetainedState = staticCompositionLocalOf<LibraryRetainedStateStore> {
    error("Missing library retained state")
}

@Composable
internal fun AuthenticatedApp(
    container: AppContainer,
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
    onMoveToBackground: () -> Unit,
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
    val open: (LibraryRoute) -> Unit = { stack = stack + it }
    val root: (LibraryRoute) -> Unit = { stack = listOf(it) }
    LaunchedEffect(route.storageKey()) { lastHomeBackAt = 0L }
    BackHandler {
        when {
            stack.size > 1 -> stack = stack.dropLast(1)
            route == LibraryRoute.My -> root(LibraryRoute.Home)
            route == LibraryRoute.Home -> {
                val now = SystemClock.elapsedRealtime()
                if (isHomeBackConfirmed(lastHomeBackAt, now)) {
                    lastHomeBackAt = 0L
                    onMoveToBackground()
                } else {
                    lastHomeBackAt = now
                    Toast.makeText(context, "再按一次返回桌面", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(playback.error) {
        if (playback.error?.contains("BAD_HTTP_STATUS") == true) {
            container.sessionRepository.verifyCurrentSession()
        }
    }

    stateHolder.SaveableStateProvider(route.storageKey()) {
        CompositionLocalProvider(
            LocalAppContainer provides container,
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
        )
        is LibraryRoute.ArtistDetail -> ArtistDetail(container, route.artist, onAlbum = { open(LibraryRoute.AlbumDetail(it)) }) {
            open(LibraryRoute.Player(it))
        }
        is LibraryRoute.AlbumDetail -> TrackCollection(
            container = container,
            stateKey = "album:${route.album.guid.value}:tracks",
            title = route.album.name,
            subtitle = route.album.artistName.orEmpty(),
            coverId = route.album.coverId,
            loader = { container.musicRepository.albumTracks(route.album.guid.value, it) },
            queueSource = { sort -> QueueSource.Album(route.album.guid.value, sort) },
            onPlayer = { open(LibraryRoute.Player(it)) },
        )
        is LibraryRoute.Player -> ImmersivePlayer(
            container,
            playback,
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

private fun LibraryRoute.storageKey(): String = when (this) {
    LibraryRoute.Home -> "home"
    LibraryRoute.My -> "my"
    LibraryRoute.AllPlaylists -> "playlists"
    is LibraryRoute.PlaylistDetail -> "playlist:${playlist.guid.value}"
    LibraryRoute.Artists -> "artists"
    LibraryRoute.Albums -> "albums"
    LibraryRoute.AllTracks -> "tracks"
    is LibraryRoute.ArtistDetail -> "artist:${artist.guid.value}"
    is LibraryRoute.AlbumDetail -> "album:${album.guid.value}"
    is LibraryRoute.Player -> "player"
    LibraryRoute.Settings -> "settings"
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
            Text("飞牛音乐 TV", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onClick = onHome, enabled = !selectedHome) { Text("首页", fontSize = 21.sp) }
            Button(onClick = onMy, enabled = selectedHome) { Text("我的", fontSize = 21.sp) }
        }
    }
}

@Composable
private fun NowPlayingPill(
    playback: PlaybackUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(19.dp)
    val coverId = playback.coverId
    Button(
        onClick = onClick,
        modifier = modifier.size(width = 186.dp, height = 37.dp),
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
                    container = LocalAppContainer.current,
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
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(1.5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        playback.title.ifBlank { "正在播放" },
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
    container: AppContainer,
    playback: PlaybackUiState,
    onMy: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onAll: () -> Unit,
    onPlayer: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var playlistsLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { container.musicRepository.playlists() }
            .onSuccess { playlists = it }
            .onFailure { error = (it as? AppException)?.error ?: AppError.Unknown() }
        playlistsLoaded = true
    }
    LaunchedEffect(playlistsLoaded, playback.roamBusy, playback.hasMedia, focusedKey) {
        if (initialFocusRequested) return@LaunchedEffect
        val restoringNowPlaying = playback.hasMedia && focusedKey == "now-playing"
        if (!playlistsLoaded && !restoringNowPlaying) return@LaunchedEffect
        val availableKeys = buildList {
            if (playlistsLoaded) {
                if (!playback.roamBusy) add("roam")
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
                    if (playback.roamBusy) "正在准备" else "从曲库里遇见下一首",
                    null,
                    FnColors.Teal,
                    enabled = !playback.roamBusy,
                    modifier = Modifier
                        .then(if (focusedKey == "roam") Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = "roam" },
                ) {
                    if (playback.queueKind == QueueKind.Roam) {
                        onPlayer()
                        return@PlaylistTile
                    }
                    scope.launch {
                        if (container.playbackController.startRoam()) {
                            error = null
                            onPlayer()
                        } else {
                            error = container.playbackController.state.value.roamError ?: AppError.Unknown()
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
                    modifier = Modifier
                        .then(if (focusedKey == "all-playlists") Modifier.focusRequester(contentFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = "all-playlists" },
                    onClick = onAll,
                )
            }
        }
        error?.let { InlineError(it) }
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
    container: AppContainer,
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
    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var libraries by remember { mutableStateOf<List<SharedLibrary>>(emptyList()) }
    var artistsLoaded by remember { mutableStateOf(false) }
    var albumsLoaded by remember { mutableStateOf(false) }
    var librariesLoaded by remember { mutableStateOf(false) }
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = LocalLibraryRetainedState.current.scope
    LaunchedEffect(Unit) {
        launch {
            runCatching { container.musicRepository.artists(1).items }.onSuccess { artists = it }
            artistsLoaded = true
        }
        launch {
            runCatching { container.musicRepository.albums(1).items }.onSuccess { albums = it }
            albumsLoaded = true
        }
        launch {
            runCatching { container.musicRepository.sharedLibraries() }.onSuccess { libraries = it }
            librariesLoaded = true
        }
    }
    LaunchedEffect(artistsLoaded, albumsLoaded, librariesLoaded, playback.hasMedia, focusedKey) {
        if (initialFocusRequested) return@LaunchedEffect
        val allContentLoaded = artistsLoaded && albumsLoaded && librariesLoaded
        val restoringChrome = focusedKey == "settings" || playback.hasMedia && focusedKey == "now-playing"
        if (!allContentLoaded && !restoringChrome) return@LaunchedEffect
        val availableKeys = buildList {
            if (allContentLoaded) {
                addAll(artists.take(8).map { "artist:${it.guid.value}" })
                add("all-artists")
                addAll(albums.take(8).map { "album:${it.guid.value}" })
                add("all-albums")
                add("all-tracks")
                addAll(libraries.filter { it.accessStatus == 0 }.map { "library:${it.name}" })
                add("all-libraries")
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
                        runCatching { container.playbackController.clearSessionDurably() }
                        container.musicRepository.clearArtwork()
                        container.musicRepository.clearLocalNamespace(includeEssential = true)
                        container.sessionRepository.logout()
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
                val libraryEntries = listOf(BandEntry("全部歌曲", "完整曲库", null, BandKind.Library, "all-tracks", onAllTracks)) + libraries.map {
                    BandEntry(
                        it.name,
                        if (it.accessStatus == 0) "可访问" else "暂不可用",
                        null,
                        BandKind.Library,
                        "library:${it.name}",
                        if (it.accessStatus == 0) onAllTracks else null,
                    )
                }
                MediaBand(
                    "音乐库",
                    libraryEntries,
                    BandEntry("全部音乐库", "浏览完整列表", null, BandKind.Library, "all-libraries", onAllTracks),
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
private fun AllPlaylists(container: AppContainer, onOpen: (Playlist) -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { container.musicRepository.playlists() }.onSuccess { playlists = it } }
    GridPage("全部歌单", playlists, { it.guid.value }) { playlist, modifier ->
        PlaylistTile(playlist.name, "歌单", playlist.coverId, FnColors.Coral, modifier = modifier) { onOpen(playlist) }
    }
}

@Composable
private fun ArtistGrid(container: AppContainer, onOpen: (Artist) -> Unit) {
    PagedGrid("artists", "全部歌手", loader = container.musicRepository::artists, key = { it.guid.value }) { artist, modifier ->
        ArtistLockup(artist.name, "${artist.trackCount ?: 0} 首歌曲", artist.coverId, modifier = modifier) { onOpen(artist) }
    }
}

@Composable
private fun AlbumGrid(container: AppContainer, onOpen: (Album) -> Unit) {
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

@Composable
private fun ArtistDetail(container: AppContainer, artist: Artist, onAlbum: (Album) -> Unit, onPlayer: (Track) -> Unit) {
    val stateKey = "artist:${artist.guid.value}"
    val retainedStore = LocalLibraryRetainedState.current
    val albumState = retainedStore.paged<Album>("$stateKey:albums")
    val albumSnapshot = albumState.snapshot
    val albums = albumSnapshot.entries.take(8)
    var focusedSectionKey by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var initialAlbumFocusRequested by remember(stateKey) { mutableStateOf(false) }
    val albumFocus = remember(stateKey) { FocusRequester() }
    val albumRowState = rememberLazyListState()
    fun loadAlbums() {
        if (albumState.loading || !shouldLoadInitialPage(albumState.snapshot)) return
        albumState.loading = true
        retainedStore.scope.launch {
            runCatching { container.musicRepository.artistAlbums(artist.guid.value, 1) }
                .onSuccess {
                    albumState.snapshot = retainLoadedPage(albumState.snapshot, it) { album -> album.guid.value }
                }
                .onFailure {
                    albumState.snapshot = albumState.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = true,
                    )
                }
            albumState.loading = false
        }
    }
    LaunchedEffect(stateKey) { loadAlbums() }
    LaunchedEffect(albumSnapshot.initialLoadCompleted, albums, focusedSectionKey) {
        if (
            !albumSnapshot.initialLoadCompleted || albums.isEmpty() ||
            focusedSectionKey == "tracks" || initialAlbumFocusRequested
        ) {
            return@LaunchedEffect
        }
        val keys = albums.map { "album:${it.guid.value}" }
        focusedSectionKey = focusedSectionKey?.takeIf(keys::contains) ?: keys.first()
        yield()
        runCatching { albumFocus.requestFocus() }
        initialAlbumFocusRequested = true
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 64.dp, vertical = 32.dp)) {
            Text(artist.name, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            albumSnapshot.error?.let { InlineError(it) }
            if (albums.isNotEmpty()) {
                LazyRow(state = albumRowState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.guid.value }) { album ->
                        val focusKey = "album:${album.guid.value}"
                        AlbumLockup(
                            album.name,
                            album.artistName.orEmpty(),
                            album.coverId,
                            modifier = Modifier
                                .then(if (focusedSectionKey == focusKey) Modifier.focusRequester(albumFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) focusedSectionKey = focusKey },
                        ) {
                            focusedSectionKey = focusKey
                            onAlbum(album)
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            TrackCollection(
                container = container,
                stateKey = "$stateKey:tracks",
                title = "歌曲",
                loader = { container.musicRepository.artistTracks(artist.guid.value, it) },
                queueSource = { sort -> QueueSource.Artist(artist.guid.value, sort) },
                onPlayer = onPlayer,
                initialFocusEnabled = albumSnapshot.initialLoadCompleted &&
                    (albums.isEmpty() || focusedSectionKey == "tracks"),
                onFocusOwnerChanged = { focusedSectionKey = "tracks" },
            )
        }
    }
}

@Composable
private fun TrackCollection(
    container: AppContainer,
    stateKey: String,
    title: String,
    subtitle: String = "",
    coverId: String? = null,
    loader: suspend (Int) -> Page<Track>,
    queueSource: (String) -> QueueSource,
    onPlayer: (Track) -> Unit,
    initialFocusEnabled: Boolean = true,
    onFocusOwnerChanged: () -> Unit = {},
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
    LaunchedEffect(stateKey) {
        if (!retained.snapshot.initialLoadCompleted) {
            load(1)
        }
    }
    val playableTracks = tracks.filter(::isTrackPlayable)
    val playAllEnabled = playableTracks.isNotEmpty()
    LaunchedEffect(loading, tracks, focusedKey, initialFocusEnabled) {
        if (!initialFocusEnabled || loading || tracks.isEmpty() || initialFocusRequested) return@LaunchedEffect
        val availableKeys = buildList {
            if (playAllEnabled) add("play-all")
            addAll(playableTracks.map { it.guid.value })
        }
        focusedKey = focusedKey?.takeIf(availableKeys::contains) ?: availableKeys.firstOrNull()
        yield()
        if (focusedKey != null) {
            runCatching { restoredFocus.requestFocus() }
            initialFocusRequested = true
        }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 38.dp), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
        Column(Modifier.width(330.dp)) {
            if (coverId != null) RemoteArtwork(container, coverId, CoverVariant.Grid, Modifier.size(300.dp))
            Text(title, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = FnColors.Muted, fontSize = 21.sp)
            Spacer(Modifier.height(18.dp))
            Button(
                enabled = playAllEnabled,
                onClick = { play(tracks.indexOfFirst(::isTrackPlayable)) },
                modifier = Modifier
                    .then(if (focusedKey == "play-all") Modifier.focusRequester(restoredFocus) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedKey = "play-all"
                            onFocusOwnerChanged()
                        }
                    },
            ) {
                Text("播放全部")
            }
            error?.let { InlineError(it) }
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tracks, key = { it.guid.value }) { track ->
                val index = tracks.indexOf(track)
                val restoreModifier = if (focusedKey == track.guid.value) Modifier.focusRequester(restoredFocus) else Modifier
                TrackRow(
                    track,
                    enabled = isTrackPlayable(track),
                    modifier = restoreModifier.onFocusChanged { state ->
                        if (state.isFocused) {
                            focusedKey = track.guid.value
                            onFocusOwnerChanged()
                            if (hasNext && index >= tracks.size - 15) load(page + 1)
                        }
                    },
                ) {
                    play(tracks.indexOf(track))
                }
            }
            if (hasNext) item {
                Button(enabled = !loading, onClick = { load(page + 1) }, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                    Text(if (loading) "正在加载" else "加载更多")
                }
            }
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
            track.coverId?.let { RemoteArtwork(LocalAppContainer.current, it, CoverVariant.Compact, Modifier.size(52.dp)) }
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
            PlaylistTileArtwork(title, coverId, accent, Modifier.fillMaxWidth().height(108.dp))
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
private fun PlaylistTileArtwork(title: String, coverId: String?, accent: Color, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val shape = RectangleShape
    if (coverId != null) {
        RemoteArtwork(
            container = container,
            coverId = coverId,
            variant = CoverVariant.Grid,
            modifier = modifier,
            shape = shape,
            contentScale = ContentScale.Crop,
            placeholderContent = { GeometricArtworkPlaceholder(title, accent, Modifier.fillMaxSize(), shape) },
        )
    } else {
        GeometricArtworkPlaceholder(title, accent, modifier, shape)
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
    val shape = RoundedCornerShape(8.dp)
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
            if (coverId != null) {
                RemoteArtwork(
                    container = LocalAppContainer.current,
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
    val shape = RoundedCornerShape(8.dp)
    val artworkShape = RoundedCornerShape(4.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.size(width = 165.dp, height = 95.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = lockupButtonColors(),
        contentPadding = PaddingValues(7.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (coverId != null) {
                RemoteArtwork(
                    container = LocalAppContainer.current,
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
    container: AppContainer,
    coverId: String,
    variant: CoverVariant,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    contentScale: ContentScale = ContentScale.Fit,
    placeholderText: String? = null,
    placeholderContent: (@Composable () -> Unit)? = null,
) {
    val bitmap = rememberRemoteArtworkBitmap(container, coverId, variant)
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, modifier.clip(shape), contentScale = contentScale)
    } else {
        Box(modifier.background(FnColors.Surface, shape), contentAlignment = Alignment.Center) {
            when {
                placeholderContent != null -> placeholderContent()
                placeholderText != null -> Text(placeholderText, color = FnColors.Teal, fontSize = 78.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun rememberRemoteArtworkBitmap(container: AppContainer, coverId: String?, variant: CoverVariant): Bitmap? {
    val bitmap by produceState<Bitmap?>(null, container, coverId, variant) {
        value = coverId?.let { id ->
            val bytes = container.musicRepository.artwork(id, variant) ?: return@let null
            withContext(Dispatchers.Default) { decodeArtwork(bytes, variant.width ?: 1_200) }
        }
    }
    return if (coverId == null) null else bitmap
}

internal data class PlayerPresentationProjection(
    val playerStyle: PlayerStyle?,
    val metadata: NowPlayingResourceState<Track>,
    val artwork: NowPlayingResourceState<ByteArray>,
    val lyrics: NowPlayingResourceState<CurrentLyrics>,
) {
    val retryableFailure: AppError?
        get() = sequenceOf(metadata, artwork, lyrics)
            .filterIsInstance<NowPlayingResourceState.RetryableFailure>()
            .firstOrNull()
            ?.error

    val canRetry: Boolean get() = retryableFailure != null
}

internal fun projectPlayerPresentation(
    expectedIdentity: NowPlayingIdentity?,
    presentation: NowPlayingPresentation?,
): PlayerPresentationProjection {
    val current = presentation?.takeIf { expectedIdentity != null && it.identity == expectedIdentity }
    return if (current == null) {
        PlayerPresentationProjection(
            playerStyle = null,
            metadata = NowPlayingResourceState.Loading,
            artwork = NowPlayingResourceState.Loading,
            lyrics = NowPlayingResourceState.Loading,
        )
    } else {
        PlayerPresentationProjection(
            playerStyle = current.playerStyle,
            metadata = current.metadata,
            artwork = current.artwork,
            lyrics = current.lyrics,
        )
    }
}

internal data class PlayerArtworkKey(
    val namespace: String,
    val mediaId: String,
    val presentationRevision: Long,
    val playerStyle: PlayerStyle,
)

internal fun playerArtworkKey(identity: NowPlayingIdentity, playerStyle: PlayerStyle): PlayerArtworkKey =
    PlayerArtworkKey(
        namespace = identity.namespace,
        mediaId = identity.mediaId,
        presentationRevision = identity.presentationRevision,
        playerStyle = playerStyle,
    )

private data class PlayerArtworkDecodeRequest(
    val key: PlayerArtworkKey,
    val bytes: ByteArray,
    val targetLongEdge: Int,
)

private data class DecodedPlayerArtwork(
    val key: PlayerArtworkKey,
    val sourceBytes: ByteArray,
    val bitmap: Bitmap?,
)

@Composable
private fun rememberCurrentArtworkBitmap(
    identity: NowPlayingIdentity?,
    playerStyle: PlayerStyle,
    presentation: PlayerPresentationProjection,
): Bitmap? {
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
    val decoded by produceState<DecodedPlayerArtwork?>(null, request?.key, request?.bytes) {
        value = null
        val currentRequest = request ?: return@produceState
        val bitmap = withContext(Dispatchers.Default) {
            decodeArtwork(currentRequest.bytes, currentRequest.targetLongEdge)
        }
        value = DecodedPlayerArtwork(currentRequest.key, currentRequest.bytes, bitmap)
    }
    return decoded?.takeIf { result ->
        request != null && result.key == request.key && result.sourceBytes === request.bytes
    }?.bitmap
}

@Composable
private fun ImmersivePlayer(
    container: AppContainer,
    playback: PlaybackUiState,
    onExitRoam: () -> Unit,
) {
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val nowPlayingPresentation by container.nowPlayingPresenter.state.collectAsStateWithLifecycle()
    val presentation = projectPlayerPresentation(playback.nowPlayingIdentity, nowPlayingPresentation)
    val metadata = when (val state = presentation.metadata) {
        is NowPlayingResourceState.Ready -> state.value
        else -> null
    }
    val currentLyrics = when (val state = presentation.lyrics) {
        is NowPlayingResourceState.Ready -> state.value
        else -> null
    }
    val timeline = currentLyrics?.timeline
    val staticLyric = currentLyrics?.document?.takeUnless { it.isLrc }?.content
    val lyricsLoading = presentation.lyrics is NowPlayingResourceState.Loading
    val lyricsFailed = presentation.lyrics is NowPlayingResourceState.RetryableFailure
    var controlsVisible by remember { mutableStateOf(true) }
    var queueVisible by remember { mutableStateOf(false) }
    var restoreQueueFocus by remember { mutableStateOf(false) }
    var interactionEpoch by remember { mutableStateOf(0) }
    val playerFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val previousFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val modeFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val exitRoamFocus = remember { FocusRequester() }
    val statusRetryFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val roaming = playback.queueKind == QueueKind.Roam
    fun revealControls() {
        controlsVisible = true
        interactionEpoch++
    }
    val active = timeline?.activeIndex(playback.positionMs) ?: -1
    val lyricLines = timeline?.lines.orEmpty()
    val title = metadata?.title?.takeUnless(String::isBlank)
        ?: playback.title.takeUnless(String::isBlank)
        ?: "尚未选择歌曲"
    val artist = metadata?.artistName?.takeUnless(String::isBlank)
        ?: playback.artist
    val audioFormat = metadata?.audioFormat?.takeUnless(String::isBlank)
        ?: playback.audioFormat
    val poster = preferences.playerStyle == PlayerStyle.Poster
    val artworkBitmap = rememberCurrentArtworkBitmap(
        identity = playback.nowPlayingIdentity,
        playerStyle = preferences.playerStyle,
        presentation = presentation,
    )
    val ambienceColor = remember(artworkBitmap, title, artist) {
        val samples = artworkBitmap?.let(::sampleArtworkPixels) ?: IntArray(0)
        dominantArtworkColor(samples, "$title|$artist")
    }
    val posterPanelColor = remember(ambienceColor) { posterSurfaceColor(ambienceColor) }
    val previousEnabled = playback.canPrevious && !playback.roamBusy
    val nextEnabled = playback.canNext && !playback.roamBusy
    val statusRetryAvailable = playerStatus(
        roamError = playback.roamError,
        canRetryRoam = playback.canRetryRoam,
        queueError = playback.queueError,
        canRetryQueue = playback.canRetryQueue,
        presentationError = presentation.retryableFailure,
        canRetryPresentation = presentation.canRetry,
        playbackError = playback.error,
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
            restoreQueueFocus -> Unit
            controlsVisible -> playFocus.requestFocus()
            else -> playerFocus.requestFocus()
        }
    }
    LaunchedEffect(queueVisible, restoreQueueFocus) {
        if (!queueVisible && restoreQueueFocus) {
            yield()
            queueFocus.requestFocus()
            restoreQueueFocus = false
        }
    }
    LaunchedEffect(roaming) {
        if (!roaming && controlsVisible) playFocus.requestFocus()
    }
    BackHandler(queueVisible) {
        queueVisible = false
        controlsVisible = true
        interactionEpoch++
        restoreQueueFocus = true
    }
    BackHandler(controlsVisible && !queueVisible) {
        controlsVisible = false
        playerFocus.requestFocus()
    }
    Box(
        Modifier.fillMaxSize()
            .background(FnColors.Background)
            .focusRequester(playerFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || controlsVisible) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.DirectionCenter -> {
                        container.playbackController.playPause()
                        revealControls()
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        revealControls()
                        true
                    }
                    else -> false
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
            artworkBitmap = artworkBitmap,
            placeholderAccent = ambienceColor,
            posterPanelColor = posterPanelColor,
            title = title,
            artist = artist,
            audioFormat = audioFormat,
            isPlaying = playback.isPlaying,
            lyricLines = lyricLines,
            activeLyricIndex = active,
            staticLyric = staticLyric,
            lyricsLoading = lyricsLoading,
            lyricsFailed = lyricsFailed,
            playbackError = playback.error,
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
            PlayerControlOverlay(
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                isPlaying = playback.isPlaying,
                roaming = roaming,
                previousEnabled = previousEnabled,
                nextEnabled = nextEnabled,
                progressFocus = progressFocus,
                previousFocus = previousFocus,
                playFocus = playFocus,
                nextFocus = nextFocus,
                modeFocus = modeFocus,
                queueFocus = queueFocus,
                exitRoamFocus = exitRoamFocus,
                statusRetryFocus = statusRetryFocus,
                statusRetryAvailable = statusRetryAvailable,
                playMode = playback.playMode,
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
        if (queueVisible) {
            PlaybackQueueOverlay(
                items = playback.queueItems,
                loadedCount = playback.loadedPlayableCount,
                queueError = playback.queueError,
                canRetry = playback.canRetryQueue,
                onRetry = container.playbackController::retryQueuePage,
                onSelect = container.playbackController::selectQueueItem,
                onInteraction = ::revealControls,
            )
        }
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
            Color.Black.copy(alpha = 0.06f),
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.49f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.51f, size.height),
        )
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
    artworkBitmap: Bitmap?,
    placeholderAccent: Color,
    posterPanelColor: Color,
    title: String,
    artist: String,
    audioFormat: String,
    isPlaying: Boolean,
    lyricLines: List<com.fnmusic.tv.core.model.lyric.LyricLine>,
    activeLyricIndex: Int,
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
                    .background(posterPanelColor),
            )
            PlayerDetails(
                title = title,
                artist = artist,
                audioFormat = audioFormat,
                lyricLines = lyricLines,
                activeLyricIndex = activeLyricIndex,
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
                    .padding(start = 10.dp, end = 40.dp, top = 64.dp, bottom = 132.dp),
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
                lyricLines = lyricLines,
                activeLyricIndex = activeLyricIndex,
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
                    .padding(start = 26.dp, end = 44.dp, top = 30.dp, bottom = 132.dp),
            )
        }
    }
}

internal enum class PlayerStatusRetry {
    Roam,
    Queue,
    Presentation,
}

internal data class PlayerStatus(
    val message: String,
    val retry: PlayerStatusRetry?,
)

internal fun playerStatus(
    roamError: AppError?,
    canRetryRoam: Boolean,
    queueError: String?,
    canRetryQueue: Boolean,
    presentationError: AppError?,
    canRetryPresentation: Boolean,
    playbackError: String?,
): PlayerStatus? = when {
    roamError != null -> PlayerStatus(
        message = appErrorMessage(roamError),
        retry = PlayerStatusRetry.Roam.takeIf { canRetryRoam },
    )
    queueError != null -> PlayerStatus(
        message = queueError,
        retry = PlayerStatusRetry.Queue.takeIf { canRetryQueue },
    )
    presentationError != null -> PlayerStatus(
        message = "当前歌曲资源：${appErrorMessage(presentationError)}",
        retry = PlayerStatusRetry.Presentation.takeIf { canRetryPresentation },
    )
    playbackError != null -> PlayerStatus(message = "播放失败：$playbackError", retry = null)
    else -> null
}

@Composable
private fun PlayerDetails(
    title: String,
    artist: String,
    audioFormat: String,
    lyricLines: List<com.fnmusic.tv.core.model.lyric.LyricLine>,
    activeLyricIndex: Int,
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
        if (poster) {
            PosterLyrics(lyricLines, activeLyricIndex, staticLyric, lyricsLoading, lyricsFailed)
        } else {
            PlayerLyrics(lyricLines, activeLyricIndex, staticLyric, lyricsLoading, lyricsFailed)
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
private fun GeometricArtworkPlaceholder(
    text: String,
    accent: Color,
    modifier: Modifier,
    shape: Shape,
) {
    Box(modifier.clip(shape).background(Color(0xFF19201F))) {
        Canvas(Modifier.fillMaxSize()) {
            val shortEdge = minOf(size.width, size.height)
            val sleeve = Path().apply {
                moveTo(size.width * 0.04f, size.height * 0.12f)
                lineTo(size.width * 0.52f, size.height * 0.02f)
                lineTo(size.width * 0.46f, size.height * 0.9f)
                lineTo(size.width * 0.02f, size.height * 0.8f)
                close()
            }
            drawPath(sleeve, accent.copy(alpha = 0.72f))
            drawRect(
                color = FnColors.Coral.copy(alpha = 0.9f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.84f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.16f, size.height),
            )
            drawLine(
                FnColors.Warning,
                androidx.compose.ui.geometry.Offset(size.width * 0.39f, size.height * 0.12f),
                androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.31f),
                strokeWidth = shortEdge * 0.035f,
                cap = StrokeCap.Round,
            )
            val center = androidx.compose.ui.geometry.Offset(size.width * 0.69f, size.height * 0.57f)
            val radius = shortEdge * 0.32f
            drawCircle(Color(0xFF101313), radius, center)
            for (ring in 1..4) {
                drawCircle(
                    Color.White.copy(alpha = 0.06f + ring * 0.015f),
                    radius * (0.48f + ring * 0.11f),
                    center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(FnColors.Warning, radius * 0.34f, center)
            drawCircle(Color(0xFF19201F), radius * 0.07f, center)
            drawLine(
                FnColors.Text.copy(alpha = 0.72f),
                androidx.compose.ui.geometry.Offset(size.width * 0.08f, size.height * 0.87f),
                androidx.compose.ui.geometry.Offset(size.width * 0.44f, size.height * 0.8f),
                strokeWidth = shortEdge * 0.018f,
                cap = StrokeCap.Round,
            )
        }
        Text(
            text.take(1).ifBlank { "音" },
            color = FnColors.Text.copy(alpha = 0.9f),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp),
        )
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
private fun PosterLyrics(
    lyricLines: List<com.fnmusic.tv.core.model.lyric.LyricLine>,
    activeIndex: Int,
    staticLyric: String?,
    loading: Boolean,
    failed: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(286.dp), contentAlignment = Alignment.TopStart) {
        when {
            lyricLines.isNotEmpty() -> AnimatedContent(
                targetState = activeIndex,
                transitionSpec = {
                    val forward = targetState >= initialState
                    val enterOffset: (Int) -> Int = { height -> if (forward) height / 10 else -height / 10 }
                    val exitOffset: (Int) -> Int = { height -> if (forward) -height / 10 else height / 10 }
                    (fadeIn(tween(220)) + slideInVertically(tween(220), enterOffset)) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(180), exitOffset))
                },
                label = "poster lyrics",
            ) { animatedActiveIndex ->
                PosterLyricSlots(lyricLines, animatedActiveIndex)
            }
            !staticLyric.isNullOrBlank() -> Text(
                staticLyric,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                maxLines = 8,
                overflow = TextOverflow.Clip,
            )
            loading -> Text("歌词加载中", color = FnColors.Text.copy(alpha = 0.4f), fontSize = 20.sp)
            failed -> Text("歌词暂时无法加载", color = FnColors.Text.copy(alpha = 0.4f), fontSize = 20.sp)
            else -> Text("纯音乐或暂无歌词", color = FnColors.Text.copy(alpha = 0.4f), fontSize = 20.sp)
        }
    }
}

@Composable
private fun PosterLyricSlots(
    lyricLines: List<com.fnmusic.tv.core.model.lyric.LyricLine>,
    activeIndex: Int,
) {
    val indices = posterLyricIndices(lyricLines.size, activeIndex)
    val topOffsets = listOf(16.dp, 64.dp, 162.dp, 242.dp)
    val heights = listOf(44.dp, 84.dp, 52.dp, 44.dp)
    Box(Modifier.fillMaxSize()) {
        indices.forEachIndexed { slot, index ->
            if (index != null) {
                val current = index == activeIndex
                val alpha = when {
                    current -> 1f
                    activeIndex < 0 -> 0.16f
                    slot == 2 -> 0.38f
                    else -> 0.12f
                }
                PosterLyricGroup(
                    texts = lyricLines[index].texts,
                    current = current,
                    alpha = alpha,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopStart)
                        .padding(top = topOffsets[slot]).height(heights[slot]),
                )
            }
        }
    }
}

@Composable
private fun PosterLyricGroup(
    texts: List<String>,
    current: Boolean,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clipToBounds(), contentAlignment = Alignment.CenterStart) {
        Text(
            posterLyricTexts(texts).joinToString("\n"),
            color = FnColors.Text.copy(alpha = alpha),
            fontSize = if (current) 24.sp else 18.sp,
            lineHeight = if (current) 28.sp else 22.sp,
            fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = if (current) 3 else 2,
            overflow = TextOverflow.Clip,
            softWrap = true,
        )
    }
}

@Composable
private fun PlayerLyrics(
    lyricLines: List<com.fnmusic.tv.core.model.lyric.LyricLine>,
    activeIndex: Int,
    staticLyric: String?,
    loading: Boolean,
    failed: Boolean,
) {
    Box(Modifier.fillMaxWidth().height(256.dp), contentAlignment = Alignment.CenterStart) {
        when {
            lyricLines.isNotEmpty() -> {
                val window = playerLyricWindow(lyricLines.size, activeIndex)
                val hasCurrentLine = activeIndex in window
                Column(Modifier.fillMaxSize()) {
                    window.forEach { index ->
                        val distance = if (activeIndex >= 0) kotlin.math.abs(index - activeIndex) else index + 1
                        val current = index == activeIndex
                        val slotHeight = when {
                            !hasCurrentLine -> 64.dp
                            current -> 112.dp
                            else -> 48.dp
                        }
                        val color = when {
                            current -> FnColors.Text
                            distance == 1 -> FnColors.Text.copy(alpha = 0.56f)
                            else -> FnColors.Text.copy(alpha = 0.28f)
                        }
                        Box(Modifier.fillMaxWidth().height(slotHeight), contentAlignment = Alignment.CenterStart) {
                            Text(
                                lyricLines[index].texts.joinToString("\n"),
                                color = color,
                                fontSize = if (current) 26.sp else 18.sp,
                                lineHeight = if (current) 28.sp else 22.sp,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                                maxLines = if (current) 4 else 2,
                                overflow = TextOverflow.Clip,
                                softWrap = true,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
            !staticLyric.isNullOrBlank() -> Text(staticLyric, fontSize = 24.sp, lineHeight = 31.sp, maxLines = 8, overflow = TextOverflow.Clip)
            loading -> Text("歌词加载中", color = FnColors.Muted, fontSize = 26.sp)
            failed -> Text("歌词暂时无法加载", color = FnColors.Muted, fontSize = 26.sp)
            else -> Text("纯音乐或暂无歌词", color = FnColors.Muted, fontSize = 26.sp)
        }
    }
}

@Composable
internal fun PlaybackQueueOverlay(
    items: List<PlaybackQueueItem>,
    loadedCount: Int,
    queueError: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onSelect: (Int) -> Unit,
    onInteraction: () -> Unit,
) {
    val listState = rememberLazyListState()
    var focusedRowKey by remember { mutableStateOf<String?>(null) }
    val requesterKeys = remember(items.map(PlaybackQueueItem::mediaId)) {
        val occurrences = mutableMapOf<String, Int>()
        items.map { item ->
            val occurrence = occurrences[item.mediaId] ?: 0
            occurrences[item.mediaId] = occurrence + 1
            "${item.mediaId}:$occurrence"
        }
    }
    val targetKey = queueFocusTargetKey(
        requesterKeys = requesterKeys,
        currentIndex = initialQueueFocusIndex(items),
        previouslyFocusedKey = focusedRowKey,
    )
    LaunchedEffect(targetKey, requesterKeys) {
        val targetIndex = targetKey?.let(requesterKeys::indexOf) ?: -1
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f))) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(0.43f).align(Alignment.CenterEnd)
                .background(Color(0xF2161C1A)).padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text("当前播放 ($loadedCount)", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(
                    items = items,
                    key = { index, _ -> requesterKeys[index] },
                ) { index, item ->
                    val rowKey = requesterKeys[index]
                    val requester = remember(rowKey) { FocusRequester() }
                    LaunchedEffect(targetKey, rowKey) {
                        if (targetKey == rowKey) requester.requestFocus()
                    }
                    Button(
                        onClick = {
                            onInteraction()
                            onSelect(item.queueIndex)
                        },
                        modifier = Modifier.fillMaxWidth().height(58.dp)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .focusRequester(requester)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focusedRowKey = rowKey
                                    onInteraction()
                                }
                            }
                            .semantics {
                                contentDescription = "${index + 1}. ${item.title} ${item.artist}" +
                                    if (item.isCurrent) "，正在播放" else ""
                            },
                        scale = ButtonDefaults.scale(focusedScale = 1.015f),
                        colors = ButtonDefaults.colors(
                            containerColor = if (item.isCurrent) Color(0xFF2E3835) else Color.Transparent,
                            contentColor = FnColors.Text,
                            focusedContainerColor = Color(0xFF3A4541),
                            focusedContentColor = FnColors.Text,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", color = FnColors.Muted, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title.ifBlank { "未知歌曲" }, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.artist.ifBlank { "未知演唱者" }, color = FnColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (item.isCurrent) {
                                Text("正在播放", color = FnColors.Coral, fontSize = 11.sp, maxLines = 1)
                            }
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
                        ) { Text("队列加载失败，重试", maxLines = 1) }
                    }
                }
            }
        }
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
    modeFocus: FocusRequester,
    queueFocus: FocusRequester,
    exitRoamFocus: FocusRequester,
    statusRetryFocus: FocusRequester,
    statusRetryAvailable: Boolean,
    playMode: PlayMode,
    queueCount: Int,
    onInteraction: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onOpenQueue: () -> Unit,
    onExitRoam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var progressFocused by remember { mutableStateOf(false) }
    val fraction = playerProgressFraction(positionMs, durationMs)
    Column(
        modifier.fillMaxWidth().height(94.dp).background(Color(0xF20C1110))
            .padding(start = 47.dp, top = 14.dp, end = 47.dp, bottom = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(positionMs), color = FnColors.Muted, fontSize = 9.sp, modifier = Modifier.width(29.dp))
            Canvas(
                Modifier.weight(1f).height(14.dp)
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
                    .focusable()
                    .background(if (progressFocused) Color(0xFF242B29) else Color.Transparent, RoundedCornerShape(3.dp))
                    .graphicsLayer {
                        scaleX = if (progressFocused) 1.04f else 1f
                        scaleY = if (progressFocused) 1.04f else 1f
                    }
                    .semantics { contentDescription = "播放进度 ${formatDuration(positionMs)} / ${formatDuration(durationMs)}" },
            ) {
                val centerY = size.height / 2f
                val playedX = size.width * fraction
                drawLine(Color(0xFF4A504E), androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(size.width, centerY), 2.dp.toPx(), StrokeCap.Round)
                drawLine(FnColors.Coral, androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(playedX, centerY), 2.dp.toPx(), StrokeCap.Round)
                if (progressFocused) drawCircle(FnColors.Coral.copy(alpha = 0.2f), 8.dp.toPx(), androidx.compose.ui.geometry.Offset(playedX, centerY))
                drawCircle(if (progressFocused) Color.White else FnColors.Text, if (progressFocused) 5.dp.toPx() else 3.5.dp.toPx(), androidx.compose.ui.geometry.Offset(playedX, centerY))
            }
            Text(formatDuration(durationMs), color = FnColors.Muted, fontSize = 9.sp, modifier = Modifier.width(29.dp), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (!roaming) {
                PlayerSideActionButton(
                    glyph = playModeGlyph(playMode),
                    description = "播放模式：${playModeLabel(playMode)}",
                    focusRequester = modeFocus,
                    upFocus = progressFocus,
                    rightFocus = if (previousEnabled) previousFocus else playFocus,
                    onFocus = onInteraction,
                    onClick = {
                        onInteraction()
                        onCyclePlayMode()
                    },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
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
                    leftFocus = modeFocus.takeIf { !roaming },
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
                        !roaming -> modeFocus
                        else -> null
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
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(width = if (glyph == null) 104.dp else 36.dp, height = 29.dp)
            .focusProperties {
                up = upFocus
                down = FocusRequester.Cancel
                left = leftFocus ?: FocusRequester.Cancel
                right = rightFocus ?: FocusRequester.Cancel
            }
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .semantics { contentDescription = description },
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = if (emphasized) Color(0xFF382A27) else Color(0xFF202624),
            contentColor = if (emphasized) Color(0xFFF0D9D1) else FnColors.Text,
            focusedContainerColor = if (emphasized) FnColors.Coral else Color(0xFF303734),
            focusedContentColor = if (emphasized) FnColors.Background else FnColors.Text,
        ),
        contentPadding = PaddingValues(if (glyph == null) 8.dp else 0.dp),
    ) {
        if (glyph != null) {
            PlayerSideActionIcon(glyph)
        } else {
            Text(label.orEmpty(), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class PlayerSideActionGlyph { RepeatAll, Shuffle, RepeatOne, Sequence, Queue }

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
    Button(
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
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = if (emphasized) FnColors.Text else Color.Transparent,
            contentColor = if (emphasized) FnColors.Background else FnColors.Text,
            focusedContainerColor = if (emphasized) FnColors.Coral else Color(0xFF303734),
            focusedContentColor = if (emphasized) FnColors.Background else FnColors.Text,
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

private data class ArtworkColorBucket(
    var count: Int = 0,
    var red: Long = 0,
    var green: Long = 0,
    var blue: Long = 0,
)

private fun sampleArtworkPixels(bitmap: Bitmap): IntArray {
    if (bitmap.width <= 0 || bitmap.height <= 0) return IntArray(0)
    return runCatching {
        IntArray(81) { index ->
            val x = (index % 9) * (bitmap.width - 1) / 8
            val y = (index / 9) * (bitmap.height - 1) / 8
            bitmap[x, y]
        }
    }.getOrDefault(IntArray(0))
}

internal fun dominantArtworkColor(pixels: IntArray, fallbackKey: String): Color {
    fun collectBuckets(skipExtremes: Boolean): LinkedHashMap<Int, ArtworkColorBucket> {
        val buckets = linkedMapOf<Int, ArtworkColorBucket>()
        pixels.forEach { pixel ->
            val alpha = pixel ushr 24 and 0xFF
            if (alpha >= 64) {
                val red = pixel ushr 16 and 0xFF
                val green = pixel ushr 8 and 0xFF
                val blue = pixel and 0xFF
                val nearBlack = maxOf(red, green, blue) < 28
                val nearWhite = minOf(red, green, blue) > 230
                if (!skipExtremes || (!nearBlack && !nearWhite)) {
                    val key = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
                    buckets.getOrPut(key, ::ArtworkColorBucket).apply {
                        count++
                        this.red += red.toLong()
                        this.green += green.toLong()
                        this.blue += blue.toLong()
                    }
                }
            }
        }
        return buckets
    }
    val filteredBuckets = collectBuckets(skipExtremes = true)
    val buckets = filteredBuckets.ifEmpty { collectBuckets(skipExtremes = false) }
    val dominant = buckets.values.maxByOrNull { it.count }
        ?: return fallbackAmbienceColor(fallbackKey)
    val red = dominant.red.toFloat() / dominant.count / 255f
    val green = dominant.green.toFloat() / dominant.count / 255f
    val blue = dominant.blue.toFloat() / dominant.count / 255f
    if (maxOf(red, green, blue) - minOf(red, green, blue) < 0.04f) return fallbackAmbienceColor(fallbackKey)
    return normalizedAmbienceColor(red, green, blue)
}

internal fun fallbackAmbienceColor(key: String): Color {
    val hue = Math.floorMod(key.hashCode(), 360).toFloat()
    return hsvColor(hue, saturation = 0.38f, value = 0.34f)
}

private fun normalizedAmbienceColor(red: Float, green: Float, blue: Float): Color {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta) % 6f)
        max == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val sourceSaturation = if (max == 0f) 0f else delta / max
    val saturation = (sourceSaturation * 0.68f).coerceIn(0.2f, 0.5f)
    return hsvColor(hue, saturation, value = 0.34f)
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
    val peak = maxOf(color.red, color.green, color.blue)
    if (peak <= 0f) return color
    val scale = 0.44f / peak
    return Color(
        red = (color.red * scale).coerceIn(0f, 1f),
        green = (color.green * scale).coerceIn(0f, 1f),
        blue = (color.blue * scale).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

internal fun posterLyricTexts(texts: List<String>): List<String> =
    texts.map(String::trim).filter(String::isNotEmpty).distinct().take(2)

internal fun posterLyricIndices(lineCount: Int, activeIndex: Int): List<Int?> {
    if (lineCount <= 0) return List(4) { null }
    if (activeIndex < 0) return listOf(null, 0, 1, 2).map { it?.takeIf { index -> index < lineCount } }
    return listOf(-1, 0, 1, 2).map { offset ->
        (activeIndex + offset).takeIf { it in 0 until lineCount }
    }
}

internal fun playerLyricWindow(lineCount: Int, activeIndex: Int, visibleCount: Int = 4): IntRange {
    if (lineCount <= 0 || visibleCount <= 0) return IntRange.EMPTY
    val count = minOf(lineCount, visibleCount)
    val safeActive = activeIndex.coerceIn(0, lineCount - 1)
    val start = (safeActive - 1).coerceIn(0, lineCount - count)
    return start until start + count
}

internal fun playerProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

@Composable
private fun SettingsScreen(container: AppContainer) {
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val scope = LocalLibraryRetainedState.current.scope
    val coverStyleFocus = remember { FocusRequester() }
    val posterStyleFocus = remember { FocusRequester() }
    var usage by remember { mutableStateOf(CacheUsage(artworkBytes = 0, indexBytes = 0)) }
    suspend fun refreshUsage() {
        usage = container.musicRepository.cacheUsage()
    }
    LaunchedEffect(Unit) {
        container.musicRepository.applyArtworkBudget()
        refreshUsage()
    }
    LaunchedEffect(Unit) {
        yield()
        runCatching { coverStyleFocus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(72.dp, 50.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("设置", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("播放界面", fontSize = 25.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = { container.appPreferences.setPlayerStyle(com.fnmusic.tv.core.model.PlayerStyle.Cover) },
                modifier = Modifier.focusProperties { right = posterStyleFocus }.focusRequester(coverStyleFocus),
            ) {
                Text(if (preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Cover) "CD 模式 · 已选" else "CD 模式")
            }
            Button(
                onClick = { container.appPreferences.setPlayerStyle(com.fnmusic.tv.core.model.PlayerStyle.Poster) },
                modifier = Modifier.focusProperties { left = coverStyleFocus }.focusRequester(posterStyleFocus),
            ) {
                Text(if (preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Poster) "大海报模式 · 已选" else "大海报模式")
            }
        }
        Text("图片磁盘缓存上限", fontSize = 25.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CacheBudget.entries.forEach { budget ->
                Button(onClick = {
                    scope.launch {
                        container.appPreferences.setCacheBudget(budget)
                        container.musicRepository.applyArtworkBudget()
                        refreshUsage()
                    }
                }) {
                    Text(if (preferences.cacheBudget == budget) "${budget.megabytes} MB · 已选" else "${budget.megabytes} MB")
                }
            }
        }
        Text(
            "当前 ${formatBytes(usage.totalBytes)}（图片 ${formatBytes(usage.artworkBytes)} / 资料 ${formatBytes(usage.indexBytes)}）",
            color = FnColors.Muted,
            fontSize = 18.sp,
        )
        Text(
            "图片额度立即生效；资料缓存独立遵守 24 MB 目标、32 MB 物理上限",
            color = FnColors.Muted,
            fontSize = 18.sp,
        )
        Button(onClick = {
            scope.launch {
                container.musicRepository.clearAllEvictableCaches()
                container.nowPlayingPresenter.refreshCurrentPresentation()
                refreshUsage()
            }
        }) { Text("清除图片和资料缓存") }
    }
}

@Composable
private fun InlineError(error: AppError) {
    Text(appErrorMessage(error), color = FnColors.Coral, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
}

private fun appErrorMessage(error: AppError): String = when (error) {
    AppError.NetworkUnavailable -> "NAS 暂时不可用"
    AppError.Empty -> "暂无内容"
    AppError.UnavailableTrack -> "歌曲不可访问"
    AppError.TranscodeUnavailable -> "兼容播放参数尚未确认"
    AppError.CollectionChanged -> "列表已更新，请返回后重新载入"
    else -> "加载失败，请重试"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f KB".format(bytes / 1024.0)
}

private fun decodeArtwork(bytes: ByteArray, targetLongEdge: Int): android.graphics.Bitmap? {
    if (bytes.size > 20 * 1024 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8_192 || bounds.outHeight > 8_192) return null
    if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) return null
    var sample = 1
    while (
        bounds.outWidth / sample > targetLongEdge ||
        bounds.outHeight / sample > targetLongEdge ||
        (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 16_000_000L
    ) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
