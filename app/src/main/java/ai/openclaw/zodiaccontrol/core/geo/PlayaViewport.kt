package ai.openclaw.zodiaccontrol.core.geo

import kotlin.math.cos
import kotlin.math.sin

/**
 * Maps points in the playa-local Cartesian frame (meters east/north of the
 * Golden Spike) to screen pixels. Track-up: when [headingDeg] = 0, geographic
 * north is at the top of the screen; positive [headingDeg] rotates the camera
 * clockwise (so the heading direction stays at the top of the screen).
 *
 * @param center where the camera is looking, in playa meters
 * @param headingDeg vehicle heading in degrees clockwise from geographic north
 * @param pixelsPerMeter zoom; e.g. 0.15 fits the ~5 km city diameter into ~750 px
 * @param widthPx viewport width in pixels
 * @param heightPx viewport height in pixels
 */
data class PlayaViewport(
    val center: PlayaPoint = PlayaPoint(0.0, 0.0),
    val headingDeg: Double = 0.0,
    val pixelsPerMeter: Double = 0.15,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
) {
    private val rad = Math.toRadians(headingDeg)
    private val cos = cos(rad)
    private val sin = sin(rad)
    private val cx = widthPx / 2.0
    private val cy = heightPx / 2.0

    /**
     * Project a playa-meters point to screen pixels (origin top-left, +Y down).
     */
    fun toScreen(point: PlayaPoint): ScreenXY {
        val dx = point.eastM - center.eastM
        val dy = point.northM - center.northM
        // Track-up: rotate world by -headingDeg so the heading vector points up.
        val xRot = dx * cos - dy * sin
        val yRot = dx * sin + dy * cos
        // North (yRot > 0) maps to screen-top (smaller screen Y), so flip.
        return ScreenXY(
            x = cx + xRot * pixelsPerMeter,
            y = cy - yRot * pixelsPerMeter,
        )
    }
}

/**
 * Plain (x, y) pair in screen pixels — kept primitive so this module has no
 * Compose / Android dependency. The renderer converts to Compose Offset at
 * the boundary.
 */
data class ScreenXY(val x: Double, val y: Double)
