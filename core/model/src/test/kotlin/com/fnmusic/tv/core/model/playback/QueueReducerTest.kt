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
    }
}
