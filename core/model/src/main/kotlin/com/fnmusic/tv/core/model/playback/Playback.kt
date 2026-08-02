package com.fnmusic.tv.core.model.playback

import com.fnmusic.tv.core.model.Track

sealed interface PlaybackSource {
    data class Direct(val track: Track) : PlaybackSource
    data class Hls(val track: Track, val reason: HlsReason) : PlaybackSource
}

enum class HlsReason { CueTrack, DecoderFallback }

const val MAX_ACTIVE_QUEUE_ITEMS = 250
const val DEFAULT_QUEUE_PAGE_SIZE = 50

enum class PlayMode {
    ListRepeat,
    Shuffle,
    SingleRepeat,
    Sequence,
}

val DEFAULT_PLAY_MODE: PlayMode = PlayMode.ListRepeat

enum class RepeatBehavior { Off, One, All }

data class PlayModeMapping(
    val repeatBehavior: RepeatBehavior,
    val shuffleEnabled: Boolean,
)

val PlayMode.mapping: PlayModeMapping
    get() = when (this) {
        PlayMode.ListRepeat -> PlayModeMapping(RepeatBehavior.All, shuffleEnabled = false)
        PlayMode.Shuffle -> PlayModeMapping(RepeatBehavior.All, shuffleEnabled = true)
        PlayMode.SingleRepeat -> PlayModeMapping(RepeatBehavior.One, shuffleEnabled = false)
        PlayMode.Sequence -> PlayModeMapping(RepeatBehavior.Off, shuffleEnabled = false)
    }

fun PlayMode.next(): PlayMode = PlayMode.entries[(ordinal + 1) % PlayMode.entries.size]

enum class QueueKind { Normal, Roam }

data class PlaybackQueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val queueIndex: Int,
    val isCurrent: Boolean,
)

data class NowPlayingIdentity(
    val namespace: String,
    val mediaId: String,
    val presentationRevision: Long,
    val title: String,
    val artist: String,
    val audioFormat: String,
    val coverId: String?,
)

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
    maxSize: Int = MAX_ACTIVE_QUEUE_ITEMS,
    pageSize: Int = DEFAULT_QUEUE_PAGE_SIZE,
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

data class QueuePageItem(
    val mediaId: String,
    val sourceAbsoluteIndex: Int,
) {
    init {
        require(mediaId.isNotBlank())
        require(sourceAbsoluteIndex >= 0)
    }
}

