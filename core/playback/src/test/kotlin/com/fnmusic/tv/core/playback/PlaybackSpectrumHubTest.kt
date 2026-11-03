package com.fnmusic.tv.core.playback

import android.os.SystemClock
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackSpectrumHubTest {
    @After
    fun restoreClock() {
        PlaybackSpectrumHub.uptimeMillis = SystemClock::uptimeMillis
    }

    private fun interleaved16(values: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { value -> buffer.putShort(value.toShort()) }
        buffer.rewind()
        return buffer
    }

    @Test
    fun `mono 16 bit pcm windows preserve order and scale`() {
        PlaybackSpectrumHub.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        val samples = (1..2048).map { index -> (index - 1024) } // -1023..1024
        PlaybackSpectrumHub.onBuffer(interleaved16(samples), 48_000, 1, C.ENCODING_PCM_16BIT)

        val window = FloatArray(1024)
        assertTrue(PlaybackSpectrumHub.snapshotWindow(window))

        val expectedWindow = samples.takeLast(window.size).map { it / 32768f }
        assertTrue(
            window.toList().zip(expectedWindow).all { (actual, expected) -> abs(actual - expected) < 1e-4f },
        )
    }

    @Test
    fun `stereo frames are averaged into mono`() {
        PlaybackSpectrumHub.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        // One stereo frame: left = 30_000, right = 0 → mono average = 15_000/32768, followed
        // by silent frames until a full window exists.
        PlaybackSpectrumHub.onBuffer(interleaved16(listOf(30_000, 0)), 48_000, 2, C.ENCODING_PCM_16BIT)
        repeat(1023) { PlaybackSpectrumHub.onBuffer(interleaved16(listOf(0, 0)), 48_000, 2, C.ENCODING_PCM_16BIT) }

        val window = FloatArray(1024)
        assertTrue(PlaybackSpectrumHub.snapshotWindow(window))

        // Oldest sample in the window is the averaged stereo frame; newest is silence.
        assertEquals(15_000f / 32768f, window[0], 1e-4f)
        assertEquals(0f, window[window.size - 1], 1e-6f)
    }

    @Test
    fun `insufficient data refuses to snapshot`() {
        PlaybackSpectrumHub.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        PlaybackSpectrumHub.onBuffer(interleaved16(listOf(1, 2, 3, 4, 5, 6, 7, 8)), 48_000, 1, C.ENCODING_PCM_16BIT)

        assertFalse(PlaybackSpectrumHub.snapshotWindow(FloatArray(1024)))
    }

    @Test
    fun `unsupported encoding is ignored without breaking the pass-through contract`() {
        PlaybackSpectrumHub.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        val buffer = interleaved16(listOf(1, 2, 3, 4, 5, 6, 7, 8))
        try {
            PlaybackSpectrumHub.onBuffer(buffer, 48_000, 1, C.ENCODING_PCM_24BIT)
        } catch (error: Throwable) {
            fail("Unsupported encoding must be ignored, not blamed on the caller: $error")
        }
        assertFalse(PlaybackSpectrumHub.snapshotWindow(FloatArray(1024)))
    }
}
