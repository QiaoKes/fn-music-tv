package com.fnmusic.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal sealed interface LibraryRoute {
    data object Home : LibraryRoute
    data object My : LibraryRoute
    data object AllPlaylists : LibraryRoute
    data class PlaylistDetail(val playlist: Playlist) : LibraryRoute
    data object Artists : LibraryRoute
    data object Albums : LibraryRoute
    data object AllTracks : LibraryRoute
    data object Favorites : LibraryRoute
    data class ArtistDetail(val artist: Artist) : LibraryRoute
    data class AlbumDetail(val album: Album) : LibraryRoute
    data class Player(val track: Track?) : LibraryRoute
    data object Settings : LibraryRoute
}

internal data class RetainedPageSnapshot<T>(
    val entries: List<T> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val total: Int? = null,
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
    total = loaded.total,
    error = null,
    initialLoadCompleted = current.initialLoadCompleted || loaded.page == 1,
)

internal fun catalogPageCount(total: Int?, loadedCount: Int, pageSize: Int): Int {
    require(pageSize > 0)
    val itemCount = total?.coerceAtLeast(loadedCount) ?: loadedCount
    return ((itemCount + pageSize - 1) / pageSize).coerceAtLeast(1)
}

internal fun catalogPageStartIndex(page: Int, pageSize: Int): Int {
    require(pageSize > 0)
    return (page.coerceAtLeast(1) - 1) * pageSize
}

internal fun <T> catalogPageEntries(entries: List<T>, page: Int, pageSize: Int): List<T> =
    entries.drop(catalogPageStartIndex(page, pageSize)).take(pageSize)

internal fun shouldPrefetchCatalogContinuation(
    currentPage: Int,
    pageSize: Int,
    loadedCount: Int,
    hasNext: Boolean,
): Boolean = hasNext && currentPage.coerceAtLeast(1) * pageSize >= loadedCount

internal enum class CatalogPagerTarget { Previous, Next }

internal fun catalogPagerTarget(
    column: Int,
    columns: Int,
    canPrevious: Boolean,
    canNext: Boolean,
): CatalogPagerTarget? {
    require(columns > 0)
    if (!canPrevious && !canNext) return null
    if (!canPrevious) return CatalogPagerTarget.Next
    if (!canNext) return CatalogPagerTarget.Previous
    return if (column.coerceIn(0, columns - 1) < columns / 2) {
        CatalogPagerTarget.Previous
    } else {
        CatalogPagerTarget.Next
    }
}

internal fun shouldLoadInitialPage(snapshot: RetainedPageSnapshot<*>): Boolean =
    !snapshot.initialLoadCompleted

internal data class RetainedListSnapshot<T>(
    val entries: List<T> = emptyList(),
    val error: AppError? = null,
    val initialLoadCompleted: Boolean = false,
)

internal fun <T> retainLoadedList(
    current: RetainedListSnapshot<T>,
    loaded: List<T>,
): RetainedListSnapshot<T> = current.copy(
    entries = loaded,
    error = null,
    initialLoadCompleted = true,
)

internal fun shouldLoadInitialList(snapshot: RetainedListSnapshot<*>): Boolean =
    !snapshot.initialLoadCompleted || snapshot.entries.isEmpty() && snapshot.error != null

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

internal class RetainedPagedGridState<T> {
    var snapshot by mutableStateOf(RetainedPageSnapshot<T>())
    var loading by mutableStateOf(false)
    var contentRevision by mutableStateOf<Long?>(null)
}

internal class RetainedListState<T> {
    var snapshot by mutableStateOf(RetainedListSnapshot<T>())
    var loading by mutableStateOf(false)
}

internal class RetainedTrackCollectionState {
    var snapshot by mutableStateOf(RetainedTrackCollectionSnapshot())
    var loading by mutableStateOf(false)
}

internal class LibraryRetainedStateStore(val scope: CoroutineScope) {
    private val pagedStates = mutableMapOf<String, RetainedPagedGridState<*>>()
    private val trackStates = mutableMapOf<String, RetainedTrackCollectionState>()
    private val listStates = mutableMapOf<String, RetainedListState<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> paged(key: String): RetainedPagedGridState<T> =
        pagedStates.getOrPut(key) { RetainedPagedGridState<T>() } as RetainedPagedGridState<T>

    fun tracks(key: String): RetainedTrackCollectionState =
        trackStates.getOrPut(key) { RetainedTrackCollectionState() }

    @Suppress("UNCHECKED_CAST")
    fun <T> list(key: String): RetainedListState<T> =
        listStates.getOrPut(key) { RetainedListState<T>() } as RetainedListState<T>

