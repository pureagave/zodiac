package org.pureagave.zodiac.control.core.sensor

/** Which of a failover pair the cockpit should be listening to. */
enum class LocationRoute {
    /** The beacon — the fleet's shared source of truth. */
    PRIMARY,

    /** This tablet's own GNSS, used only while the beacon is missing. */
    FALLBACK,
}

/**
 * Decides *when* to give up on the beacon and when to trust it again.
 *
 * Pure and clock-injected: [update] is handed the time and whether the primary
 * is currently healthy, and returns the route. No coroutines, no sources, so
 * the timing rules are testable without a network or a GPS.
 *
 * Both directions are deliberately delayed, for different reasons:
 *
 * * **Dropping** waits [dropAfterMs] on top of the primary's own staleness
 *   window, so a brief WiFi stumble doesn't tear down and restart sources.
 *   Switching isn't free and a flapping position source is worse than a
 *   momentarily frozen one.
 * * **Recovering** waits [recoverAfterMs] — longer — because a beacon that is
 *   half-alive (coming back for a second, dropping out again) would otherwise
 *   bounce the cockpit between two sources that disagree slightly about where
 *   the vehicle is. Once we're on the fallback there's no urgency; the fallback
 *   works. So we make the beacon prove itself before handing back.
 */
class LocationFailoverPolicy(
    private val dropAfterMs: Long = DROP_AFTER_MS,
    private val recoverAfterMs: Long = RECOVER_AFTER_MS,
) {
    var route: LocationRoute = LocationRoute.PRIMARY
        private set

    private var lastHealthy: Boolean? = null
    private var conditionSinceMs: Long = 0L

    /**
     * Advance the policy. [nowMs] is any monotonic millisecond clock;
     * [primaryHealthy] is whether the beacon is currently delivering fixes.
     */
    fun update(
        nowMs: Long,
        primaryHealthy: Boolean,
    ): LocationRoute {
        if (primaryHealthy != lastHealthy) {
            lastHealthy = primaryHealthy
            conditionSinceMs = nowMs
        }
        val heldMs = nowMs - conditionSinceMs
        route =
            when {
                !primaryHealthy && route == LocationRoute.PRIMARY && heldMs >= dropAfterMs ->
                    LocationRoute.FALLBACK
                primaryHealthy && route == LocationRoute.FALLBACK && heldMs >= recoverAfterMs ->
                    LocationRoute.PRIMARY
                else -> route
            }
        return route
    }

    /** Back to trusting the beacon, forgetting all timing — used on restart. */
    fun reset() {
        route = LocationRoute.PRIMARY
        lastHealthy = null
        conditionSinceMs = 0L
    }

    companion object {
        /**
         * Extra grace beyond `NetworkLocationSource.STALE_MS` (5 s) before
         * abandoning the beacon, so a single dropped multicast burst doesn't
         * cost a source swap.
         *
         * Worst-case time blind before failover ≈ 10.5 s, not the naive 5 + 3.
         * NET does not go Searching the instant its fix turns 5 s old: its
         * watchdog polls every `STALE_MS / 2` (2.5 s), so a fix can sit up to
         * STALE_MS + one poll = 7.5 s stale before NET demotes off it. Only
         * then does this policy begin its DROP_AFTER_MS (3 s) countdown, so
         * 7.5 + 3 ≈ 10.5 s. `FailoverLocationSource`'s 1 s tick can add up to a
         * further second; best case ≈ 8 s, when a poll catches staleness right
         * at the 5 s boundary.
         */
        const val DROP_AFTER_MS: Long = 3_000L

        /** How long the beacon must stream cleanly before we hand back to it. */
        const val RECOVER_AFTER_MS: Long = 10_000L
    }
}
