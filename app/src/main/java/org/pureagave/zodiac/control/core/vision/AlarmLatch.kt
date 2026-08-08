package org.pureagave.zodiac.control.core.vision

/**
 * Holds a safety alarm on after its trigger stops, so a flickering input reads
 * as a steady warning instead of a strobe.
 *
 * The edge box decides `collision` per frame from a strictly-increasing size
 * against a noisy 9 fps thermal estimate, so the flag chatters: true, false,
 * true, false across neighbouring frames while the underlying situation — a
 * person walking into the vehicle's path — does not change at all. Evaluated
 * fresh every frame, that painted `! BRAKE !` and the centre banner as a
 * flashing artefact. A driver reads a strobe as a glitch, and an alert read as
 * a glitch is an alert that gets ignored.
 *
 * **Deliberately asymmetric: instant attack, slow release.** Raising the alarm
 * is never delayed — waiting for confirmation would cost exactly the moment the
 * warning exists for. Clearing it waits [holdMs] past the last trigger, so the
 * gaps in a chattering flag are bridged. The cost is that a genuinely resolved
 * hazard keeps warning for up to [holdMs] longer, which is the right side to
 * err on.
 *
 * This does not replace the sector and speed gating in
 * [SurroundRing.brakeAdvised] — it smooths whatever that decides. Feed it the
 * already-gated answer.
 */
class AlarmLatch(
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private var lastActiveMs: Long = 0L
    private var everActive: Boolean = false

    /**
     * Feed the current raw decision and get the latched one back.
     */
    fun update(active: Boolean): Boolean {
        val now = nowMs()
        if (active) {
            lastActiveMs = now
            everActive = true
            return true
        }
        return everActive && (now - lastActiveMs) < holdMs
    }

    /**
     * Milliseconds until the alarm would clear on its own, or 0 if it is
     * already clear.
     *
     * The caller needs this because nothing else will wake it: when the threat
     * feed goes quiet there are no further frames to re-evaluate on, so
     * something has to schedule the clear. Returning the remaining time lets
     * the caller sleep exactly that long instead of polling.
     */
    fun holdRemainingMs(): Long {
        if (!everActive) return 0L
        val remaining = holdMs - (nowMs() - lastActiveMs)
        return if (remaining > 0L) remaining else 0L
    }

    /** Drop the alarm immediately — for a feed change or a concept switch. */
    fun reset() {
        lastActiveMs = 0L
        everActive = false
    }

    companion object {
        /**
         * Long enough to bridge the gaps in a chattering flag at the edge box's
         * ~8–9 fps (several consecutive frames), short enough that the warning
         * still tracks a situation the driver is actively resolving.
         */
        const val DEFAULT_HOLD_MS: Long = 1_500L
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