data class QueuePageSegment(
    val page: Int,
    val rawRowCount: Int,
    val playableItems: List<QueuePageItem>,
    val sort: String,
    val knownTotal: Int?,
    val pageSize: Int = DEFAULT_QUEUE_PAGE_SIZE,
    val sourceStartIndex: Int = (page - 1) * pageSize,
) {
    init {
        require(page > 0)
        require(pageSize > 0)
        require(rawRowCount in 0..pageSize)
        require(sourceStartIndex >= 0)
        require(knownTotal == null || knownTotal >= 0)
        require(sourceStartIndex.toLong() + rawRowCount <= Int.MAX_VALUE)
        require(playableItems.map(QueuePageItem::mediaId).distinct().size == playableItems.size)
        require(playableItems.zipWithNext().all { (left, right) ->
            left.sourceAbsoluteIndex < right.sourceAbsoluteIndex
        })
        require(playableItems.all { item ->
            item.sourceAbsoluteIndex >= sourceStartIndex && item.sourceAbsoluteIndex < sourceEndExclusive
        })
    }

    val sourceEndExclusive: Int get() = sourceStartIndex + rawRowCount
    val mediaIds: List<String> get() = playableItems.map(QueuePageItem::mediaId)
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
    val segments: List<QueuePageSegment> = legacyQueueSegments(
        guids = guids,
        windowStart = windowStart,
        firstPage = firstPage,
        lastPage = lastPage,
        knownTotal = knownTotal,
        sort = sort,
    ),
) {
    val activeItems: List<QueuePageItem> get() = segments.flatMap(QueuePageSegment::playableItems)

    companion object {
        fun fromSegments(
            segments: List<QueuePageSegment>,
            currentIndex: Int,
            loading: Boolean = false,
            failureCount: Int = 0,
            invalidated: Boolean = false,
        ): SlidingQueueState = stateFromSegments(
            segments = segments,
            currentIndex = currentIndex,
            loading = loading,
            failureCount = failureCount,
            invalidated = invalidated,
        )
    }
}

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
        maxSize: Int = MAX_ACTIVE_QUEUE_ITEMS,
    ): SlidingQueueUpdate = append(
        state = state,
        segment = legacyIncomingSegment(page, guids, total, sort),
        maxSize = maxSize,
    )

    fun append(
        state: SlidingQueueState,
        segment: QueuePageSegment,
        maxSize: Int = MAX_ACTIVE_QUEUE_ITEMS,
    ): SlidingQueueUpdate {
        require(maxSize > 0)
        if (!state.loading || segment.page != state.lastPage + 1) return SlidingQueueUpdate(state)
        if (drifted(state, segment)) return SlidingQueueUpdate(invalidate(state))

        val (retainedSegments, incomingSegment) = reconcileKnownTotal(state, segment)
        val combined = retainedSegments + incomingSegment
        val trimmed = trimStart(combined, state.currentIndex, maxSize)
            ?: return SlidingQueueUpdate(invalidate(state))
        return SlidingQueueUpdate(
            state = stateFromSegments(
                segments = trimmed.segments,
                currentIndex = state.currentIndex - trimmed.removedItemCount,
                loading = false,
                failureCount = 0,
                invalidated = false,
            ),
            removeFromStart = trimmed.removedItemCount,
        )
    }

    fun prepend(
        state: SlidingQueueState,
        page: Int,
        guids: List<String>,
        total: Int,
        sort: String,
        maxSize: Int = MAX_ACTIVE_QUEUE_ITEMS,
    ): SlidingQueueUpdate = prepend(
        state = state,
        segment = legacyIncomingSegment(page, guids, total, sort),
        maxSize = maxSize,
    )

    fun prepend(
        state: SlidingQueueState,
        segment: QueuePageSegment,
        maxSize: Int = MAX_ACTIVE_QUEUE_ITEMS,
    ): SlidingQueueUpdate {
        require(maxSize > 0)
        if (!state.loading || segment.page != state.firstPage - 1) return SlidingQueueUpdate(state)
        if (drifted(state, segment)) return SlidingQueueUpdate(invalidate(state))

        val (retainedSegments, incomingSegment) = reconcileKnownTotal(state, segment)
        val combined = listOf(incomingSegment) + retainedSegments
        val currentIndex = state.currentIndex + segment.playableItems.size
        val trimmed = trimEnd(combined, currentIndex, maxSize)
            ?: return SlidingQueueUpdate(invalidate(state))
        return SlidingQueueUpdate(
            state = stateFromSegments(
                segments = trimmed.segments,
                currentIndex = currentIndex,
                loading = false,
                failureCount = 0,
                invalidated = false,
            ),
            removeFromEnd = trimmed.removedItemCount,
        )
    }

    private fun drifted(state: SlidingQueueState, incoming: QueuePageSegment): Boolean {
        if (!state.isSegmentCoherent()) return true
        if (state.knownTotal != null && incoming.knownTotal != null && incoming.knownTotal != state.knownTotal) return true
        if (state.sort != incoming.sort) return true
        val overlapsRetainedRange = when (incoming.page) {
            state.lastPage + 1 -> incoming.sourceStartIndex < state.segments.last().sourceEndExclusive
            state.firstPage - 1 -> incoming.sourceEndExclusive > state.segments.first().sourceStartIndex
            else -> true
        }
        if (overlapsRetainedRange) return true
        val knownIds = state.guids.toHashSet()
        if (incoming.playableItems.any { !knownIds.add(it.mediaId) }) return true
        val occupiedSourceIndices = state.activeItems.mapTo(hashSetOf(), QueuePageItem::sourceAbsoluteIndex)
        return incoming.playableItems.any { !occupiedSourceIndices.add(it.sourceAbsoluteIndex) }
    }

    private fun invalidate(state: SlidingQueueState) = state.copy(loading = false, invalidated = true)

    private fun reconcileKnownTotal(
        state: SlidingQueueState,
        incoming: QueuePageSegment,
    ): Pair<List<QueuePageSegment>, QueuePageSegment> {
        val knownTotal = state.knownTotal ?: incoming.knownTotal
        val retained = state.segments.map { segment ->
            if (segment.knownTotal == knownTotal) segment else segment.copy(knownTotal = knownTotal)
        }
        val normalizedIncoming = if (incoming.knownTotal == knownTotal) {
            incoming
        } else {
            incoming.copy(knownTotal = knownTotal)
        }
        return retained to normalizedIncoming
    }

    private fun trimStart(
        segments: List<QueuePageSegment>,
        currentIndex: Int,
        maxSize: Int,
    ): TrimmedSegments? {
        val retained = segments.toMutableList()
        var activeCount = retained.sumOf { it.playableItems.size }
        var removed = 0
        while (activeCount > maxSize && retained.size > 1) {
            val first = retained.first()
            if (currentIndex < removed + first.playableItems.size) return null
            retained.removeAt(0)
            removed += first.playableItems.size
            activeCount -= first.playableItems.size
        }
        return retained.takeIf { activeCount <= maxSize }?.let { TrimmedSegments(it, removed) }
    }

    private fun trimEnd(
        segments: List<QueuePageSegment>,
        currentIndex: Int,
        maxSize: Int,
    ): TrimmedSegments? {
        val retained = segments.toMutableList()
        var activeCount = retained.sumOf { it.playableItems.size }
        var removed = 0
        while (activeCount > maxSize && retained.size > 1) {
            val last = retained.last()
            val lastStart = activeCount - last.playableItems.size
            if (currentIndex >= lastStart) return null
            retained.removeAt(retained.lastIndex)
            removed += last.playableItems.size
            activeCount -= last.playableItems.size
        }
        return retained.takeIf { activeCount <= maxSize }?.let { TrimmedSegments(it, removed) }
    }

    private data class TrimmedSegments(
        val segments: List<QueuePageSegment>,
        val removedItemCount: Int,
    )
}

