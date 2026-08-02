package com.fnmusic.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppContainerRuntimeTest {
    @Test
    fun `invalidated session finishes playback and namespace cleanup after collector cancellation`() = runBlocking {
        val playbackStarted = CompletableDeferred<Unit>()
        val releasePlayback = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val cleanup = launch {
            clearInvalidatedPlaybackSession(
                departingNamespace = "server:user",
                clearPlaybackSession = {
                    playbackStarted.complete(Unit)
                    releasePlayback.await()
                    events += "playback"
                },
                invalidateNamespace = { events += "namespace:$it" },
                clearArtwork = { events += "global-artwork" },
            )
        }
        playbackStarted.await()

        cleanup.cancel()
        releasePlayback.complete(Unit)
        cleanup.join()

        assertEquals(listOf("playback", "namespace:server:user"), events)
    }

    @Test
    fun `invalidated startup session falls back to global artwork cleanup without a namespace`() = runBlocking {
        val events = mutableListOf<String>()

        clearInvalidatedPlaybackSession(
            departingNamespace = null,
            clearPlaybackSession = { events += "playback" },
            invalidateNamespace = { events += "namespace:$it" },
            clearArtwork = { events += "global-artwork" },
        )

        assertEquals(listOf("playback", "global-artwork"), events)
    }
}
