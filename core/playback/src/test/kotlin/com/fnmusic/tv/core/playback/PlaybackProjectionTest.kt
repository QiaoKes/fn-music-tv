package com.fnmusic.tv.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
