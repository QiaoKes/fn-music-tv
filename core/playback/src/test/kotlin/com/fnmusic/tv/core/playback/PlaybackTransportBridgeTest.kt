package com.fnmusic.tv.core.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackTransportBridgeTest {
    @After
    fun tearDown() {
        PlaybackTransportBridge.unregister()
    }

    @Test
    fun `restoring suppresses every navigation method`() {
        val fake = RecordingPlayer()
        val routing = RoutingPlayer(fake.player)

        routing.seekToNext()
        routing.seekToNextMediaItem()
        routing.seekToPrevious()
        routing.seekToPreviousMediaItem()

        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test
    fun `normal navigation canonicalizes method families and previous threshold`() {
        val fake = RecordingPlayer(positionMs = 3_000L)
        val routing = RoutingPlayer(fake.player)
        PlaybackTransportBridge.register { error("Normal transport must not post roam work") }
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)

        routing.seekToNext()
        routing.seekToNextMediaItem()
        routing.seekToPrevious()
        fake.positionMs = 3_001L
        routing.seekToPreviousMediaItem()

        assertEquals(
            listOf("nextItem", "nextItem", "previousItem", "seek:0"),
            fake.calls,
        )
    }

    @Test
    fun `roam navigation posts once per call without touching delegate`() {
        val fake = RecordingPlayer()
        val routing = RoutingPlayer(fake.player)
        val routed = mutableListOf<PlaybackTransportDirection>()
        PlaybackTransportBridge.register(routed::add)
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Roam)

        routing.seekToNext()
        routing.seekToNextMediaItem()
        routing.seekToPrevious()
        routing.seekToPreviousMediaItem()

        assertEquals(
            listOf(
                PlaybackTransportDirection.Next,
                PlaybackTransportDirection.Next,
                PlaybackTransportDirection.Previous,
                PlaybackTransportDirection.Previous,
            ),
            routed,
        )
        assertEquals(emptyList<String>(), fake.calls)
        assertTrue(routing.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(routing.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test
    fun `single repeat manual navigation changes item and wraps`() {
        val fake = RecordingPlayer(
            repeatMode = Player.REPEAT_MODE_ONE,
            mediaItemCount = 3,
            currentIndex = 2,
        )
        val routing = RoutingPlayer(fake.player)
        PlaybackTransportBridge.register { error("Normal transport must not post roam work") }
        PlaybackTransportBridge.setOwnership(PlaybackTransportOwnership.Normal)

        routing.seekToNextMediaItem()
        fake.currentIndex = 0
        routing.seekToPreviousMediaItem()

        assertEquals(listOf("seekIndex:0:0", "seekIndex:2:0"), fake.calls)
    }

    @Test
    fun `shuffle validation returns canonical indices only for a permutation`() {
        assertArrayEquals(
            intArrayOf(2, 0, 1),
            validatedShuffleIndices(listOf("a", "b", "c"), listOf("c", "a", "b")),
        )
        assertNull(validatedShuffleIndices(listOf("a", "b"), listOf("a")))
        assertNull(validatedShuffleIndices(listOf("a", "b"), listOf("a", "a")))
        assertNull(validatedShuffleIndices(listOf("a", ""), listOf("", "a")))
        assertNull(validatedShuffleIndices(listOf("a", "a"), listOf("a", "a")))
    }

    private class RecordingPlayer(
        positionMs: Long = 0L,
        private val repeatMode: Int = Player.REPEAT_MODE_OFF,
        private val mediaItemCount: Int = 0,
        currentIndex: Int = 0,
    ) {
        val calls = mutableListOf<String>()
        var positionMs = positionMs
        var currentIndex = currentIndex
        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getCurrentPosition" -> this.positionMs
                "getRepeatMode" -> repeatMode
                "getMediaItemCount" -> mediaItemCount
                "getCurrentMediaItemIndex" -> this.currentIndex
                "seekToNextMediaItem" -> calls.add("nextItem")
                "seekToPreviousMediaItem" -> calls.add("previousItem")
                "seekTo" -> if (arguments?.size == 2) {
                    calls.add("seekIndex:${arguments[0]}:${arguments[1]}")
                } else {
                    calls.add("seek:${arguments?.lastOrNull()}")
                }
                "isCommandAvailable" -> false
                "hasNextMediaItem", "hasPreviousMediaItem" -> false
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecordingPlayer"
                else -> defaultValue(method.returnType)
            }
        } as Player

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Void.TYPE -> null
            else -> null
        }
    }
}
