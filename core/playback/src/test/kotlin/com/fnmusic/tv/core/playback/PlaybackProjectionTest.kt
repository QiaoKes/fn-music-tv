package com.fnmusic.tv.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackProjectionTest {
    @Test
    fun `current item identity and all presentation fields are captured coherently`() {
        val currentB = MediaItem.Builder()
            .setMediaId("track-b")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Title B")
                    .setArtist("Artist B")
                    .setArtworkUri(Uri.parse("https://example.test/b.jpg"))
                    .setExtras(Bundle().apply {
                        putString(AUDIO_FORMAT_KEY, "FLAC")
                        putString(COVER_ID_KEY, "cover-b")
                    })
                    .build(),
            )
            .build()

        assertEquals(
            CapturedNowPlayingFields(
                mediaId = "track-b",
                title = "Title B",
                artist = "Artist B",
                audioFormat = "FLAC",
                coverId = "cover-b",
                artworkUrl = "https://example.test/b.jpg",
            ),
            captureNowPlayingFields(currentB),
        )
    }

    @Test
    fun `progress-only events reuse the existing queue projection`() {
        assertFalse(
            shouldRebuildPlaybackQueue(
                timelineChanged = false,
                mediaItemTransition = false,
                mediaMetadataChanged = false,
            ),
        )
    }

    @Test
    fun `structural and presentation events rebuild the queue projection`() {
        assertTrue(shouldRebuildPlaybackQueue(true, false, false))
        assertTrue(shouldRebuildPlaybackQueue(false, true, false))
        assertTrue(shouldRebuildPlaybackQueue(false, false, true))
    }

    @Test
    fun `playlist installation does not restart presentation requests after manual projection`() {
        assertFalse(
            shouldForcePresentationProjection(
                hasMediaItem = true,
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
            ),
        )
        assertTrue(
            shouldForcePresentationProjection(
                hasMediaItem = true,
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
            ),
        )
        assertFalse(
            shouldForcePresentationProjection(
                hasMediaItem = false,
                transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            ),
        )
    }

    @Test
    fun `only bad http status failures request session verification`() {
        val badStatus = PlaybackFailure(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            "ERROR_CODE_IO_BAD_HTTP_STATUS",
        )
        val unavailable = PlaybackFailure(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
        )

        assertTrue(badStatus.requiresSessionVerification)
        assertFalse(unavailable.requiresSessionVerification)
    }

    @Test
    fun `auto advance only targets a different existing queue index`() {
        assertEquals(3, autoAdvanceTargetIndex(itemCount = 5, currentIndex = 2, nextIndex = 3))
        assertNull(autoAdvanceTargetIndex(itemCount = 5, currentIndex = 2, nextIndex = 2))
        assertNull(autoAdvanceTargetIndex(itemCount = 1, currentIndex = 0, nextIndex = 0))
        assertNull(autoAdvanceTargetIndex(itemCount = 0, currentIndex = 0, nextIndex = 0))
        assertNull(autoAdvanceTargetIndex(itemCount = 4, currentIndex = 3, nextIndex = 3))
    }

    @Test
    fun `display text appends diagnostics and auto advance countdown`() {
        val plain = PlaybackFailure(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
        )
        assertEquals("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", plain.displayText)

        val withDiagnostics = plain.copy(diagnostics = "HTTP 200, type=application/json")
        assertEquals(
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED\nHTTP 200, type=application/json",
            withDiagnostics.displayText,
        )

        val withCountdown = withDiagnostics.copy(autoAdvanceSeconds = 3)
        assertEquals(
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED\nHTTP 200, type=application/json，3 秒后自动播放下一首",
            withCountdown.displayText,
        )
    }
}
