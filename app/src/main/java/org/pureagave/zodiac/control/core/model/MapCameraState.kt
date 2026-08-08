package org.pureagave.zodiac.control.core.model

import org.pureagave.zodiac.control.core.geo.PlayaPoint

/**
 * Where the map camera is looking, as one value (A2).
 *
 * These six moved together and changed together while living as six loose
 * fields on `CockpitUiState`, which made a camera update read like six
 * unrelated edits and let an inconsistent pair through — a [followMode] of
 * [FollowMode.FREE] with a null [cameraOverride], say, or a TRACK_UP camera
 * whose [viewRotationDeg] had drifted off the ego heading. Grouped, a camera
 * change is one `copy`, and the invariants have somewhere to live.
 *
 * The vehicle's own heading deliberately stays out: that's the *ego*, not the
 * camera. [viewRotationDeg] follows it in TRACK_UP and is independent in FREE,
 * and conflating the two is exactly the bug this grouping makes visible.
 *
 * @property mapMode orthographic TOP or pitched TILT.
 * @property tiltDeg pitch angle used by [MapMode.TILT].
 * @property pixelsPerMeter zoom.
 * @property cameraOverride absolute position in playa metres when [followMode]
 *   is [FollowMode.FREE]; null in TRACK_UP, where the renderer centres on the
 *   live GPS fix instead.
 * @property followMode whether the camera tracks the ego or has been panned free.
 * @property viewRotationDeg compass direction aligned with the top of the
 *   viewport (degrees CW from true north).
 */
data class MapCameraState(
    val mapMode: MapMode = MapMode.TOP,
    val tiltDeg: Int = DEFAULT_TILT_DEG,
    val pixelsPerMeter: Double = DEFAULT_PIXELS_PER_METER,
    val cameraOverride: PlayaPoint? = null,
    val followMode: FollowMode = FollowMode.TRACK_UP,
    val viewRotationDeg: Double = 0.0,
) {
    /**
     * True when the camera has been panned or rotated away from the ego. The
     * two conditions are the same state and were previously tested separately
     * at each call site, which is how they drifted apart.
     */
    val isFree: Boolean get() = followMode == FollowMode.FREE

    /** Snap back to tracking the ego at [headingDeg], dropping any free pan. */
    fun recentredOn(headingDeg: Int): MapCameraState =
        copy(
            cameraOverride = null,
            viewRotationDeg = headingDeg.toDouble(),
            followMode = FollowMode.TRACK_UP,
        )

    companion object {
        val DEFAULT = MapCameraState()

        const val DEFAULT_TILT_DEG: Int = 40
        const val MIN_TILT_DEG: Int = 0
        const val MAX_TILT_DEG: Int = 80

        // Map zoom in screen pixels per playa meter. Defaults frame the ~5 km
        // city radius at the typical Fire-tablet viewport. Mirrors the bounds
        // enforced by MapTouchInput's pinch handler.
        const val DEFAULT_PIXELS_PER_METER: Double = 0.18
        const val MIN_PIXELS_PER_METER: Double = 0.05
        const val MAX_PIXELS_PER_METER: Double = 5.0

        // Hard cap on how far the camera can drift from ego in [FollowMode.FREE].
        // Keeps a stuck/dragging finger from sliding the city far off-canvas
        // where the recenter button might be the only escape.
        const val MAX_CAMERA_OFFSET_M: Double = 5_000.0
    }
}
