package com.fnmusic.tv.core.model.playback

import com.fnmusic.tv.core.model.Track

sealed interface PlaybackSource {
    data class Direct(val track: Track) : PlaybackSource
    data class Hls(val track: Track, val reason: HlsReason) : PlaybackSource
}

enum class HlsReason { CueTrack, DecoderFallback }

sealed interface QueueSource {
    val sort: String

    data class Playlist(val guid: String, override val sort: String) : QueueSource
    data class Artist(val guid: String, override val sort: String) : QueueSource
    data class Album(val guid: String, override val sort: String) : QueueSource
    data class LibraryAllTracks(override val sort: String) : QueueSource
}

data class QueueCursor(
    val source: QueueSource,
    val trackGuids: List<String>,
    val currentIndex: Int,
    val absoluteIndex: Int,
    val knownTotal: Int?,
    val loadedPage: Int,
    val pageSize: Int = 50,
    val loadingNextPage: Boolean = false,
    val endReached: Boolean = false,
    val failureCount: Int = 0,
    val invalidated: Boolean = false,
)

data class BoundedQueueWindow<T>(val items: List<T>, val selectedIndex: Int, val startIndex: Int)

fun <T> boundedQueueWindow(
    items: List<T>,
    selectedIndex: Int,
    maxSize: Int = 250,
    pageSize: Int = 50,
): BoundedQueueWindow<T> {
    require(maxSize > 0)
    require(pageSize > 0)
    if (items.isEmpty()) return BoundedQueueWindow(emptyList(), 0, 0)
    val selected = selectedIndex.coerceIn(items.indices)
    val maximumStart = (items.size - maxSize).coerceAtLeast(0)
    val centeredStart = (selected - maxSize / 2).coerceAtLeast(0)
    val start = ((centeredStart / pageSize) * pageSize)
        .coerceAtMost((maximumStart / pageSize) * pageSize)
    val window = items.subList(start, (start + maxSize).coerceAtMost(items.size))
    return BoundedQueueWindow(window, selected - start, start)
}

data class SlidingQueueState(
    val guids: List<String>,
    val currentIndex: Int,
    val windowStart: Int,
    val firstPage: Int,
    val lastPage: Int,
    val knownTotal: Int?,
    val sort: String,
    val loading: Boolean = false,
    val failureCount: Int = 0,
    val invalidated: Boolean = false,
    val reachedStart: Boolean = firstPage <= 1,
    val reachedEnd: Boolean = knownTotal != null && windowStart + guids.size >= knownTotal,
)

data class SlidingQueueUpdate(val state: SlidingQueueState, val removeFromStart: Int = 0, val removeFromEnd: Int = 0)

object SlidingQueueReducer {
    fun loading(state: SlidingQueueState): SlidingQueueState =
        if (state.loading || state.invalidated) state else state.copy(loading = true)

    fun failed(state: SlidingQueueState): SlidingQueueState =
        state.copy(loading = false, failureCount = state.failureCount + 1)

    fun append(
        state: SlidingQueueState,
        page: Int,
        guids: List<String>,
        total: Int,
        sort: String,
        maxSize: Int = 250,
    ): SlidingQueueUpdate {
        if (!state.loading || page != state.lastPage + 1) return SlidingQueueUpdate(state)
        if (drifted(state, guids, total, sort)) return SlidingQueueUpdate(invalidate(state))
        val combined = state.guids + guids
        val remove = (combined.size - maxSize).coerceAtLeast(0)
        return SlidingQueueUpdate(
            state.copy(
                guids = combined.drop(remove),
                currentIndex = (state.currentIndex - remove).coerceAtLeast(0),
                windowStart = state.windowStart + remove,
                lastPage = page,
                knownTotal = total,
                loading = false,
                failureCount = 0,
                reachedEnd = page * 50 >= total || guids.isEmpty(),
            ),
            removeFromStart = remove,
        )
    }

    fun prepend(
        state: SlidingQueueState,
        page: Int,
        guids: List<String>,
        total: Int,
        sort: String,
        maxSize: Int = 250,
    ): SlidingQueueUpdate {
        if (!state.loading || page != state.firstPage - 1) return SlidingQueueUpdate(state)
        if (drifted(state, guids, total, sort)) return SlidingQueueUpdate(invalidate(state))
        val combined = guids + state.guids
        val remove = (combined.size - maxSize).coerceAtLeast(0)
        return SlidingQueueUpdate(
            state.copy(
                guids = combined.dropLast(remove),
                currentIndex = state.currentIndex + guids.size,
                windowStart = (state.windowStart - guids.size).coerceAtLeast(0),
                firstPage = page,
                knownTotal = total,
                loading = false,
                failureCount = 0,
                reachedStart = page <= 1,
            ),
            removeFromEnd = remove,
        )
    }

    private fun drifted(state: SlidingQueueState, incoming: List<String>, total: Int, sort: String): Boolean =
        (state.knownTotal != null && state.knownTotal != total) || state.sort != sort || incoming.any { it in state.guids }

    private fun invalidate(state: SlidingQueueState) = state.copy(loading = false, invalidated = true)
}

sealed interface QueueAction {
    data class MoveTo(val index: Int) : QueueAction
    data object Next : QueueAction
    data object Previous : QueueAction
    data object PageLoadStarted : QueueAction
    data class PageLoaded(val page: Int, val guids: List<String>, val total: Int?) : QueueAction
    data object PageLoadFailed : QueueAction
}

object QueueReducer {
    fun reduce(state: QueueCursor, action: QueueAction): QueueCursor = when (action) {
        is QueueAction.MoveTo -> state.copy(currentIndex = action.index.coerceIn(state.trackGuids.indices))
        QueueAction.Next -> state.copy(currentIndex = (state.currentIndex + 1).coerceAtMost(state.trackGuids.lastIndex))
        QueueAction.Previous -> state.copy(currentIndex = (state.currentIndex - 1).coerceAtLeast(0))
        QueueAction.PageLoadStarted -> if (state.loadingNextPage || state.endReached) state else state.copy(loadingNextPage = true)
        is QueueAction.PageLoaded -> appendPage(state, action)
        QueueAction.PageLoadFailed -> state.copy(loadingNextPage = false, failureCount = state.failureCount + 1)
    }

    private fun appendPage(state: QueueCursor, action: QueueAction.PageLoaded): QueueCursor {
        if (!state.loadingNextPage || action.page != state.loadedPage + 1) return state
        val known = state.trackGuids.toHashSet()
        val drifted = (state.knownTotal != null && action.total != null && state.knownTotal != action.total) ||
            action.guids.any { it in known }
        if (drifted) return state.copy(loadingNextPage = false, endReached = true, invalidated = true)
        val unique = action.guids.filter(known::add)
        val tracks = state.trackGuids + unique
        return state.copy(
            trackGuids = tracks,
            knownTotal = action.total,
            loadedPage = action.page,
            loadingNextPage = false,
            endReached = tracks.size >= (action.total ?: Int.MAX_VALUE) || action.guids.isEmpty(),
            failureCount = 0,
        )
    }
}
