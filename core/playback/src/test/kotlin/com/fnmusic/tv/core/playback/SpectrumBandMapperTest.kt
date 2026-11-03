package com.fnmusic.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumBandMapperTest {
    private val captureSize = PlaybackSpectrumCapture.WINDOW_SIZE
    private val binCount = captureSize / 2
    private val window = SpectrumBandMapper.hannWindow(captureSize)
    private val re = FloatArray(captureSize)
    private val im = FloatArray(captureSize)
    private val magnitudes = FloatArray(binCount)

    private fun magnitudesAt(toneBin: Int, amplitude: Float = 1f): FloatArray {
        val samples = FloatArray(captureSize) { index ->
            sin(2.0 * PI * toneBin * index / captureSize).toFloat() * amplitude * window[index]
        }
        SpectrumBandMapper.fftMagnitudes(samples, re, im, magnitudes)
        return magnitudes
    }

    @Test
    fun `edges cover the usable range once each`() {
        val edges = SpectrumBandMapper.edges(binCount, SPECTRUM_BAND_COUNT)

        assertEquals(SPECTRUM_BAND_COUNT + 1, edges.size)
        assertEquals(2, edges.first())
        assertEquals(binCount, edges.last())
        for (index in 1..SPECTRUM_BAND_COUNT) {
            assertTrue(edges[index] > edges[index - 1])
        }
    }

    @Test
    fun `silence maps every band to zero`() {
        magnitudesAt(0, amplitude = 0f)
        val edges = SpectrumBandMapper.edges(binCount, SPECTRUM_BAND_COUNT)
        val out = FloatArray(SPECTRUM_BAND_COUNT)

        SpectrumBandMapper.aggregate(magnitudes, edges, out)

        assertEquals(FloatArray(SPECTRUM_BAND_COUNT).toList(), out.toList())
    }

    @Test
    fun `full-scale integer-bin tone lands near magnitude one`() {
        val magnitudes = magnitudesAt(64)

        assertEquals(1f, magnitudes.slice(63..65).max(), 0.2f)
    }

    @Test
    fun `a dominant bin keeps its tone localized to one bin`() {
        val magnitudes = magnitudesAt(64)
        val peak = magnitudes.max()

        val dominantBin = magnitudes.indices.maxBy { magnitudes[it] }
        assertEquals(64, dominantBin)

        val leakage = magnitudes.filterIndexed { bin, value -> value != peak && (bin - 64 !in -2..2) }
        assertTrue(leakage.all { it < peak * 0.05f })
    }

    @Test
    fun `high frequency tone lands in the top bands`() {
        val edges = SpectrumBandMapper.edges(binCount, SPECTRUM_BAND_COUNT)
        magnitudesAt(binCount - 8)
        val out = FloatArray(SPECTRUM_BAND_COUNT)

        SpectrumBandMapper.aggregate(magnitudes, edges, out)

        val activeBand = out.indices.last { out[it] > 0.1f }
        assertEquals(SPECTRUM_BAND_COUNT - 1, activeBand)
        assertTrue(out.first() < 0.01f)
    }

    @Test
    fun `aggregate stays normalized even for a full scale tone`() {
        val edges = SpectrumBandMapper.edges(binCount, SPECTRUM_BAND_COUNT)
        magnitudesAt(binCount - 8)
        val out = FloatArray(SPECTRUM_BAND_COUNT)

        SpectrumBandMapper.aggregate(magnitudes, edges, out)

        assertTrue(out.all { it in 0f..1f })
    }
}
