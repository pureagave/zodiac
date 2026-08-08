package org.pureagave.zodiac.control.burnin

/**
 * Cumulative on-time per display zone, for deciding what to move between
 * burns (deferred Phase 5 of the burn-in work).
 *
 * A "zone" is `<concept>/<phase>` — that granularity is deliberate and is as
 * far as the honest claim goes. Within one concept the layout is fixed, so the
 * chrome that risks burning is the same pixels for the whole time that concept
 * is on screen; and the phase is what says how *bright* those pixels were. Any
 * finer split (per-widget rectangles) would be inventing data the app doesn't
 * actually measure, and a burn-risk number that's made up is worse than none.
 *
 * Deliberately cheap, per the task's own caveat: two map lookups and a
 * subtraction per transition, nothing per frame, and no persistence of its
 * own — [report] goes to the rolling log, which already survives reboots, so
 * cross-burn totals are a matter of summing lines rather than another store.
 *
 * Not thread-safe; drive it from one place (the scaffold's effect).
 */
class BurnInLedger(private val clock: () -> Long) {
    private val totalsMs = LinkedHashMap<String, Long>()
    private var openZone: String? = null
    private var openedAt: Long = 0L

    /**
     * Switch to [zone], closing whatever was open and banking its elapsed time.
     * Calling it repeatedly with the same zone is a no-op, so a recomposition
     * storm can't inflate the numbers.
     */
    fun mark(zone: String) {
        val now = clock()
        if (openZone == zone) return
        closeOpen(now)
        openZone = zone
        openedAt = now
    }

    /** Bank the open interval without starting a new one (process going away). */
    fun close() {
        closeOpen(clock())
        openZone = null
    }

    /**
     * Banked totals plus whatever the currently-open zone has accrued, so a
     * report mid-session isn't silently missing the zone you're looking at.
     */
    fun totals(): Map<String, Long> {
        val out = LinkedHashMap(totalsMs)
        val zone = openZone ?: return out
        out[zone] = (out[zone] ?: 0L) + (clock() - openedAt).coerceAtLeast(0L)
        return out
    }

    /** One log line, busiest zone first: `burn-in: RADAR/ACTIVE 2h14m, MAP/DIM 31m`. */
    fun report(): String {
        val entries = totals().entries.sortedByDescending { it.value }
        if (entries.isEmpty()) return "burn-in: no on-time recorded"
        return "burn-in: " + entries.joinToString(", ") { "${it.key} ${formatDuration(it.value)}" }
    }

    private fun closeOpen(now: Long) {
        val zone = openZone ?: return
        // A clock that goes backwards (or a bad injected one) must not credit
        // negative time and quietly reduce a risk figure.
        val elapsed = (now - openedAt).coerceAtLeast(0L)
        totalsMs[zone] = (totalsMs[zone] ?: 0L) + elapsed
        openedAt = now
    }
}

/** `2h14m` / `31m` / `45s` — compact enough to sit several to a log line. */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}h${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
