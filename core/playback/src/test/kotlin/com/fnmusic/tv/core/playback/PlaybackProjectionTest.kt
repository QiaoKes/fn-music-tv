package com.fnmusic.tv.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
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
}
