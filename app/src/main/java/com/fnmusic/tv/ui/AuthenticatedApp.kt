package com.fnmusic.tv.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
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
import kotlinx.coroutines.launch

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
            Button(onClick = onPlayer, modifier = Modifier.width(360.dp).height(62.dp)) {
                Text(playback.title.ifBlank { "正在播放" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 20.sp)
            }
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
                MediaTile("随机漫游", if (roaming) "正在准备" else "从曲库里遇见下一首", null, FnColors.Teal, enabled = !roaming) {
                    if (activeRoam != null) {
                        onResumeRoam(activeRoam)
                        return@MediaTile
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
                MediaTile(playlist.name, "歌单", playlist.coverId, FnColors.Coral) { onPlaylist(playlist) }
            }
            item { MediaTile("全部歌单", "浏览完整列表", null, FnColors.Muted, onClick = onAll) }
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
                MediaBand("歌手", artists.take(8).map { BandEntry(it.name, it.coverId) { onArtist(it) } }, onArtists)
            }
            item {
                MediaBand("专辑", albums.take(8).map { BandEntry(it.name, it.coverId) { onAlbum(it) } }, onAlbums)
            }
            item {
                val libraryEntries = listOf(BandEntry("全部歌曲", null, onAllTracks)) + libraries.map {
                    BandEntry(it.name, null, if (it.accessStatus == 0) onAllTracks else null)
                }
                MediaBand("音乐库", libraryEntries, onAllTracks)
            }
        }
    }
}

private data class BandEntry(val title: String, val coverId: String?, val action: (() -> Unit)?)

@Composable
private fun MediaBand(title: String, entries: List<BandEntry>, onAll: () -> Unit) {
    Column {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries) { entry ->
                MediaTile(entry.title, "", entry.coverId, FnColors.Teal, enabled = entry.action != null) { entry.action?.invoke() }
            }
            item { MediaTile("全部", "", null, FnColors.Muted, onClick = onAll) }
        }
    }
}

@Composable
private fun AllPlaylists(container: AppContainer, onOpen: (Playlist) -> Unit) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { container.musicRepository.playlists() }.onSuccess { playlists = it } }
    GridPage("全部歌单", playlists, { it.guid.value }) { playlist ->
        MediaTile(playlist.name, "歌单", playlist.coverId, FnColors.Coral) { onOpen(playlist) }
    }
}

@Composable
private fun ArtistGrid(container: AppContainer, onOpen: (Artist) -> Unit) {
    PagedGrid("全部歌手", loader = container.musicRepository::artists, key = { it.guid.value }) { artist ->
        MediaTile(artist.name, "${artist.trackCount ?: 0} 首", artist.coverId, FnColors.Teal) { onOpen(artist) }
    }
}

@Composable
private fun AlbumGrid(container: AppContainer, onOpen: (Album) -> Unit) {
    PagedGrid("全部专辑", loader = container.musicRepository::albums, key = { it.guid.value }) { album ->
        MediaTile(album.name, album.artistName.orEmpty(), album.coverId, FnColors.Coral) { onOpen(album) }
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
        LazyVerticalGrid(columns = GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries, key = key) { item(it) }
            if (hasNext) item { MediaTile("加载更多", "第 ${page + 1} 页", null, FnColors.Muted, enabled = !loading) { load(page + 1) } }
        }
    }
}

