package ai.openclaw.zodiaccontrol.core.navigation

/**
 * Result of a single nav-cue computation. The bar UI renders one of these;
 * [Unknown] is the "we don't have enough fix yet" / "no map loaded" state.
 *
 * Distance is metres, always non-negative. For [TowardClock] it's the length
 * of the heading-ray to the trash fence; for [AwayFromClock] it's the length
 * of the *backward* ray to the trash fence — i.e. how far behind us the
 * exit-clock sits.
 */
sealed class NavigationCue {
    /** No cue can be produced (no GPS fix, missing map, etc.). */
    object Unknown : NavigationCue()

    /**
     * Off-street, heading toward (or through) the city. The displayed clock
     * is the angle-from-the-Man of the point where our heading-ray hits the
     * trash fence. In deep playa this is the gate we're aimed at; in the
     * inner playa it's the far-side trash fence after passing through the
     * city.
     */
    data class TowardClock(val clock: ClockTime, val distanceM: Double) : NavigationCue()

    /**
     * Off-street, deep playa, heading outward (away from the Man). The clock
     * is the angle of the *backward*-ray hit on the trash fence — the gate
     * we just left behind. UI prefixes with "-".
     */
    data class AwayFromClock(val clock: ClockTime, val distanceM: Double) : NavigationCue()

    /**
     * Driving along a radial street (e.g. 4:30) toward the Man — Esplanade
     * is the next named arc we'll hit. [radialName] is the clock-format
     * radial name as it appears in the source data ("4:30", "4:45", ...).
     */
    data class OnRadialInbound(val radialName: String, val nextArc: String) : NavigationCue()

    /**
     * Driving along a radial street outward from the Man — [lastArc] is the
     * named arc we most recently crossed.
     */
    data class OnRadialOutbound(val radialName: String, val lastArc: String) : NavigationCue()

    /**
     * Driving along a named arc (Esplanade, Atwood, ...). [clock] reflects
     * our current position on the arc — it ticks as we move along, e.g.
     * 4:59 → 4:31 as we approach the 4:30 intersection going clockwise.
     */
    data class OnArc(val arcName: String, val clock: ClockTime) : NavigationCue()
}
