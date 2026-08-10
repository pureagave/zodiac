package org.pureagave.zodiac.beacon

/**
 * Turns a stream of linear-acceleration magnitudes (gravity already removed, in
 * m/s²) into discrete shock/impact events. A sample crossing [thresholdG] opens
 * a [peakWindowMs] window rather than firing immediately: every sample in that
 * window is compared against the running maximum, and the *peak* of the window
 * is what gets reported when it closes (AUDIT-2026-08-09 C7 — the previous
 * onset-only design reported the leading edge of the impulse, which at ~20 ms
 * sampling can be roughly half the true peak). [refractoryMs] then holds quiet
 * so one bump reads as one event rather than a burst of them; it starts when
 * the window closes and the peak is reported, not when it opens, so a
 * genuinely re-triggering series of samples inside the window can't shorten
 * the post-event quiet period. Pure + stateful — feed it every accelerometer
 * sample; the caller broadcasts a `$ZSHK` when it returns non-null.
 *
 * Latency: reporting a shock is delayed by up to [peakWindowMs] (default
 * 120 ms — a handful of samples at the sensor's ~20 ms `SENSOR_DELAY_GAME`
 * cadence, comfortably spanning onset-to-peak for a bump/impact impulse on a
 * ground vehicle without adding materially to it). That delay is acceptable
 * here: `$ZSHK` drives a logged severity readout and reactive lighting cue,
 * not a real-time safety interlock — nothing on the fleet bus consumes it as
 * an immediate-braking signal, so trading ~100 ms of latency for a peak
 * reading that isn't systematically under-read is the right side of that
 * tradeoff.
 */
class ShockDetector(
    private val thresholdG: Double = DEFAULT_THRESHOLD_G,
    private val refractoryMs: Long = DEFAULT_REFRACTORY_MS,
    private val peakWindowMs: Long = DEFAULT_PEAK_WINDOW_MS,
) {
    // null = not currently tracking a window.
    private var windowOpenedAtMs: Long? = null
    private var windowPeakG: Double = 0.0

    // null = never fired. A nullable sentinel (not Long.MIN_VALUE) so the very
    // first report can't underflow the refractory comparison.
    private var lastEventMs: Long? = null

    /**
     * Feed one sample: linear-accel magnitude in m/s² at monotonic [nowMs].
     * Returns the peak magnitude in g of a just-closed event window, or null
     * if this sample didn't close one (below threshold with no window open,
     * still inside the refractory period, or the window is still collecting).
     */
    fun sample(
        magnitudeMps2: Double,
        nowMs: Long,
    ): Double? {
        val g = magnitudeMps2 / G
        val openedAt = windowOpenedAtMs
        return if (openedAt != null) closeOrContinueWindow(g, nowMs, openedAt) else maybeOpenWindow(g, nowMs)
    }

    /** A window is already open: track the running max, and report it once [peakWindowMs] has elapsed. */
    private fun closeOrContinueWindow(
        g: Double,
        nowMs: Long,
        openedAt: Long,
    ): Double? {
        if (g > windowPeakG) windowPeakG = g
        if (nowMs - openedAt < peakWindowMs) return null // still collecting
        val peak = windowPeakG
        windowOpenedAtMs = null
        lastEventMs = nowMs
        return peak
    }

    /** No window open: open one on a threshold crossing outside the post-event refractory period. */
    private fun maybeOpenWindow(
        g: Double,
        nowMs: Long,
    ): Double? {
        val last = lastEventMs
        if (last != null && nowMs - last < refractoryMs) return null // quiet period after the last report
        if (g < thresholdG) return null
        windowOpenedAtMs = nowMs
        windowPeakG = g
        return null // window just opened; the peak is reported when it closes
    }

    private companion object {
        const val G = 9.80665 // m/s² per g
        const val DEFAULT_THRESHOLD_G = 1.5 // a firm bump/impact, not normal driving jostle
        const val DEFAULT_REFRACTORY_MS = 500L
        const val DEFAULT_PEAK_WINDOW_MS = 120L
    }
}