@Composable
private fun <T> GridPage(title: String, entries: List<T>, key: (T) -> String, item: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxSize().padding(64.dp, 44.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    items(albums.take(8)) { album -> MediaTile(album.name, "专辑", album.coverId, FnColors.Coral) { onAlbum(album) } }
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
private fun MediaTile(
    title: String,
    subtitle: String,
    coverId: String?,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(enabled = enabled, onClick = onClick, modifier = Modifier.size(width = 260.dp, height = 190.dp)) {
        Column(Modifier.fillMaxWidth()) {
            if (coverId != null) RemoteArtworkSmall(coverId) else Box(Modifier.fillMaxWidth().height(72.dp).background(accent, RoundedCornerShape(6.dp)))
            Spacer(Modifier.height(10.dp))
            Text(title, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = FnColors.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RemoteArtworkSmall(coverId: String) {
    val container = LocalAppContainer.current
    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        RemoteArtwork(container, coverId, CoverVariant.Compact, Modifier.fillMaxSize())
    }
}

@Composable
private fun RemoteArtwork(container: AppContainer, coverId: String, variant: CoverVariant, modifier: Modifier = Modifier) {
    val bytes by produceState<ByteArray?>(null, coverId, variant) { value = container.musicRepository.artwork(coverId, variant) }
    val bitmap = remember(bytes, variant) { bytes?.let { decodeArtwork(it, variant.width ?: 1_200) } }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, modifier.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
    } else {
        Box(modifier.background(FnColors.Surface, RoundedCornerShape(8.dp)))
    }
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
    var progressFocused by remember { mutableStateOf(false) }
    val playerFocus = remember { FocusRequester() }
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
    LaunchedEffect(interactionEpoch, controlsVisible) {
        if (controlsVisible) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) playFocus.requestFocus() else playerFocus.requestFocus()
    }
    BackHandler(controlsVisible) {
        controlsVisible = false
        playerFocus.requestFocus()
    }
    Row(
        Modifier.fillMaxSize()
            .focusRequester(playerFocus)
            .focusable()
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
            .padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artSize = if (preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Poster) 420.dp else 390.dp
        val playbackCoverId = remember(playback.artworkUrl) {
            playback.artworkUrl?.let { runCatching { it.toUri().getQueryParameter("coverId") }.getOrNull() }
        }
        val playerCoverId = playbackCoverId ?: track?.takeIf { it.guid.value == playback.mediaId }?.coverId
        if (playerCoverId != null) {
            RemoteArtwork(container, playerCoverId, if (preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Poster) CoverVariant.Poster else CoverVariant.Player, Modifier.size(artSize))
        } else {
            Box(Modifier.size(artSize).background(FnColors.Surface, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(playback.title.take(1).ifBlank { "音" }, fontSize = 108.sp, color = FnColors.Teal)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                playback.title.ifBlank { track?.title ?: "尚未选择歌曲" },
                fontSize = 36.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                playback.artist.ifBlank { track?.artistName.orEmpty() },
                color = FnColors.Muted,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            when {
                lyricLines.isNotEmpty() -> {
                    val start = (active - 1).coerceAtLeast(0)
                    lyricLines.drop(start).take(3).forEachIndexed { index, line ->
                        val current = start + index == active
                        Text(
                            line.texts.joinToString("\n"),
                            color = if (current) FnColors.Text else FnColors.Muted,
                            fontSize = if (current) 40.sp else 22.sp,
                            lineHeight = if (current) 46.sp else 26.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.height(if (current) 100.dp else 56.dp),
                        )
                    }
                }
                !staticLyric.isNullOrBlank() -> Text(staticLyric.orEmpty(), fontSize = 26.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                lyricsLoading -> Text("歌词加载中", color = FnColors.Muted, fontSize = 28.sp)
                lyricsFailed -> Text("歌词暂时无法加载", color = FnColors.Muted, fontSize = 28.sp)
                else -> Text("纯音乐或暂无歌词", color = FnColors.Muted, fontSize = 28.sp)
            }
            if (controlsVisible) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(22.dp)
                        .onFocusChanged { progressFocused = it.isFocused }
                        .border(if (progressFocused) 2.dp else 0.dp, FnColors.Text, RoundedCornerShape(4.dp))
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    container.playbackController.seekBy(-10_000)
                                    revealControls()
                                    true
                                }
                                Key.DirectionRight -> {
                                    container.playbackController.seekBy(10_000)
                                    revealControls()
                                    true
                                }
                                else -> false
                            }
                        }
                        .padding(vertical = 8.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF3A3F44))) {
                        val fraction = if (playback.durationMs > 0) (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(FnColors.Coral))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(playback.positionMs), color = FnColors.Muted, fontSize = 16.sp)
                    Text(formatDuration(playback.durationMs), color = FnColors.Muted, fontSize = 16.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val previousEnabled = if (roaming) roamWindow?.previous != null else playback.currentIndex > 0
                val nextEnabled = if (roaming) roamWindow?.next != null else playback.currentIndex + 1 < playback.itemCount
                Button(enabled = previousEnabled, onClick = {
                    revealControls()
                    if (!roaming) container.playbackController.previous() else roamWindow?.current?.roamId?.let { id ->
                        if (!roamBusy) scope.launch {
                            roamBusy = true
                            runCatching { container.musicRepository.previousRoam(id) }.onSuccess { window ->
                                runCatching { container.musicRepository.prepare(window.current.track) }.onSuccess {
                                    container.playbackController.replaceRoamTrack(it)
                                    onRoamChanged(window)
                                }
                            }
                            roamBusy = false
                        }
                    }
                }, modifier = Modifier
                    .focusRequester(previousFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            playFocus.requestFocus()
                            true
                        } else false
                    }
                    .semantics { contentDescription = "上一首" }) { Text("上一首") }
                Button(
                    onClick = {
                        revealControls()
                        container.playbackController.playPause()
                    },
                    modifier = Modifier
                        .focusRequester(playFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (previousEnabled) previousFocus.requestFocus()
                                    true
                                }
                                Key.DirectionRight -> {
                                    when {
                                        nextEnabled -> nextFocus.requestFocus()
                                        roaming -> exitRoamFocus.requestFocus()
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                ) { Text(if (playback.isPlaying) "暂停" else "播放") }
                Button(enabled = nextEnabled, onClick = {
                    revealControls()
                    if (!roaming) container.playbackController.next() else roamWindow?.current?.roamId?.let { id ->
                        if (!roamBusy) scope.launch {
                            roamBusy = true
                            runCatching { container.musicRepository.nextRoam(id) }.onSuccess { window ->
                                runCatching { container.musicRepository.prepare(window.current.track) }.onSuccess {
                                    container.playbackController.replaceRoamTrack(it)
                                    onRoamChanged(window)
                                }
                            }
                            roamBusy = false
                        }
                    }
                }, modifier = Modifier
                    .focusRequester(nextFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                playFocus.requestFocus()
                                true
                            }
                            Key.DirectionRight -> if (roaming) {
                                exitRoamFocus.requestFocus()
                                true
                            } else false
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "下一首" }) { Text("下一首") }
                    if (roaming) Button(
                        onClick = onExitRoam,
                        modifier = Modifier
                            .focusRequester(exitRoamFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                    if (nextEnabled) nextFocus.requestFocus() else playFocus.requestFocus()
                                    true
                                } else false
                            },
                    ) { Text("退出漫游") }
                }
            }
            playback.error?.let { Text("播放失败：$it", color = FnColors.Coral, fontSize = 18.sp) }
            playback.queueError?.let { message ->
                Text(message, color = FnColors.Coral, fontSize = 18.sp)
                if (playback.canRetryQueue) {
                    Button(onClick = container.playbackController::retryQueuePage) { Text("重试队列加载") }
                }
            }
        }
    }
}

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
                Text(if (preferences.playerStyle == com.fnmusic.tv.core.model.PlayerStyle.Cover) "封面模式 · 已选" else "封面模式")
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
