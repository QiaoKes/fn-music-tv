package com.fnmusic.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.fnmusic.tv.AppContainer
import com.fnmusic.tv.core.data.repository.SessionState
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.SharedLibrary
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.lyric.LyricTimeline
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.playback.boundedQueueWindow
import com.fnmusic.tv.core.model.preferences.CacheBudget
import com.fnmusic.tv.core.model.preferences.CacheUsage
import com.fnmusic.tv.core.playback.PlaybackUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    data class Player(val track: Track?, val roam: Boolean = false) : LibraryRoute
    data object Settings : LibraryRoute
}

private val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("Missing AppContainer") }

@Composable
internal fun AuthenticatedApp(
    container: AppContainer,
    session: SessionState.SignedIn,
    playback: PlaybackUiState,
) {
    var stack by remember(session.user.guid) { mutableStateOf(listOf<LibraryRoute>(LibraryRoute.Home)) }
    var roamWindow by remember { mutableStateOf<RoamWindow?>(null) }
    val route = stack.last()
    val stateHolder = rememberSaveableStateHolder()
    val open: (LibraryRoute) -> Unit = { stack = stack + it }
    val root: (LibraryRoute) -> Unit = { stack = listOf(it) }
    BackHandler(stack.size > 1) { stack = stack.dropLast(1) }
    LaunchedEffect(playback.error) {
        if (playback.error?.contains("BAD_HTTP_STATUS") == true && !container.sessionRepository.verifyCurrentSession()) {
            container.playbackController.clearSession()
            container.musicRepository.clearArtwork()
        }
    }

    stateHolder.SaveableStateProvider(route.storageKey()) {
        CompositionLocalProvider(LocalAppContainer provides container) {
            when (route) {
        LibraryRoute.Home -> BrowseHome(
            container,
            playback,
            onMy = { root(LibraryRoute.My) },
            onPlaylist = { open(LibraryRoute.PlaylistDetail(it)) },
            onAll = { open(LibraryRoute.AllPlaylists) },
            onPlayer = { open(LibraryRoute.Player(null, roamWindow != null)) },
            activeRoam = roamWindow,
            onResumeRoam = { open(LibraryRoute.Player(it.current.track, true)) },
            onRoam = { window, prepared ->
                roamWindow = window
                container.playbackController.enterRoam(prepared)
                open(LibraryRoute.Player(window.current.track, true))
            },
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
            onPlayer = { open(LibraryRoute.Player(null, roamWindow != null)) },
        )
        LibraryRoute.AllPlaylists -> AllPlaylists(container, onOpen = { open(LibraryRoute.PlaylistDetail(it)) })
        is LibraryRoute.PlaylistDetail -> TrackCollection(
            container = container,
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
            route.track,
            route.roam,
            roamWindow,
            onRoamChanged = { roamWindow = it },
            onExitRoam = {
                roamWindow = null
                stack = if (container.playbackController.exitRoam()) {
                    stack.dropLast(1) + LibraryRoute.Player(null, false)
                } else {
                    listOf(LibraryRoute.Home)
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
) {
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playback.hasMedia) {
            NowPlayingPill(playback, onPlayer)
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
private fun NowPlayingPill(playback: PlaybackUiState, onClick: () -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    val coverId = remember(playback.artworkUrl) {
        playback.artworkUrl?.let { runCatching { it.toUri().getQueryParameter("coverId") }.getOrNull() }
    }
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 186.dp, height = 37.dp),
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
    activeRoam: RoamWindow?,
    onResumeRoam: (RoamWindow) -> Unit,
    onRoam: (RoamWindow, PlaybackTrack) -> Unit,
) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var roaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { container.musicRepository.playlists() }
            .onSuccess { playlists = it }
            .onFailure { error = (it as? AppException)?.error ?: AppError.Unknown() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 38.dp)) {
        LibraryTopBar(playback, true, {}, onMy, onPlayer)
        Spacer(Modifier.height(38.dp))
        Text("听点什么", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(22.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                PlaylistTile("随机漫游", if (roaming) "正在准备" else "从曲库里遇见下一首", null, FnColors.Teal, enabled = !roaming) {
                    if (activeRoam != null) {
                        onResumeRoam(activeRoam)
                        return@PlaylistTile
                    }
                    roaming = true
                    scope.launch {
                        runCatching {
                            val window = container.musicRepository.startRoam() ?: throw AppException(AppError.Empty)
                            window to container.musicRepository.prepare(window.current.track)
                        }.onSuccess { (window, prepared) -> onRoam(window, prepared) }
                            .onFailure { error = (it as? AppException)?.error ?: AppError.Unknown() }
                        roaming = false
                    }
                }
            }
            items(playlists.take(12), key = { it.guid.value }) { playlist ->
                PlaylistTile(playlist.name, "歌单", playlist.coverId, FnColors.Coral) { onPlaylist(playlist) }
            }
            item { PlaylistTile("全部歌单", "浏览完整列表", null, FnColors.Muted, onClick = onAll) }
        }
        error?.let { InlineError(it) }
    }
}

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
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        launch { runCatching { container.musicRepository.artists(1).items }.onSuccess { artists = it } }
        launch { runCatching { container.musicRepository.albums(1).items }.onSuccess { albums = it } }
        launch { runCatching { container.musicRepository.sharedLibraries() }.onSuccess { libraries = it } }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 38.dp)) {
        LibraryTopBar(playback, false, onHome, {}, onPlayer)
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
                Button(onClick = onSettings) { Text("设置") }
                Button(onClick = {
                    container.playbackController.clearSession()
                    scope.launch {
                        container.musicRepository.clearArtwork()
                        container.musicRepository.clearLocalNamespace(includeEssential = true)
                        container.sessionRepository.logout()
                    }
                }) { Text("切换账号") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                MediaBand(
                    "歌手",
                    artists.take(8).map {
                        BandEntry(it.name, "${it.trackCount ?: 0} 首歌曲", it.coverId, BandKind.Artist) { onArtist(it) }
                    },
                    BandEntry("全部歌手", "浏览完整列表", null, BandKind.Artist, onArtists),
                )
            }
            item {
                MediaBand(
                    "专辑",
                    albums.take(8).map {
                        BandEntry(it.name, it.artistName.orEmpty(), it.coverId, BandKind.Album) { onAlbum(it) }
                    },
                    BandEntry("全部专辑", "浏览完整列表", null, BandKind.Album, onAlbums),
                )
            }
            item {
                val libraryEntries = listOf(BandEntry("全部歌曲", "完整曲库", null, BandKind.Library, onAllTracks)) + libraries.map {
                    BandEntry(
                        it.name,
                        if (it.accessStatus == 0) "可访问" else "暂不可用",
                        null,
                        BandKind.Library,
                        if (it.accessStatus == 0) onAllTracks else null,
                    )
                }
                MediaBand(
                    "音乐库",
                    libraryEntries,
                    BandEntry("全部音乐库", "浏览完整列表", null, BandKind.Library, onAllTracks),
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
    val action: (() -> Unit)?,
)

@Composable
private fun MediaBand(title: String, entries: List<BandEntry>, terminalEntry: BandEntry) {
    Column {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries) { entry ->
                BandLockup(entry)
            }
            item { BandLockup(terminalEntry) }
        }
    }
}

@Composable
private fun BandLockup(entry: BandEntry) {
    when (entry.kind) {
        BandKind.Artist -> ArtistLockup(entry.title, entry.subtitle, entry.coverId, entry.action != null) { entry.action?.invoke() }
        BandKind.Album -> AlbumLockup(entry.title, entry.subtitle, entry.coverId, entry.action != null) { entry.action?.invoke() }
        BandKind.Library -> LibraryLockup(entry.title, entry.subtitle, entry.action != null) { entry.action?.invoke() }
    }
}

@Composable
private fun AllPlaylists(container: AppContainer, onOpen: (Playlist) -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { container.musicRepository.playlists() }.onSuccess { playlists = it } }
    GridPage("全部歌单", playlists, { it.guid.value }) { playlist ->
        PlaylistTile(playlist.name, "歌单", playlist.coverId, FnColors.Coral) { onOpen(playlist) }
    }
}

@Composable
private fun ArtistGrid(container: AppContainer, onOpen: (Artist) -> Unit) {
    PagedGrid("全部歌手", loader = container.musicRepository::artists, key = { it.guid.value }) { artist ->
        ArtistLockup(artist.name, "${artist.trackCount ?: 0} 首歌曲", artist.coverId) { onOpen(artist) }
    }
}

@Composable
private fun AlbumGrid(container: AppContainer, onOpen: (Album) -> Unit) {
    PagedGrid("全部专辑", loader = container.musicRepository::albums, key = { it.guid.value }) { album ->
        AlbumLockup(album.name, album.artistName.orEmpty(), album.coverId) { onOpen(album) }
    }
}

@Composable
private fun <T> PagedGrid(
    title: String,
    loader: suspend (Int) -> Page<T>,
    key: (T) -> String,
    item: @Composable (T) -> Unit,
) {
    var entries by remember { mutableStateOf<List<T>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load(target: Int) {
        if (loading) return
        loading = true
        scope.launch {
            runCatching { loader(target) }.onSuccess {
                entries = (entries + it.items).distinctBy(key)
                page = target
                hasNext = it.hasNext
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(1) }
    Column(Modifier.fillMaxSize().padding(64.dp, 44.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries, key = key) { item(it) }
            if (hasNext) item { LoadMoreBlock(page + 1, loading) { load(page + 1) } }
        }
    }
}

@Composable
private fun <T> GridPage(title: String, entries: List<T>, key: (T) -> String, item: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxSize().padding(64.dp, 44.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries, key = key) { item(it) }
        }
    }
}

@Composable
private fun ArtistDetail(container: AppContainer, artist: Artist, onAlbum: (Album) -> Unit, onPlayer: (Track) -> Unit) {
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    LaunchedEffect(artist.guid) { runCatching { container.musicRepository.artistAlbums(artist.guid.value, 1).items }.onSuccess { albums = it } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 64.dp, vertical = 32.dp)) {
            Text(artist.name, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            if (albums.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums.take(8)) { album ->
                        AlbumLockup(album.name, album.artistName.orEmpty(), album.coverId) { onAlbum(album) }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            TrackCollection(
                container,
                "歌曲",
                loader = { container.musicRepository.artistTracks(artist.guid.value, it) },
                queueSource = { sort -> QueueSource.Artist(artist.guid.value, sort) },
                onPlayer = onPlayer,
            )
        }
    }
}

@Composable
private fun TrackCollection(
    container: AppContainer,
    title: String,
    subtitle: String = "",
    coverId: String? = null,
    loader: suspend (Int) -> Page<Track>,
    queueSource: (String) -> QueueSource,
    onPlayer: (Track) -> Unit,
) {
    var tracks by remember(title) { mutableStateOf<List<Track>>(emptyList()) }
    var page by remember(title) { mutableStateOf(1) }
    var hasNext by remember(title) { mutableStateOf(false) }
    var loading by remember(title) { mutableStateOf(false) }
    var error by remember(title) { mutableStateOf<AppError?>(null) }
    var expectedTotal by remember(title) { mutableStateOf<Int?>(null) }
    var expectedSort by remember(title) { mutableStateOf<String?>(null) }
    var focusedGuid by rememberSaveable(title) { mutableStateOf<String?>(null) }
    val restoredFocus = remember(title) { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun load(target: Int) {
        if (loading) return
        loading = true
        scope.launch {
            runCatching { loader(target) }.onSuccess {
                val existing = tracks.asSequence().map { track -> track.guid.value }.toHashSet()
                val drifted = target > 1 && (
                    expectedTotal != it.total || expectedSort != it.sort || it.items.any { track -> track.guid.value in existing }
                )
                if (drifted) {
                    error = AppError.CollectionChanged
                    hasNext = false
                    return@onSuccess
                }
                tracks = tracks + it.items
                page = target
                hasNext = it.hasNext
                expectedTotal = it.total
                expectedSort = it.sort
                error = null
            }.onFailure { error = (it as? AppException)?.error ?: AppError.Unknown() }
            loading = false
        }
    }
    fun play(items: List<Track>, index: Int) {
        val target = items.getOrNull(index) ?: return
        val window = boundedQueueWindow(items, index)
        val queue = container.musicRepository.prepareQueue(window.items)
        val queueIndex = queue.indexOfFirst { it.track.guid == target.guid }.coerceAtLeast(0)
        if (queue.isEmpty()) {
            error = AppError.TranscodeUnavailable
            return
        }
        val sort = expectedSort ?: return
        container.playbackController.playQueue(
            tracks = queue,
            startIndex = queueIndex,
            source = queueSource(sort),
            windowStart = window.startIndex,
            firstPage = window.startIndex / 50 + 1,
            lastPage = (window.startIndex + window.items.lastIndex) / 50 + 1,
            knownTotal = expectedTotal,
        )
        onPlayer(target)
    }
    LaunchedEffect(title) { load(1) }
    LaunchedEffect(tracks.size) {
        if (focusedGuid != null) runCatching { restoredFocus.requestFocus() }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 38.dp), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
        Column(Modifier.width(330.dp)) {
            if (coverId != null) RemoteArtwork(container, coverId, CoverVariant.Grid, Modifier.size(300.dp))
            Text(title, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = FnColors.Muted, fontSize = 21.sp)
            Spacer(Modifier.height(18.dp))
            Button(enabled = tracks.any { !it.isCue && (it.accessStatus == null || it.accessStatus == 0) }, onClick = { play(tracks, 0) }) {
                Text("播放全部")
            }
            error?.let { InlineError(it) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tracks, key = { it.guid.value }) { track ->
                val index = tracks.indexOf(track)
                val restoreModifier = if (focusedGuid == track.guid.value) Modifier.focusRequester(restoredFocus) else Modifier
                TrackRow(
                    track,
                    enabled = !track.isCue && (track.accessStatus == null || track.accessStatus == 0),
                    modifier = restoreModifier.onFocusChanged { state ->
                        if (state.isFocused) {
                            focusedGuid = track.guid.value
                            if (hasNext && index >= tracks.size - 15) load(page + 1)
                        }
                    },
                ) {
                    play(tracks, tracks.indexOf(track))
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(8.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(width = 193.dp, height = 142.dp),
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(width = 170.dp, height = 95.dp),
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val artworkShape = RoundedCornerShape(4.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(width = 165.dp, height = 95.dp),
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
private fun LibraryLockup(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val artworkShape = RoundedCornerShape(6.dp)
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(width = 170.dp, height = 95.dp),
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

@Composable
private fun ImmersivePlayer(
    container: AppContainer,
    playback: PlaybackUiState,
    track: Track?,
    roaming: Boolean,
    roamWindow: RoamWindow?,
    onRoamChanged: (RoamWindow) -> Unit,
    onExitRoam: () -> Unit,
) {
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    var timeline by remember(playback.mediaId) { mutableStateOf<LyricTimeline?>(null) }
    var staticLyric by remember(playback.mediaId) { mutableStateOf<String?>(null) }
    var lyricsLoading by remember(playback.mediaId) { mutableStateOf(playback.mediaId.isNotBlank()) }
    var lyricsFailed by remember(playback.mediaId) { mutableStateOf(false) }
    var roamBusy by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionEpoch by remember { mutableStateOf(0) }
    val playerFocus = remember { FocusRequester() }
    val progressFocus = remember { FocusRequester() }
    val previousFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val exitRoamFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun revealControls() {
        controlsVisible = true
        interactionEpoch++
    }
    LaunchedEffect(playback.mediaId) {
        if (playback.mediaId.isNotBlank()) {
            lyricsLoading = true
            lyricsFailed = false
            runCatching { container.musicRepository.lyrics(playback.mediaId) }
                .onSuccess { (document, parsed) ->
                    timeline = parsed
                    staticLyric = document?.takeUnless { it.isLrc }?.content
                }
                .onFailure { lyricsFailed = true }
            lyricsLoading = false
        }
    }
    val active = timeline?.activeIndex(playback.positionMs) ?: -1
    val lyricLines = timeline?.lines.orEmpty()
    val playbackCoverId = remember(playback.artworkUrl) {
        playback.artworkUrl?.let { runCatching { it.toUri().getQueryParameter("coverId") }.getOrNull() }
    }
    val playerCoverId = playbackCoverId ?: track?.takeIf { it.guid.value == playback.mediaId }?.coverId
    val title = playback.title.ifBlank { track?.title ?: "尚未选择歌曲" }
    val artist = playback.artist.ifBlank { track?.artistName.orEmpty() }
    val audioFormat = playback.audioFormat.ifBlank { track?.audioFormat.orEmpty() }
    val poster = preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Poster
    val artworkBitmap = rememberRemoteArtworkBitmap(
        container,
        playerCoverId,
        if (poster) CoverVariant.Poster else CoverVariant.Player,
    )
    val ambienceColor = remember(artworkBitmap, title, artist) {
        val samples = artworkBitmap?.let(::sampleArtworkPixels) ?: IntArray(0)
        dominantArtworkColor(samples, "$title|$artist")
    }
    val posterPanelColor = remember(ambienceColor) { posterSurfaceColor(ambienceColor) }
    val previousEnabled = if (roaming) roamWindow?.previous != null else playback.currentIndex > 0
    val nextEnabled = if (roaming) roamWindow?.next != null else playback.currentIndex + 1 < playback.itemCount
    LaunchedEffect(interactionEpoch, controlsVisible) {
        if (controlsVisible) {
            delay(5_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) playFocus.requestFocus() else playerFocus.requestFocus()
    }
    LaunchedEffect(roaming) {
        if (!roaming && controlsVisible) playFocus.requestFocus()
    }
    BackHandler(controlsVisible) {
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
        )
        if (controlsVisible) {
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
                exitRoamFocus = exitRoamFocus,
                onInteraction = ::revealControls,
                onSeek = container.playbackController::seekBy,
                onPrevious = {
                    revealControls()
                    if (!roaming) {
                        container.playbackController.previous()
                    } else {
                        roamWindow?.current?.roamId?.let { id ->
                            if (!roamBusy) {
                                roamBusy = true
                                scope.launch {
                                    try {
                                        runCatching { container.musicRepository.previousRoam(id) }.onSuccess { window ->
                                            runCatching { container.musicRepository.prepare(window.current.track) }.onSuccess {
                                                container.playbackController.replaceRoamTrack(it)
                                                onRoamChanged(window)
                                            }
                                        }
                                    } finally {
                                        roamBusy = false
                                    }
                                }
                            }
                        }
                    }
                },
                onPlayPause = {
                    revealControls()
                    container.playbackController.playPause()
                },
                onNext = {
                    revealControls()
                    if (!roaming) {
                        container.playbackController.next()
                    } else {
                        roamWindow?.current?.roamId?.let { id ->
                            if (!roamBusy) {
                                roamBusy = true
                                scope.launch {
                                    try {
                                        runCatching { container.musicRepository.nextRoam(id) }.onSuccess { window ->
                                            runCatching { container.musicRepository.prepare(window.current.track) }.onSuccess {
                                                container.playbackController.replaceRoamTrack(it)
                                                onRoamChanged(window)
                                            }
                                        }
                                    } finally {
                                        roamBusy = false
                                    }
                                }
                            }
                        }
                    }
                },
                onExitRoam = onExitRoam,
                modifier = Modifier.align(Alignment.BottomCenter),
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
                poster = false,
                modifier = Modifier.weight(0.51f).fillMaxHeight()
                    .padding(start = 26.dp, end = 44.dp, top = 30.dp, bottom = 132.dp),
            )
        }
    }
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
        val statusMessage = queueError ?: playbackError?.let { "播放失败：$it" }
        if (statusMessage != null) {
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(statusMessage, color = FnColors.Coral, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (queueError != null && canRetryQueue) {
                    Button(onClick = onRetryQueue, modifier = Modifier.height(40.dp)) { Text("重试", maxLines = 1) }
                }
            }
        }
    }
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
private fun PlayerControlOverlay(
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
    exitRoamFocus: FocusRequester,
    onInteraction: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
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
                        up = FocusRequester.Cancel
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
                    rightFocus = playFocus,
                    onFocus = onInteraction,
                    onClick = onPrevious,
                )
                PlayerTransportButton(
                    glyph = if (isPlaying) TransportGlyph.Pause else TransportGlyph.Play,
                    description = if (isPlaying) "暂停" else "播放",
                    focusRequester = playFocus,
                    upFocus = progressFocus,
                    leftFocus = previousFocus.takeIf { previousEnabled },
                    rightFocus = when {
                        nextEnabled -> nextFocus
                        roaming -> exitRoamFocus
                        else -> null
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
                    rightFocus = exitRoamFocus.takeIf { roaming },
                    onFocus = onInteraction,
                    onClick = onNext,
                )
            }
            if (roaming) {
                Button(
                    onClick = onExitRoam,
                    modifier = Modifier.align(Alignment.CenterEnd).size(width = 95.dp, height = 29.dp)
                        .focusProperties {
                            up = progressFocus
                            down = FocusRequester.Cancel
                            left = if (nextEnabled) nextFocus else playFocus
                            right = FocusRequester.Cancel
                        }
                        .focusRequester(exitRoamFocus)
                        .onFocusChanged { if (it.isFocused) onInteraction() }
                        .semantics { contentDescription = "退出漫游" },
                    scale = ButtonDefaults.scale(focusedScale = 1.04f),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF382A27),
                        contentColor = Color(0xFFF0D9D1),
                        focusedContainerColor = FnColors.Coral,
                        focusedContentColor = FnColors.Background,
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("退出漫游", fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
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
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf(CacheUsage(0, 0, 0)) }
    suspend fun refreshUsage() {
        container.musicRepository.applyArtworkBudget()
        usage = container.musicRepository.cacheUsage()
    }
    LaunchedEffect(preferences.cacheBudget) { refreshUsage() }
    Column(Modifier.fillMaxSize().padding(72.dp, 50.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("设置", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("播放界面", fontSize = 25.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(onClick = { container.appPreferences.setPlayerStyle(com.fnmusic.tv.core.model.PlayerStyle.Cover) }) {
                Text(if (preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Cover) "CD 模式 · 已选" else "CD 模式")
            }
            Button(onClick = { container.appPreferences.setPlayerStyle(com.fnmusic.tv.core.model.PlayerStyle.Poster) }) {
                Text(if (preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Poster) "大海报模式 · 已选" else "大海报模式")
            }
        }
        Text("音频 + 图片缓存上限", fontSize = 25.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CacheBudget.entries.forEach { budget ->
                Button(onClick = {
                    container.appPreferences.setCacheBudget(budget)
                    scope.launch { refreshUsage() }
                }) {
                    Text(if (preferences.cacheBudget == budget) "${budget.megabytes} MB · 已选" else "${budget.megabytes} MB")
                }
            }
        }
        Text(
            "当前 ${formatBytes(usage.totalBytes)}（音频 ${formatBytes(usage.mediaBytes)} / 图片 ${formatBytes(usage.artworkBytes)}）",
            color = FnColors.Muted,
            fontSize = 18.sp,
        )
        Text(
            "图片额度立即清理，音频额度下次播放服务启动时生效；资料索引当前 ${formatBytes(usage.indexBytes)}，最多另占 32 MB",
            color = FnColors.Muted,
            fontSize = 18.sp,
        )
        Button(onClick = {
            scope.launch {
                container.musicRepository.clearArtwork()
                container.playbackController.clearMediaCache()
                usage = container.musicRepository.cacheUsage()
            }
        }) { Text("清除音频和图片缓存") }
    }
}

@Composable
private fun InlineError(error: AppError) {
    val message = when (error) {
        AppError.NetworkUnavailable -> "NAS 暂时不可用"
        AppError.Empty -> "暂无内容"
        AppError.UnavailableTrack -> "歌曲不可访问"
        AppError.TranscodeUnavailable -> "兼容播放参数尚未确认"
        AppError.CollectionChanged -> "列表已更新，请返回后重新载入"
        else -> "加载失败，请重试"
    }
    Text(message, color = FnColors.Coral, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
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
