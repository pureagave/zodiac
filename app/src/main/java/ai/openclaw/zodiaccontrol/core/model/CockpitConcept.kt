package ai.openclaw.zodiaccontrol.core.model

/**
 * Visual concept for the cockpit shell. The four concepts are alternate map /
 * HUD presentations sharing the same underlying state — switching is purely
 * presentational.
 *
 * Display order is the cycle order; [next] advances and wraps back to [A].
 */
enum class CockpitConcept(
    val tag: String,
    val displayName: String,
) {
    A("A", "CRT VECTOR"),
    B("B", "PERSPECTIVE"),
    C("C", "TRACKER"),
    D("D", "BAY"),
    ;

    fun next(): CockpitConcept = entries[(ordinal + 1) % entries.size]
}
