package com.fnmusic.tv.ui

import com.fnmusic.tv.core.model.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeatureArtworkTest {
    @Test
    fun featureArtworkSlotsPrioritizePrimaryAndDeduplicateCoverIds() {
        val slots = featureArtworkSlots(
            primary = listOf(
                FeatureArtworkItem("One", "cover-1"),
                FeatureArtworkItem("Duplicate", "cover-1"),
                FeatureArtworkItem("Two", " cover-2 "),
            ),
            fallback = listOf(
                FeatureArtworkItem("Three", "cover-3"),
                FeatureArtworkItem("Four", "cover-4"),
            ),
        )

        assertEquals(listOf("cover-1", "cover-2", "cover-3"), slots.map { it.coverId })
        assertEquals(listOf("One", "Two", "Three"), slots.map { it.title })
    }

    @Test
    fun featureArtworkSlotsExcludeItemsWithoutCovers() {
        val slots = featureArtworkSlots(
            primary = listOf(
                FeatureArtworkItem("Missing", null),
                FeatureArtworkItem("Blank", "  "),
                FeatureArtworkItem("One", "cover-1"),
            ),
        )

        assertEquals(listOf(FeatureArtworkItem("One", "cover-1")), slots)
        assertTrue(featureArtworkSlots(emptyList()).isEmpty())
    }

    @Test
    fun featureArtworkSlotsRespectRequestedLimit() {
        val slots = featureArtworkSlots(
            primary = (1..5).map { FeatureArtworkItem("Track $it", "cover-$it") },
            limit = 2,
        )

        assertEquals(2, slots.size)
        assertEquals(listOf("cover-1", "cover-2"), slots.map { it.coverId })
        assertTrue(featureArtworkSlots(emptyList(), limit = 0).isEmpty())
    }

    @Test
    fun favoritePreviewRefreshesOnlyWhenRevisionChanges() {
        assertTrue(shouldRefreshPagedPreview(loadedRevision = null, currentRevision = 0L))
        assertFalse(shouldRefreshPagedPreview(loadedRevision = 7L, currentRevision = 7L))
        assertTrue(shouldRefreshPagedPreview(loadedRevision = 7L, currentRevision = 8L))
    }

    @Test
    fun favoritePreviewDoesNotRepeatSuccessfulRevision() = runBlocking {
        val store = LibraryRetainedStateStore(CoroutineScope(Dispatchers.Unconfined))
        val state = RetainedPagedGridState<String>()
        var requests = 0
        val loader: suspend (Int) -> Page<String> = {
            requests += 1
            Page(listOf("favorite-$requests"), page = 1, pageSize = 1, total = 1, sort = "name")
        }

        store.loadFirstPageForRevision(state, revision = 3L, loader = loader, key = { it })
        store.loadFirstPageForRevision(state, revision = 3L, loader = loader, key = { it })

        assertEquals(1, requests)
        assertEquals(listOf("favorite-1"), state.snapshot.entries)
    }

    @Test
    fun favoritePreviewReloadsLatestRevisionWhenRevisionChangesDuringRequest() = runBlocking {
        val store = LibraryRetainedStateStore(this)
        val state = RetainedPagedGridState<String>()
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        var requests = 0
        val loader: suspend (Int) -> Page<String> = {
            requests += 1
            if (requests == 1) {
                firstRequestStarted.complete(Unit)
                releaseFirstRequest.await()
            }
            Page(listOf("favorite-$requests"), page = 1, pageSize = 1, total = 1, sort = "name")
        }

        store.loadFirstPageForRevision(state, revision = 3L, loader = loader, key = { it })
        firstRequestStarted.await()
        store.loadFirstPageForRevision(state, revision = 4L, loader = loader, key = { it })
        releaseFirstRequest.complete(Unit)
        while (state.loading) yield()

        assertEquals(2, requests)
        assertEquals(4L, state.contentRevision)
        assertEquals(listOf("favorite-2"), state.snapshot.entries)
    }
}
