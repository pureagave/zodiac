package ai.openclaw.zodiaccontrol.core.model

/**
 * Visual concept for the cockpit shell. The concepts are alternate map / HUD
 * presentations sharing the same underlying state — switching is purely
 * presentational.
 *
 * History: the original A `CRT VECTOR` and B `PERSPECTIVE` were dropped, and
 * the remaining two lost their letter designations (2026-07-04): C `TRACKER`
 * became [RADAR] and D `BAY` became [MAP]. A stale persisted old value falls
 * back to the default ([RADAR]).
 *
 * Display order is the cycle order; [next] advances and wraps back to [RADAR].
 */
enum class CockpitConcept(
    val displayName: String,
) {
    RADAR("RADAR"),
    MAP("MAP"),
    DRIVER("DRIVER"),
    ;

    fun next(): CockpitConcept = entries[(ordinal + 1) % entries.size]
}