    fun remove(keys: Set<String>) {
        keys.forEach { key ->
            pagedStates.remove(key)
            trackStates.remove(key)
            listStates.remove(key)
        }
    }

    internal fun contains(key: String): Boolean =
        key in pagedStates || key in trackStates || key in listStates

    internal val size: Int
        get() = pagedStates.size + trackStates.size + listStates.size

    fun <T> loadListOnce(state: RetainedListState<T>, loader: suspend () -> List<T>) {
        if (state.loading || !shouldLoadInitialList(state.snapshot)) return
        state.loading = true
        scope.launch {
            runCatching { loader() }
                .onSuccess { state.snapshot = retainLoadedList(state.snapshot, it) }
                .onFailure {
                    state.snapshot = state.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = true,
                    )
                }
            state.loading = false
        }
    }

    fun <T> loadFirstPageOnce(
        state: RetainedPagedGridState<T>,
        loader: suspend (Int) -> Page<T>,
        key: (T) -> String,
    ) {
        if (state.loading || !shouldLoadInitialPage(state.snapshot)) return
        state.loading = true
        scope.launch {
            runCatching { loader(1) }
                .onSuccess { state.snapshot = retainLoadedPage(state.snapshot, it, key) }
                .onFailure {
                    state.snapshot = state.snapshot.copy(
                        error = (it as? AppException)?.error ?: AppError.Unknown(),
                        initialLoadCompleted = true,
                    )
                }
            state.loading = false
        }
    }

    fun <T> loadFirstPageForRevision(
        state: RetainedPagedGridState<T>,
        revision: Long,
        loader: suspend (Int) -> Page<T>,
        key: (T) -> String,
    ) {
        val revisionChanged = shouldRefreshPagedPreview(state.contentRevision, revision)
        if (!revisionChanged && (state.loading || !shouldLoadInitialPage(state.snapshot))) return
        if (revisionChanged) {
            state.contentRevision = revision
            state.snapshot = RetainedPageSnapshot()
        }
        if (state.loading) return

        state.loading = true
        scope.launch {
            while (true) {
                val loadingRevision = state.contentRevision ?: break
                val result = runCatching { loader(1) }
                if (state.contentRevision == loadingRevision) {
                    result
                        .onSuccess { state.snapshot = retainLoadedPage(state.snapshot, it, key) }
                        .onFailure {
                            state.snapshot = state.snapshot.copy(
                                error = (it as? AppException)?.error ?: AppError.Unknown(),
                                initialLoadCompleted = true,
                            )
                        }
                    break
                }
            }
            state.loading = false
        }
    }
}

internal fun shouldRefreshPagedPreview(loadedRevision: Long?, currentRevision: Long): Boolean =
    loadedRevision != currentRevision

internal class LibraryRouteStateLifecycle {
    private var activeRoutesByKey = emptyMap<String, LibraryRoute>()

    fun update(stack: List<LibraryRoute>): List<LibraryRoute> {
        val nextRoutesByKey = stack.associateBy(LibraryRoute::storageKey)
        val removed = activeRoutesByKey.filterKeys { it !in nextRoutesByKey }.values.toList()
        activeRoutesByKey = nextRoutesByKey
        return removed
    }
}

internal fun LibraryRoute.storageKey(): String = when (this) {
    LibraryRoute.Home -> "home"
    LibraryRoute.My -> "my"
    LibraryRoute.AllPlaylists -> "playlists"
    is LibraryRoute.PlaylistDetail -> "playlist:${playlist.guid.value}"
    LibraryRoute.Artists -> "artists"
    LibraryRoute.Albums -> "albums"
    LibraryRoute.AllTracks -> "tracks"
    LibraryRoute.Favorites -> "favorites"
    is LibraryRoute.ArtistDetail -> "artist:${artist.guid.value}"
    is LibraryRoute.AlbumDetail -> "album:${album.guid.value}"
    is LibraryRoute.Player -> "player"
    LibraryRoute.Settings -> "settings"
}

internal fun LibraryRoute.retainedStateKeys(): Set<String> = when (this) {
    is LibraryRoute.PlaylistDetail -> setOf("playlist:${playlist.guid.value}:tracks")
    is LibraryRoute.ArtistDetail -> setOf(
        "artist:${artist.guid.value}:albums",
        "artist:${artist.guid.value}:tracks",
    )
    is LibraryRoute.AlbumDetail -> setOf("album:${album.guid.value}:tracks")
    LibraryRoute.Favorites -> setOf("favorites:tracks")
    else -> emptySet()
}
