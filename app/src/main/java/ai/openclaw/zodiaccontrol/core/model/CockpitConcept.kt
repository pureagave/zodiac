package ai.openclaw.zodiaccontrol.core.model

/**
 * Visual concept for the cockpit shell. The concepts are alternate map / HUD
 * presentations sharing the same underlying state — switching is purely
 * presentational. (Concept B "PERSPECTIVE" was dropped 2026-07-04; the tags
 * A/C/D are kept stable so persisted values and mental model don't shift.)
 *
 * Display order is the cycle order; [next] advances and wraps back to [A].
 */
enum class CockpitConcept(
    val tag: String,
    val displayName: String,
) {
    A("A", "CRT VECTOR"),
    C("C", "TRACKER"),
    D("D", "BAY"),
    ;

    fun next(): CockpitConcept = entries[(ordinal + 1) % entries.size]
}
