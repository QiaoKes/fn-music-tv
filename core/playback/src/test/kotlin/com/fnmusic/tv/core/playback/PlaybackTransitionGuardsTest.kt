package com.fnmusic.tv.core.playback

import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.RoamNode
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.PlayMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionGuardsTest {
    @Test
    fun `queue removal selects the following item when current is removed`() {
        assertEquals(
            QueueRemovalPlan(removeIndex = 1, remainingCount = 2, nextCurrentIndex = 1),
            queueRemovalPlan(itemCount = 3, currentIndex = 1, removeIndex = 1),
        )
    }

    @Test
    fun `queue removal falls back to the previous item when current tail is removed`() {
        assertEquals(
            QueueRemovalPlan(removeIndex = 2, remainingCount = 2, nextCurrentIndex = 1),
            queueRemovalPlan(itemCount = 3, currentIndex = 2, removeIndex = 2),
        )
    }

    @Test
    fun `queue removal leaves no current item after deleting the only item`() {
        assertEquals(
            QueueRemovalPlan(removeIndex = 0, remainingCount = 0, nextCurrentIndex = null),
            queueRemovalPlan(itemCount = 1, currentIndex = 0, removeIndex = 0),
        )
        assertNull(queueRemovalPlan(itemCount = 1, currentIndex = 0, removeIndex = 1))
    }

    @Test
    fun `initial namespace bind may adopt service media but account rebind must reset it`() {
        assertEquals(PlaybackNamespaceBinding.Initial, playbackNamespaceBinding(null, "server:user-a"))
        assertEquals(
            PlaybackNamespaceBinding.Same,
            playbackNamespaceBinding("server:user-a", "server:user-a"),
        )
        assertEquals(
            PlaybackNamespaceBinding.Rebind,
            playbackNamespaceBinding("server:user-a", "server:user-b"),
        )
    }

    @Test
    fun `configured service queue is reprojected and only an empty unrestored queue reads storage`() {
        assertTrue(hasConfiguredQueue(restored = true, mediaItemCount = 0))
        assertTrue(hasConfiguredQueue(restored = false, mediaItemCount = 1))
        assertFalse(hasConfiguredQueue(restored = false, mediaItemCount = 0))
    }

    @Test
    fun `shuffle acknowledgement requires generation revision exact active IDs and echoed order`() {
        val pending = PendingShuffleActivation(
            generation = 4,
            baseRevision = 9,
            activationRevision = 10,
            canonicalIds = listOf("a", "b", "c"),
            orderIds = listOf("c", "a", "b"),
            fallbackMode = PlayMode.ListRepeat,
            persistOnAccept = true,
            persistFallbackOnReject = false,
        )
        val acknowledgement = ShuffleAcknowledgement(10, listOf("c", "a", "b"))

        assertTrue(pending.accepts(acknowledgement, 4, 9, listOf("a", "b", "c")))
        assertFalse(pending.accepts(acknowledgement, 5, 9, listOf("a", "b", "c")))
        assertFalse(pending.accepts(acknowledgement, 4, 10, listOf("a", "b", "c")))
        assertFalse(pending.accepts(acknowledgement, 4, 9, listOf("a", "b", "c", "d")))
        assertFalse(pending.accepts(ShuffleAcknowledgement(11, acknowledgement.orderIds), 4, 9, pending.canonicalIds))
        assertFalse(pending.accepts(ShuffleAcknowledgement(10, listOf("a", "b", "c")), 4, 9, pending.canonicalIds))
    }

    @Test
    fun `queue mutation during shuffle acknowledgement makes the old activation stale`() {
        val old = pendingShuffle(
            baseRevision = 20,
            activationRevision = 21,
            canonical = listOf("a", "b"),
            order = listOf("b", "a"),
        )
        val oldAck = ShuffleAcknowledgement(21, listOf("b", "a"))

        assertFalse(old.accepts(oldAck, 3, 20, listOf("a", "b", "c")))

        val reapplied = pendingShuffle(
            baseRevision = 21,
            activationRevision = 22,
            canonical = listOf("a", "b", "c"),
            order = listOf("b", "c", "a"),
        )
        assertTrue(
            reapplied.accepts(
                ShuffleAcknowledgement(22, listOf("b", "c", "a")),
                currentGeneration = 3,
                currentRevision = 21,
                currentCanonicalIds = listOf("a", "b", "c"),
            ),
        )
    }

    @Test
    fun `roam rejects an unchanged first response before preparing it`() = runBlocking {
        var prepares = 0

        val failure = roamFailure {
            resolvePlayableRoam(
                initial = window("cursor"),
                direction = RoamDirection.Next,
                currentCursor = "cursor",
                prepare = {
                    prepares++
                    playback(it.current.track)
                },
                move = { _, _ -> error("unchanged cursor must stop immediately") },
            )
        }

        assertEquals(AppError.CollectionChanged, failure.error)
        assertEquals(0, prepares)
    }

    @Test
    fun `roam detects a cursor cycle and never prepares a repeated node`() = runBlocking {
        val prepared = mutableListOf<String>()
        val nextById = mapOf("a" to window("b"), "b" to window("a"))

        val failure = roamFailure {
            resolvePlayableRoam(
                initial = window("a"),
                direction = RoamDirection.Next,
                currentCursor = "current",
                prepare = {
                    prepared += it.current.roamId
                    throw AppException(AppError.UnavailableTrack)
                },
                move = { _, id -> nextById.getValue(id) },
            )
        }

        assertEquals(AppError.CollectionChanged, failure.error)
        assertEquals(listOf("a", "b"), prepared)
    }

    @Test
    fun `roam inspects at most eight unavailable windows`() = runBlocking {
        var prepares = 0
        var moves = 0

        val failure = roamFailure {
            resolvePlayableRoam(
                initial = window("1"),
                direction = RoamDirection.Next,
                currentCursor = "current",
                prepare = {
                    prepares++
                    throw AppException(AppError.TranscodeUnavailable)
                },
                move = { _, id ->
                    moves++
                    window((id.toInt() + 1).toString())
                },
            )
        }

        assertEquals(AppError.Empty, failure.error)
        assertEquals(8, prepares)
        assertEquals(7, moves)
    }

    @Test
    fun `roam session cancellation is propagated before another request`() = runBlocking {
        val cancellation = CancellationException("replaced")
        var checks = 0

        val thrown = try {
            resolvePlayableRoam(
                initial = window("a"),
                direction = RoamDirection.Next,
                currentCursor = "current",
                ensureCurrent = {
                    checks++
                    if (checks == 2) throw cancellation
                },
                prepare = { throw AppException(AppError.UnavailableTrack) },
                move = { _, _ -> window("b") },
            )
            null
        } catch (cause: CancellationException) {
            cause
        }

        assertSame(cancellation, thrown)
        assertEquals(2, checks)
    }

    @Test
    fun `natural completion token is single flight until the media item changes`() {
        val gate = RoamAutoAdvanceGate()

        assertTrue(gate.tryConsume(7, "track-a"))
        assertFalse(gate.tryConsume(7, "track-a"))
        assertTrue(gate.tryConsume(7, "track-b"))
        gate.reset()
        assertTrue(gate.tryConsume(7, "track-b"))
    }

    private fun pendingShuffle(
        baseRevision: Long,
        activationRevision: Long,
        canonical: List<String>,
        order: List<String>,
    ) = PendingShuffleActivation(
        generation = 3,
        baseRevision = baseRevision,
        activationRevision = activationRevision,
        canonicalIds = canonical,
        orderIds = order,
        fallbackMode = PlayMode.ListRepeat,
        persistOnAccept = true,
        persistFallbackOnReject = false,
    )

    private suspend fun roamFailure(block: suspend () -> Unit): AppException = try {
        block()
        error("Expected roam resolution to fail")
    } catch (failure: AppException) {
        failure
    }

    private fun window(id: String): RoamWindow = RoamWindow(
        previous = null,
        current = RoamNode(id, track(id)),
        next = null,
    )

    private fun playback(track: Track) = PlaybackTrack(
        track = track,
        streamUrl = "https://example.test/${track.guid.value}.flac",
        artworkUrl = null,
    )

    private fun track(id: String) = Track(
        guid = TrackGuid("track-$id"),
        title = "Track $id",
        artistName = null,
        albumName = null,
        coverId = null,
        durationMs = null,
        isCue = false,
        accessStatus = 0,
        audioFormat = "FLAC",
    )
}
