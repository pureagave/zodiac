package org.pureagave.zodiac.beacon

/**
 * Turns a stream of linear-acceleration magnitudes (gravity already removed, in
 * m/s²) into discrete shock/impact events. Fires when a sample crosses
 * [thresholdG], then stays quiet for [refractoryMs] so one bump reads as one
 * event rather than a burst of them. Pure + stateful — feed it every
 * accelerometer sample; the caller broadcasts a `$ZSHK` when it returns non-null.
 */
class ShockDetector(
    private val thresholdG: Double = DEFAULT_THRESHOLD_G,
    private val refractoryMs: Long = DEFAULT_REFRACTORY_MS,
) {
    // null = never fired. A nullable sentinel (not Long.MIN_VALUE) so the very
    // first sample at nowMs=0 can't underflow the refractory subtraction.
    private var lastEventMs: Long? = null

    /**
     * Feed one sample: linear-accel magnitude in m/s² at monotonic [nowMs].
     * Returns the peak magnitude in g of a fired event, or null if this sample
     * didn't trip one (below threshold, or still inside the refractory window).
     */
    fun sample(
        magnitudeMps2: Double,
        nowMs: Long,
    ): Double? {
        val g = magnitudeMps2 / G
        if (g < thresholdG) return null
        val last = lastEventMs
        if (last != null && nowMs - last < refractoryMs) return null
        lastEventMs = nowMs
        return g
    }

    private companion object {
        const val G = 9.80665 // m/s² per g
        const val DEFAULT_THRESHOLD_G = 1.5 // a firm bump/impact, not normal driving jostle
        const val DEFAULT_REFRACTORY_MS = 500L
    }
}
