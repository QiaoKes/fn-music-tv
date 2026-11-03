package com.fnmusic.tv.core.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

/**
 * Process-wide PCM handoff between the playback service's audio pipeline and the spectrum
 * capture. Implemented as a [TeeAudioProcessor.AudioBufferSink], so the service installs
 * the battle-tested [TeeAudioProcessor] in its audio sink and this object receives every
 * decoded output buffer while it is routed to the device. No platform audio effect engine
 * is involved, so this works on every device and every emulator build.
 *
 * The handleBuffer contract hands over a live pipeline buffer; views must duplicate it and
 * never disturb the original's position, because the processor forwards it untouched.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackSpectrumHub : TeeAudioProcessor.AudioBufferSink {
    private const val RING_CAPACITY = 16_384
    private const val FRESH_WINDOW_MILLISECONDS = 160L

    private val ring = FloatArray(RING_CAPACITY)
    private var writeIndex = 0
    private var filled = 0
    private var sampleRateHz = 0
    private var channelCount = 0
    private var encoding = 0
    @Volatile private var lastWriteUptimeMs = 0L

    /** Injectable for tests; production reads the monotonic uptime clock. */
    internal var uptimeMillis: () -> Long = SystemClock::uptimeMillis

    override fun flush(sampleRateHz: Int, channelCount: Int, pcmEncoding: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = pcmEncoding
        reset()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (sampleRateHz > 0 && channelCount > 0) {
            onBuffer(buffer.duplicate(), sampleRateHz, channelCount, encoding)
        }
    }

    /** Clears stale samples when a stream restarts (format change, flush). */
    private fun reset() {
        synchronized(ring) {
            writeIndex = 0
            filled = 0
            lastWriteUptimeMs = uptimeMillis()
        }
    }

    /**
     * Stores mono-averaged frame samples of one decoded PCM buffer, bound to the given
     * stream format. Unsupported encodings are ignored silently.
     */
    internal fun onBuffer(buffer: ByteBuffer, sampleRateHz: Int, channelCount: Int, encoding: Int) {
        if (channelCount <= 0) return
        when (encoding) {
            C.ENCODING_PCM_16BIT -> appendPcm16(buffer, channelCount)
            C.ENCODING_PCM_FLOAT -> appendPcmFloat(buffer, channelCount)
            else -> Unit
        }
    }

    private fun appendPcm16(buffer: ByteBuffer, channelCount: Int) {
        val bytesPerFrame = channelCount * 2
        if (buffer.remaining() < bytesPerFrame) return
        val frames = buffer.remaining() / bytesPerFrame
        val bytes = ByteArray(frames * bytesPerFrame)
        buffer.get(bytes)
        var index = 0
        var source = 0
        while (index < frames) {
            var sum = 0f
            var channel = 0
            while (channel < channelCount) {
                val low = bytes[source].toInt() and 0xFF
                val high = bytes[source + 1].toInt()
                sum += ((high shl 8) or low) / 32768f
                channel++
                source += 2
            }
            pushSample(sum / channelCount)
            index++
        }
        markWritten()
    }

    private fun appendPcmFloat(buffer: ByteBuffer, channelCount: Int) {
        val bytesPerFrame = channelCount * 4
        if (buffer.remaining() < bytesPerFrame) return
        val frames = buffer.remaining() / bytesPerFrame
        val bytes = ByteArray(frames * bytesPerFrame)
        buffer.get(bytes)
        var index = 0
        var source = 0
        while (index < frames) {
            var sum = 0f
            var channel = 0
            while (channel < channelCount) {
                val bits = (bytes[source].toInt() and 0xFF) or
                    ((bytes[source + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[source + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[source + 3].toInt() and 0xFF) shl 24)
                sum += java.lang.Float.intBitsToFloat(bits)
                channel++
                source += 4
            }
            pushSample(sum / channelCount)
            index++
        }
        markWritten()
    }

    private fun pushSample(sample: Float) {
        synchronized(ring) {
            ring[writeIndex] = sample
            writeIndex = (writeIndex + 1) % RING_CAPACITY
            if (filled < RING_CAPACITY) filled++
        }
    }

    private fun markWritten() {
        lastWriteUptimeMs = uptimeMillis()
    }

    /**
     * Copies the most recent window into [out]. The ring must hold enough samples and they
     * must be fresh, i.e. the stream must be actively writing; paused or stalled streams
     * report `false`, which lets the UI animate the bars down.
     */
    fun snapshotWindow(out: FloatArray): Boolean {
        val fresh = uptimeMillis() - lastWriteUptimeMs < FRESH_WINDOW_MILLISECONDS
        var copied = false
        synchronized(ring) {
            if (filled >= out.size && fresh) {
                var readIndex = (writeIndex + RING_CAPACITY - out.size) % RING_CAPACITY
                for (index in out.indices) {
                    out[index] = ring[readIndex]
                    readIndex = (readIndex + 1) % RING_CAPACITY
                }
                copied = true
            }
        }
        return copied
    }
}
