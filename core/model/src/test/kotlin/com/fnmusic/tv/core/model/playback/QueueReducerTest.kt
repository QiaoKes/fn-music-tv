package com.fnmusic.tv.core.model.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueReducerTest {
    private val state = QueueCursor(
        source = QueueSource.Playlist("p1", "trackAddedAt,desc"),
        trackGuids = listOf("a", "b"),
        currentIndex = 0,
        absoluteIndex = 0,
        knownTotal = 4,
        loadedPage = 1,
    )

    @Test fun `page append is single flight and ordered`() {
        val loading = QueueReducer.reduce(state, QueueAction.PageLoadStarted)
        val result = QueueReducer.reduce(loading, QueueAction.PageLoaded(2, listOf("c", "d"), 4))
        assertEquals(listOf("a", "b", "c", "d"), result.trackGuids)
        assertEquals(2, result.loadedPage)
        assertFalse(result.loadingNextPage)
    }

    @Test fun `unsolicited page does not mutate cursor`() {
        assertEquals(state, QueueReducer.reduce(state, QueueAction.PageLoaded(2, listOf("c"), 3)))
    }

    @Test fun `overlap or total change invalidates pagination`() {
        val loading = QueueReducer.reduce(state, QueueAction.PageLoadStarted)
        val overlap = QueueReducer.reduce(loading, QueueAction.PageLoaded(2, listOf("b", "c"), 4))
        assertTrue(overlap.invalidated)
        assertTrue(overlap.endReached)

        val totalChanged = QueueReducer.reduce(loading, QueueAction.PageLoaded(2, listOf("c"), 5))
        assertTrue(totalChanged.invalidated)
    }

    @Test fun `large collection window stays bounded and preserves selection`() {
        val items = (0 until 190_000).toList()
        val window = boundedQueueWindow(items, selectedIndex = 100_000)

        assertEquals(250, window.items.size)
        assertEquals(100_000, window.items[window.selectedIndex])
        assertEquals(99_850, window.startIndex)
    }

    @Test fun `play modes cycle in product order and map to playback behavior`() {
        val cycle = generateSequence(DEFAULT_PLAY_MODE, PlayMode::next).take(5).toList()

        assertEquals(
            listOf(
                PlayMode.ListRepeat,
                PlayMode.Shuffle,
                PlayMode.SingleRepeat,
                PlayMode.Sequence,
                PlayMode.ListRepeat,
            ),
            cycle,
        )
        assertEquals(PlayModeMapping(RepeatBehavior.All, false), PlayMode.ListRepeat.mapping)
        assertEquals(PlayModeMapping(RepeatBehavior.All, true), PlayMode.Shuffle.mapping)
        assertEquals(PlayModeMapping(RepeatBehavior.One, false), PlayMode.SingleRepeat.mapping)
        assertEquals(PlayModeMapping(RepeatBehavior.Off, false), PlayMode.Sequence.mapping)
    }

    @Test fun `sliding queue appends atomically and remains bounded`() {
        val state = SlidingQueueReducer.loading(
            SlidingQueueState(
                guids = (0 until 250).map(Int::toString),
                currentIndex = 240,
                windowStart = 0,
                firstPage = 1,
                lastPage = 5,
                knownTotal = 3500,
                sort = "createdAt,desc",
            ),
        )
        val update = SlidingQueueReducer.append(
            state,
            page = 6,
            guids = (250 until 300).map(Int::toString),
            total = 3500,
            sort = "createdAt,desc",
        )

        assertEquals(250, update.state.guids.size)
        assertEquals(50, update.removeFromStart)
        assertEquals(190, update.state.currentIndex)
        assertEquals("50", update.state.guids.first())
        assertEquals(2, update.state.firstPage)
        assertEquals(50, update.state.windowStart)
        assertFalse(update.state.reachedStart)
    }

    @Test fun `sliding queue exposes retry and rejects drift`() {
        val base = SlidingQueueState(listOf("a", "b"), 1, 0, 1, 1, 4, "title,asc")
        val failed = (1..3).fold(base) { current, _ ->
            SlidingQueueReducer.failed(SlidingQueueReducer.loading(current))
        }
        assertEquals(3, failed.failureCount)

        val drift = SlidingQueueReducer.append(
            SlidingQueueReducer.loading(base),
            page = 2,
            guids = listOf("b", "c"),
            total = 5,
            sort = "title,asc",
        )
        assertTrue(drift.state.invalidated)
    }

    @Test fun `sliding queue suppresses duplicate loads and permits manual retry`() {
        val base = SlidingQueueState(listOf("a", "b"), 1, 0, 1, 1, 4, "title,asc")
        val loading = SlidingQueueReducer.loading(base)

        assertEquals(loading, SlidingQueueReducer.loading(loading))

        val failed = SlidingQueueReducer.failed(loading)
        val retried = SlidingQueueReducer.loading(failed)
        assertTrue(retried.loading)
        assertEquals(1, retried.failureCount)
        assertFalse(retried.invalidated)
    }

    @Test fun `fresh page total enriches a restored queue with unknown total`() {
        val restored = SlidingQueueState.fromSegments(
            segments = listOf(segment(page = 1, knownTotal = null)),
            currentIndex = 40,
        )
        val update = SlidingQueueReducer.append(
            state = SlidingQueueReducer.loading(restored),
            segment = segment(page = 2, knownTotal = 6 * DEFAULT_QUEUE_PAGE_SIZE),
        )

        assertEquals(6 * DEFAULT_QUEUE_PAGE_SIZE, update.state.knownTotal)
        assertTrue(update.state.segments.all { it.knownTotal == 6 * DEFAULT_QUEUE_PAGE_SIZE })
        assertFalse(update.state.invalidated)
    }

    @Test fun `sliding queue prepends and removes the distant tail`() {
        val base = SlidingQueueReducer.loading(
            SlidingQueueState(
                guids = (50 until 300).map(Int::toString),
                currentIndex = 8,
                windowStart = 50,
                firstPage = 2,
                lastPage = 6,
                knownTotal = 500,
                sort = "createdAt,desc",
            ),
        )

        val update = SlidingQueueReducer.prepend(
            base,
            page = 1,
            guids = (0 until 50).map(Int::toString),
            total = 500,
            sort = "createdAt,desc",
        )

        assertEquals(250, update.state.guids.size)
        assertEquals(50, update.removeFromEnd)
        assertEquals(58, update.state.currentIndex)
        assertEquals("0", update.state.guids.first())
        assertTrue(update.state.reachedStart)
        assertEquals(5, update.state.lastPage)
        assertFalse(update.state.reachedEnd)
    }

    @Test fun `append eviction can reload the exact filtered head page`() {
        val original = SlidingQueueState.fromSegments(
            segments = (1..5).map { page -> segment(page, filteredOffsets = if (page == 1) setOf(3, 17) else emptySet()) },
            currentIndex = 240,
        )

        val appended = SlidingQueueReducer.append(
            state = SlidingQueueReducer.loading(original),
            segment = segment(6, filteredOffsets = setOf(4, 21)),
        )

        assertEquals(48, appended.removeFromStart)
        assertEquals(2, appended.state.firstPage)
        assertEquals(50, appended.state.windowStart)
        assertFalse(appended.state.reachedStart)
        assertEquals(1, appended.state.firstPage - 1)

        val restored = SlidingQueueReducer.prepend(
            state = SlidingQueueReducer.loading(appended.state),
            segment = segment(1, filteredOffsets = setOf(3, 17)),
        )

        assertEquals(48, restored.removeFromEnd)
        assertEquals(1, restored.state.firstPage)
        assertEquals(5, restored.state.lastPage)
        assertEquals(original.guids, restored.state.guids)
        assertEquals(original.currentIndex, restored.state.currentIndex)
        assertEquals(listOf(3, 17), restored.state.segments.first().missingSourceOffsets())
    }

    @Test fun `prepend eviction can reload the exact filtered tail page`() {
        val original = SlidingQueueState.fromSegments(
            segments = (2..6).map { page -> segment(page, filteredOffsets = if (page == 6) setOf(4, 21) else emptySet()) },
            currentIndex = 8,
        )

        val prepended = SlidingQueueReducer.prepend(
            state = SlidingQueueReducer.loading(original),
            segment = segment(1, filteredOffsets = setOf(3, 17)),
        )

        assertEquals(48, prepended.removeFromEnd)
        assertEquals(5, prepended.state.lastPage)
        assertFalse(prepended.state.reachedEnd)
        assertEquals(6, prepended.state.lastPage + 1)

        val restored = SlidingQueueReducer.append(
            state = SlidingQueueReducer.loading(prepended.state),
            segment = segment(6, filteredOffsets = setOf(4, 21)),
        )

        assertEquals(48, restored.removeFromStart)
        assertEquals(2, restored.state.firstPage)
        assertEquals(6, restored.state.lastPage)
        assertEquals(original.guids, restored.state.guids)
        assertEquals(original.currentIndex, restored.state.currentIndex)
        assertTrue(restored.state.reachedEnd)
        assertEquals(listOf(4, 21), restored.state.segments.last().missingSourceOffsets())
    }

    private fun segment(
        page: Int,
        filteredOffsets: Set<Int> = emptySet(),
        knownTotal: Int? = 6 * DEFAULT_QUEUE_PAGE_SIZE,
    ): QueuePageSegment {
        val sourceStart = (page - 1) * DEFAULT_QUEUE_PAGE_SIZE
        return QueuePageSegment(
            page = page,
            rawRowCount = DEFAULT_QUEUE_PAGE_SIZE,
            playableItems = (0 until DEFAULT_QUEUE_PAGE_SIZE)
                .filterNot(filteredOffsets::contains)
                .map { offset -> QueuePageItem("$page:$offset", sourceStart + offset) },
            sort = "createdAt,desc",
            knownTotal = knownTotal,
        )
    }

    private fun QueuePageSegment.missingSourceOffsets(): List<Int> {
        val retained = playableItems.mapTo(hashSetOf()) { it.sourceAbsoluteIndex - sourceStartIndex }
        return (0 until rawRowCount).filterNot(retained::contains)
    }
}
