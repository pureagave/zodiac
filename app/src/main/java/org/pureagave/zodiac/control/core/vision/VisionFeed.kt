package org.pureagave.zodiac.control.core.vision

/**
 * Tri-state health of the DRIVER HUD's thermal contact feed.
 *
 * A plain "is the feed alive" boolean can't carry this: with the bench demo
 * fallback enabled, a crashed edge box and a genuinely clear road both render
 * as an empty contact list unless something distinguishes them. [ABSENT] is
 * the state that must never be allowed to read as "CLEAR" — see
 * `CockpitUiState.visionFeed` and the DRIVER HUD status line, which reserves
 * the word CLEAR for [LIVE].
 */
enum class VisionFeed {
    /** Real detections are arriving from the Jetson edge box. */
    LIVE,

    /** No live feed; showing the synthetic bench demo instead. */
    DEMO,

    /** No live feed and no demo fallback — genuinely no vision. */
    ABSENT,
}
