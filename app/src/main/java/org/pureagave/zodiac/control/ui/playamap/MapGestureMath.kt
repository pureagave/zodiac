package org.pureagave.zodiac.control.ui.playamap

import kotlin.math.abs

private const val DEG_PER_RAD: Double = 180.0 / Math.PI
private const val ROT_DEADZONE_DEG: Double = 0.05
private const val HALF_TURN_DEG: Double = 180.0
private const val FULL_TURN_DEG: Double = 360.0

/**
 * The view-rotation step, in degrees, for one frame of a two-finger twist.
 *
 * [prevAngleRad] and [angleRad] are successive `atan2` angles between the two
 * fingers, so each lies in `(-π, π]`. When the finger pair sweeps through
 * dead-horizontal-left the angle wraps between those ends and the naive
 * difference is nearly a full turn — a hair of twist would spin the map most of
 * the way round. Taking the shortest arc is what stops that; the raw difference
 * can never exceed a full turn, so one correction is always enough.
 *
 * The result is sign-flipped: a clockwise twist on screen (positive delta in
 * y-down screen coordinates) rotates the compass direction at the top of the
 * viewport counter-clockwise, which is the standard map-app feel.
 *
 * Returns `0f` inside a small deadzone — finger-position jitter otherwise
 * dithers the view rotation while two fingers merely rest on the glass.
 */
fun mapRotationStepDeg(
    prevAngleRad: Double,
    angleRad: Double,
): Float {
    val raw = (angleRad - prevAngleRad) * DEG_PER_RAD
    val wrapped =
        when {
            raw > HALF_TURN_DEG -> raw - FULL_TURN_DEG
            raw < -HALF_TURN_DEG -> raw + FULL_TURN_DEG
            else -> raw
        }
    return if (abs(wrapped) > ROT_DEADZONE_DEG) -wrapped.toFloat() else 0f
}

/**
 * The zoom for the current pinch spread, relative to where the pinch began.
 *
 * Ratio-based against the zoom captured when the second finger landed, so a
 * second pinch continues from the level the first one left rather than
 * snapping back. [startDist] of zero means the session had no baseline yet;
 * the caller's zoom is returned untouched rather than dividing by it.
 */
fun mapPinchZoom(
    startZoom: Double,
    startDist: Float,
    distance: Float,
): Double {
    if (startDist <= 0f) return startZoom.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)
    return (startZoom * distance / startDist).coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)
}
