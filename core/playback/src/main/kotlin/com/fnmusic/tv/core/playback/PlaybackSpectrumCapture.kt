package com.fnmusic.tv.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Powers the 频谱 player style from the decoded PCM tapped inside the playback service's
 * audio pipeline ([PlaybackSpectrumProcessor] -> [PlaybackSpectrumHub]). An in-process
 * worker window-snapshots, FFTs, and normalizes bars roughly 50 times a second while the
 * style composes and playback runs; no platform audio effect engine is involved, so this
 * works on devices and emulator builds whose `Visualizer` transport is unavailable.
 *
 * Two independent switches:
 * - [setActive] – the 频谱 player style became (in)visible: stops the worker.
 * - [setRunning] – playback (un)paused: keeps the worker, but only fresh PCM publishes.
 */
class PlaybackSpectrumCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workLoop: Job? = null
    private val windowTable = SpectrumBandMapper.hannWindow(WINDOW_SIZE)
    private val samples = FloatArray(WINDOW_SIZE)
    private val windowed = FloatArray(WINDOW_SIZE)
    private val fftReal = FloatArray(WINDOW_SIZE)
    private val fftImag = FloatArray(WINDOW_SIZE)
    private val magnitudes = FloatArray(WINDOW_SIZE / 2)
    private val smoothed = FloatArray(SPECTRUM_BAND_COUNT)
    private val target = FloatArray(SPECTRUM_BAND_COUNT)
    private val edges = SpectrumBandMapper.edges(WINDOW_SIZE / 2, SPECTRUM_BAND_COUNT)
    private val _frame = MutableStateFlow(PlaybackSpectrumFrame(FloatArray(SPECTRUM_BAND_COUNT)))
    val frame: StateFlow<PlaybackSpectrumFrame> = _frame.asStateFlow()
    @Volatile private var active = false
    @Volatile private var running = false
    private var levelEstimate = 0f

    fun setActive(value: Boolean) {
        active = value
        reconcileWorker()
    }

    fun setRunning(value: Boolean) {
        running = value
    }

    private fun reconcileWorker() {
        synchronized(this) {
            val runningLoop = if (active) workLoop?.takeIf { it.isActive } else null
            if (runningLoop != null) return
            workLoop?.cancel()
            workLoop = if (active) scope.launch { runLoop() } else null
        }
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive && active) {
            if (running && PlaybackSpectrumHub.snapshotWindow(samples)) {
                for (index in 0 until WINDOW_SIZE) {
                    windowed[index] = samples[index] * windowTable[index]
                }
                SpectrumBandMapper.fftMagnitudes(windowed, fftReal, fftImag, magnitudes)
                applyAdaptiveGain(magnitudes)
                SpectrumBandMapper.aggregate(magnitudes, edges, target)
                for (index in 0 until SPECTRUM_BAND_COUNT) {
                    val peak = target[index]
                    val held = smoothed[index] * SPECTRUM_BAND_DECAY
                    smoothed[index] = if (peak > held) peak else held
                }
                _frame.value = PlaybackSpectrumFrame(smoothed)
            }
            delay(CAPTURE_INTERVAL_MILLISECONDS)
        }
    }

    /** Keeps bars lively at low listening volumes; loud input stays unscaled. */
    private fun applyAdaptiveGain(magnitudes: FloatArray) {
        var peak = 0f
        for (magnitude in magnitudes) {
            if (magnitude > peak) peak = magnitude
        }
        levelEstimate = levelEstimate * 0.86f + peak * 0.14f
        val gain = (0.8f / levelEstimate.coerceAtLeast(0.03f)).coerceIn(1f, 5f)
        if (gain <= 1f) return
        for (index in magnitudes.indices) {
            magnitudes[index] = (magnitudes[index] * gain).coerceAtMost(2f)
        }
    }

    internal companion object {
        internal const val WINDOW_SIZE = 1024

        /** Roughly 50 captures per second keeps recomposition cheap and decay natural. */
        internal const val CAPTURE_INTERVAL_MILLISECONDS = 18L
    }
}