private fun SlidingQueueState.isSegmentCoherent(): Boolean =
    segments.isNotEmpty() &&
        segments.hasValidTopology() &&
        segments.flatMap(QueuePageSegment::mediaIds) == guids &&
        guids.distinct().size == guids.size &&
        segments.all { it.sort == sort && it.knownTotal == knownTotal }

private fun List<QueuePageSegment>.hasValidTopology(): Boolean =
    zipWithNext().all { (left, right) ->
        right.page == left.page + 1 && left.sourceEndExclusive <= right.sourceStartIndex
    }

private fun stateFromSegments(
    segments: List<QueuePageSegment>,
    currentIndex: Int,
    loading: Boolean,
    failureCount: Int,
    invalidated: Boolean,
): SlidingQueueState {
    require(segments.isNotEmpty())
    require(segments.hasValidTopology())
    val first = segments.first()
    val last = segments.last()
    require(segments.all { it.sort == first.sort && it.knownTotal == first.knownTotal })
    val guids = segments.flatMap(QueuePageSegment::mediaIds)
    require(guids.distinct().size == guids.size)
    val selected = if (guids.isEmpty()) 0 else currentIndex.coerceIn(guids.indices)
    return SlidingQueueState(
        guids = guids,
        currentIndex = selected,
        windowStart = first.sourceStartIndex,
        firstPage = first.page,
        lastPage = last.page,
        knownTotal = first.knownTotal,
        sort = first.sort,
        loading = loading,
        failureCount = failureCount,
        invalidated = invalidated,
        reachedStart = first.page == 1 && first.sourceStartIndex == 0,
        reachedEnd = last.knownTotal?.let { last.sourceEndExclusive >= it } == true,
        segments = segments,
    )
}

private fun legacyIncomingSegment(
    page: Int,
    guids: List<String>,
    total: Int,
    sort: String,
): QueuePageSegment {
    val start = (page - 1) * DEFAULT_QUEUE_PAGE_SIZE
    val rawCount = minOf(DEFAULT_QUEUE_PAGE_SIZE, (total - start).coerceAtLeast(0))
        .coerceAtLeast(guids.size)
    return QueuePageSegment(
        page = page,
        rawRowCount = rawCount,
        playableItems = guids.mapIndexed { index, guid -> QueuePageItem(guid, start + index) },
        sort = sort,
        knownTotal = total,
    )
}

private fun legacyQueueSegments(
    guids: List<String>,
    windowStart: Int,
    firstPage: Int,
    lastPage: Int,
    knownTotal: Int?,
    sort: String,
): List<QueuePageSegment> {
    require(firstPage > 0)
    require(lastPage >= firstPage)
    var offset = 0
    return (firstPage..lastPage).map { page ->
        val sourceStart = windowStart + (page - firstPage) * DEFAULT_QUEUE_PAGE_SIZE
        val rawCount = knownTotal
            ?.let { minOf(DEFAULT_QUEUE_PAGE_SIZE, (it - sourceStart).coerceAtLeast(0)) }
            ?: DEFAULT_QUEUE_PAGE_SIZE
        val itemCount = minOf(rawCount, guids.size - offset)
        val items = guids.subList(offset, offset + itemCount).mapIndexed { index, guid ->
            QueuePageItem(guid, sourceStart + index)
        }
        offset += itemCount
        QueuePageSegment(
            page = page,
            rawRowCount = rawCount,
            playableItems = items,
            sort = sort,
            knownTotal = knownTotal,
            sourceStartIndex = sourceStart,
        )
    }.also {
        require(offset == guids.size) { "Queue items exceed the declared page range" }
    }
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
