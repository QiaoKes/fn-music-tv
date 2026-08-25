package com.mocharealm.gaze.capsule

@Suppress("NOTHING_TO_INLINE")
internal inline fun lerp(start: Double, stop: Double, fraction: Double): Double {
    return start + (stop - start) * fraction
}
