package com.fnmusic.tv.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.flac.FlacLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FlacPlaybackIntegrationTest {
    @Test
    fun bundledLibflacDecodes16BitAudioToCompletion() {
        assertFlacPlayback("test-tone.flac")
    }

    @Test
    fun bundledLibflacDecodes24Bit96KhzAudioToCompletion() {
        assertFlacPlayback("test-tone-24bit-96khz.flac")
    }

    private fun assertFlacPlayback(assetName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val flacFile = File(context.cacheDir, assetName)
        instrumentation.context.assets.open(assetName).use { input ->
            flacFile.outputStream().use(input::copyTo)
        }

        val playbackFinished = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null

        try {
            assertTrue("libflacJNI failed to load", FlacLibrary.isAvailable())
            instrumentation.runOnMainSync {
                val renderersFactory = DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                player = ExoPlayer.Builder(context, renderersFactory).build().apply {
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onPlaybackStateChanged(
                            eventTime: AnalyticsListener.EventTime,
                            state: Int,
                        ) {
                            if (state == Player.STATE_ENDED) playbackFinished.countDown()
                        }

                        override fun onPlayerError(
                            eventTime: AnalyticsListener.EventTime,
                            error: PlaybackException,
                        ) {
                            playbackError.set(error)
                            playbackFinished.countDown()
                        }
                    })
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(flacFile)))
                    prepare()
                    play()
                }
            }

            assertTrue(
                "$assetName playback did not finish",
                playbackFinished.await(15, TimeUnit.SECONDS),
            )
            assertNull("$assetName playback failed", playbackError.get())
        } finally {
            player?.let { initializedPlayer ->
                instrumentation.runOnMainSync(initializedPlayer::release)
            }
            flacFile.delete()
        }
    }
}
