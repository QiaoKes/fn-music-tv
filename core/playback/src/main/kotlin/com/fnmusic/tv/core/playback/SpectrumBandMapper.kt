package com.fnmusic.tv.core.playback

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Number of spectrum bars rendered by the 频谱 playback style. */
const val SPECTRUM_BAND_COUNT = 48

/**
 * Normalized (every value within 0f..1f) spectrum frame shared with the playback UI.
 * The array is reused frame to frame; consumers must copy it if they need to retain it.
 */
class PlaybackSpectrumFrame(val bands: FloatArray)

/**
 * Pure math between Visualizer captures and log-spaced spectrum bands. Free of Android
 * API dependencies so it stays unit testable.
 *
 * The native Visualizer FFT transport is unreliable on many devices (zeros or odd scaling),
 * so the capture feeds time-domain waveform bytes through [fftMagnitudes] instead.
 */
internal object SpectrumBandMapper {
    private const val DC_SKIP_BINS = 2
    private const val BAND_SPACING_EXPONENT = 0.82

    /**
     * Returns band boundaries (inclusive start, exclusive end) over complex FFT bins. Bin 0
     * and 1 (DC and near-DC rumble) are skipped. Spacing follows a power curve so the low
     * end does not collapse into a single bar.
     */
    fun edges(binCount: Int, bandCount: Int): IntArray {
        val firstBin = DC_SKIP_BINS
        val lastBin = (binCount - 1).coerceAtLeast(firstBin)
        val span = (lastBin - firstBin).coerceAtLeast(1)
        val result = IntArray(bandCount + 1) { index ->
            when (index) {
                0 -> firstBin
                bandCount -> lastBin + 1
                else -> (firstBin + span * (index.toDouble() / bandCount).pow(BAND_SPACING_EXPONENT))
                    .roundToInt()
            }
        }
        for (index in 1..bandCount) {
            if (result[index] <= result[index - 1]) result[index] = result[index - 1] + 1
        }
        return result
    }

    /** Precomputes a Hann window of [size] samples so one sinusoid stays one bin wide. */
    fun hannWindow(size: Int): FloatArray {
        require(size > 0)
        return FloatArray(size) { index ->
            (1.0 - cos(2.0 * PI * index / size)).toFloat() / 2f
        }
    }

    /**
     * In-place iterative radix-2 FFT of the windowed time-domain samples in [samples],
     * filling [out] with magnitudes for bins `0..samples.size/2-1` where
     * `samples.size == re.size == im.size` is a power of two. Scaling: a full-scale
     * (`±1.0`) sinusoid lands at magnitude ≈ 1.0.
     */
    fun fftMagnitudes(samples: FloatArray, re: FloatArray, im: FloatArray, out: FloatArray) {
        val size = samples.size
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        require(re.size == size && im.size == size && out.size >= size / 2)
        for (index in 0 until size) {
            re[index] = samples[index]
            im[index] = 0f
        }
        var swapped = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (swapped and bit != 0) {
                swapped = swapped xor bit
                bit = bit shr 1
            }
            swapped = swapped or bit
            if (index < swapped) {
                val real = re[index]
                re[index] = re[swapped]
                re[swapped] = real
                val imaginary = im[index]
                im[index] = im[swapped]
                im[swapped] = imaginary
            }
        }
        var width = 2
        while (width <= size) {
            val half = width shr 1
            val stepAngle = -2.0 * PI / width
            for (block in 0 until size / width) {
                val evenBase = block * width
                for (step in 0 until half) {
                    val twiddleAngle = stepAngle * step
                    val wReal = cos(twiddleAngle).toFloat()
                    val wImag = sin(twiddleAngle).toFloat()
                    val even = evenBase + step
                    val odd = even + half
                    val oddReal = re[odd] * wReal - im[odd] * wImag
                    val oddImag = re[odd] * wImag + im[odd] * wReal
                    re[odd] = re[even] - oddReal
                    im[odd] = im[even] - oddImag
                    re[even] += oddReal
                    im[even] += oddImag
                }
            }
            width *= 2
        }
        val scale = size / 4f // Hann-windowed full-scale sine |X| ≈ size/4 → ≈ 1.0
        for (bin in 0 until size / 2) {
            out[bin] = sqrt(re[bin] * re[bin] + im[bin] * im[bin]) / scale
        }
    }

    /**
     * Fills [out] with peak magnitudes from one value-per-bin magnitude buffer, each band
     * normalized to 0f..1f using a dB floor and a mild high-frequency gain compensation.
     */
    fun aggregate(magnitudes: FloatArray, edges: IntArray, out: FloatArray) {
        val binCount = magnitudes.size
        val bandCount = out.size
        val lastBand = (bandCount - 1).coerceAtLeast(1)
        for (band in 0 until bandCount) {
            val start = edges[band].coerceAtMost(binCount)
            val end = (if (band == bandCount - 1) binCount else edges[band + 1]).coerceAtMost(binCount)
            var peak = 0f
            for (bin in start until end) {
                val magnitude = magnitudes[bin]
                if (magnitude > peak) peak = magnitude
            }
            val db = 20f * log10(peak.coerceAtLeast(1e-4f))
            val tilt = 1f + 0.45f * band / lastBand
            val normalized = (db - SPECTRUM_SILENCE_FLOOR_DB) / (0f - SPECTRUM_SILENCE_FLOOR_DB) * tilt
            out[band] = normalized.coerceIn(0f, 1f)
        }
    }
}

/** Signal level treated as silence, in dB relative to a full-scale sinusoid. */
internal const val SPECTRUM_SILENCE_FLOOR_DB = -38f

/** Per-callback multiplier a band decays with after its peak has passed. */
internal const val SPECTRUM_BAND_DECAY = 0.80f
