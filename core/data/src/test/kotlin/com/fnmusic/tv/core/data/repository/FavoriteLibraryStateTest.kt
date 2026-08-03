package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.data.api.TrackDto
import com.fnmusic.tv.core.model.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteLibraryStateTest {
    @Test
    fun `favorite list membership is authoritative when response omits favorite flag`() {
        val track = TrackDto(guid = "track-a", title = "Song").toFavoriteDomain()

        assertTrue(track.isFavorite)
    }

    @Test
    fun `account binding isolates favorite state`() {
        val accountA = FavoriteLibraryState(namespace = "server:user-a")
            .observe("track-a", favorite = true)

        val accountB = accountA.bindNamespace("server:user-b")

        assertEquals("server:user-b", accountB.namespace)
        assertTrue(accountB.statuses.isEmpty())
        assertEquals(0L, accountB.revision)
    }

    @Test
    fun `mutation is optimistic then commits one revision`() {
        val initial = FavoriteLibraryState(namespace = "server:user")
            .observe("track-a", favorite = false)

        val (optimistic, mutation) = initial.beginMutation("track-a", fallbackFavorite = false)
        val completed = optimistic.complete(mutation)

        assertTrue(optimistic.statuses.getValue("track-a"))
        assertTrue("track-a" in optimistic.pending)
        assertTrue(completed.statuses.getValue("track-a"))
        assertFalse("track-a" in completed.pending)
        assertEquals(1L, completed.revision)
    }

    @Test
    fun `failure restores the last server confirmed favorite state`() {
        val initial = FavoriteLibraryState(namespace = "server:user")
            .observe("track-a", favorite = true)
        val (optimistic, mutation) = initial.beginMutation("track-a", fallbackFavorite = true)

        val rolledBack = optimistic.rollback(mutation, AppError.NetworkUnavailable)

        assertFalse(optimistic.statuses.getValue("track-a"))
        assertTrue(rolledBack.statuses.getValue("track-a"))
        assertEquals(AppError.NetworkUnavailable, rolledBack.error)
        assertEquals(0L, rolledBack.revision)
    }

    @Test
    fun `stale observations cannot overwrite a pending mutation`() {
        val (optimistic, _) = FavoriteLibraryState(namespace = "server:user")
            .beginMutation("track-a", fallbackFavorite = false)

        assertEquals(optimistic, optimistic.observe("track-a", favorite = false))
    }

    @Test
    fun `late mutation completion cannot cross an account namespace`() {
        val accountA = FavoriteLibraryState(namespace = "server:user-a")
        val (_, mutationA) = accountA.beginMutation("track-a", fallbackFavorite = false)
        val accountB = accountA.bindNamespace("server:user-b")

        assertEquals(accountB, accountB.complete(mutationA))
        assertEquals(accountB, accountB.rollback(mutationA, AppError.NetworkUnavailable))
    }
}
