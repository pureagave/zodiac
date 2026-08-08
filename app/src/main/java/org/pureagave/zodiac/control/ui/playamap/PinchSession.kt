package org.pureagave.zodiac.control.ui.playamap

import kotlin.math.atan2
import kotlin.math.hypot

/** A pressed pointer in view pixels. Deliberately not Compose's `Offset` — see [PinchSession]. */
data class TouchPoint(val x: Float, val y: Float)

/**
 * What one pointer frame asks of the camera. [zoom] is null when this frame
 * didn't change it (a pan, or the frame that merely establishes a grip).
 */
data class GestureUpdate(
    val panDx: Float = 0f,
    val panDy: Float = 0f,
    val zoom: Double? = null,
    val rotateStepDeg: Float = 0f,
) {
    companion object {
        val NONE = GestureUpdate()
    }
}

/**
 * The map viewport's gesture state machine, extracted from the Compose
 * modifier so it can be tested without `awaitPointerEventScope` (L1).
 *
 * All the subtle behaviour lives in *when not to emit*, which is precisely
 * what's invisible in an integration test and obvious in a unit test:
 *
 *  - The first frame of a one-finger drag emits **no pan**. Without that, the
 *    map jumps by the full distance from wherever the previous gesture ended.
 *  - Dropping to one finger after a pinch also emits no pan on its first
 *    frame, for the same reason — the remaining finger is nowhere near where
 *    the last single finger was.
 *  - The first frame of a two-finger grip only *records* the span and angle.
 *    Zoom is relative to the zoom held at that moment, so a second pinch
 *    continues from the current level instead of snapping back.
 *  - Rotation is suppressed on that same establishing frame, or a fresh grip
 *    would twist the map by the angle between two unrelated gestures.
 *
 * Feed it every pointer frame via [onPointers]; it is stateful and single-
 * threaded, matching the pointer loop that drives it.
 *
 * @param currentZoom read at grip time, so the caller's live zoom is used
 *   rather than a value captured when the modifier was attached.
 */
class PinchSession(private val currentZoom: () -> Double) {
    private var pinchStartDist = 0f
    private var pinchStartZoom = 0.0
    private var lastRotAngleRad = 0.0
    private var hasGrip = false

    private var lastPanX = 0f
    private var lastPanY = 0f
    private var hasFinger = false

    fun onPointers(pressed: List<TouchPoint>): GestureUpdate {
        if (pressed.size < PINCH_FINGERS) {
            pinchStartDist = 0f
            hasGrip = false
        }
        if (pressed.size != 1) hasFinger = false

        return when {
            pressed.size == 1 -> pan(pressed[0])
            pressed.size >= PINCH_FINGERS -> pinch(pressed[0], pressed[1])
            else -> GestureUpdate.NONE
        }
    }

    private fun pan(point: TouchPoint): GestureUpdate {
        val update =
            if (hasFinger) {
                GestureUpdate(panDx = point.x - lastPanX, panDy = point.y - lastPanY)
            } else {
                GestureUpdate.NONE
            }
        lastPanX = point.x
        lastPanY = point.y
        hasFinger = true
        return update
    }

    private fun pinch(
        a: TouchPoint,
        b: TouchPoint,
    ): GestureUpdate {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val distance = hypot(dx, dy)
        val angleRad = atan2(dy.toDouble(), dx.toDouble())

        if (pinchStartDist == 0f) {
            pinchStartDist = distance
            pinchStartZoom = currentZoom()
            lastRotAngleRad = angleRad
            hasGrip = true
            return GestureUpdate.NONE
        }

        val zoom = mapPinchZoom(pinchStartZoom, pinchStartDist, distance)
        val step = if (hasGrip) mapRotationStepDeg(lastRotAngleRad, angleRad) else 0f
        lastRotAngleRad = angleRad
        return GestureUpdate(zoom = zoom, rotateStepDeg = step)
    }

    private companion object {
        const val PINCH_FINGERS = 2
    }
}
