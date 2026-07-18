package org.pureagave.zodiac.control.ui.playamap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.hypot

private const val PINCH_FINGERS: Int = 2
private const val DEG_PER_RAD: Double = 180.0 / Math.PI
private const val ROT_DEADZONE_DEG: Double = 0.05
const val MAP_MIN_ZOOM: Double = 0.05
const val MAP_MAX_ZOOM: Double = 5.0

/**
 * Combined gesture handler for the cockpit map viewport.
 *
 * - One-finger drag → calls [onPan] with the screen-pixel delta since the
 *   previous frame. The caller is responsible for converting that delta into
 *   world-space (heading-aware) movement of the camera.
 * - Two-or-more fingers → simultaneous pinch-zoom and rotate. We track the
 *   inter-finger distance for [onZoom] and the inter-finger angle for
 *   [onRotate]. Both fire on every frame the second finger moves so the
 *   caller can update zoom and view rotation in lock-step. The session
 *   resets whenever the second finger lifts, so a fresh two-finger touch
 *   starts new ratios — no rotation jump from the last grip.
 *
 * `pointerInput(Unit)` only runs its handler block once when the modifier
 * is first attached, so every recomposition's fresh `currentZoom` /
 * `onPan` / `onZoom` / `onRotate` lambda would otherwise be ignored —
 * the gesture handler would keep invoking the stale closures captured
 * the first time around. We route them through `rememberUpdatedState`
 * inside a `composed { }` block so the State holding each callback is
 * stable across recompositions but its `.value` always points at the
 * latest lambda. Reading the delegated `xCb()` inside the gesture loop
 * dispatches to whichever closure was passed in on the most recent
 * composition — which is the only way "second pinch starts from
 * already-zoomed-in level" works.
 */
fun Modifier.cockpitTouchInput(
    currentZoom: () -> Double,
    onPan: (Float, Float) -> Unit,
    onZoom: (Double) -> Unit,
    onRotate: (Float) -> Unit = {},
): Modifier =
    composed {
        val zoomGetter by rememberUpdatedState(currentZoom)
        val panCb by rememberUpdatedState(onPan)
        val zoomCb by rememberUpdatedState(onZoom)
        val rotateCb by rememberUpdatedState(onRotate)
        pointerInput(Unit) {
            var pinchStartDist = 0f
            var pinchStartZoom = zoomGetter()
            var lastRotAngleRad = 0.0
            var hadTwoFingers = false
            var lastPanX = 0f
            var lastPanY = 0f
            var hadOneFinger = false
            awaitPointerEventScope {
                while (true) {
                    val pressed = awaitPointerEvent().changes.filter { it.pressed }
                    if (pressed.size < PINCH_FINGERS) {
                        pinchStartDist = 0f
                        hadTwoFingers = false
                    }
                    if (pressed.size != 1) hadOneFinger = false
                    if (pressed.size == 1) {
                        val pos = pressed[0].position
                        if (hadOneFinger) panCb(pos.x - lastPanX, pos.y - lastPanY)
                        lastPanX = pos.x
                        lastPanY = pos.y
                        hadOneFinger = true
                    } else if (pressed.size >= PINCH_FINGERS) {
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val distance = hypot(dx, dy)
                        val angleRad = atan2(dy.toDouble(), dx.toDouble())
                        if (pinchStartDist == 0f) {
                            pinchStartDist = distance
                            pinchStartZoom = zoomGetter()
                            lastRotAngleRad = angleRad
                            hadTwoFingers = true
                        } else {
                            zoomCb((pinchStartZoom * distance / pinchStartDist).coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM))
                            if (hadTwoFingers) {
                                // Shortest-arc delta in degrees, then sign-flip so
                                // a CW twist on screen (positive atan2-delta with
                                // y-down screen coords) increases the user's idea
                                // of "what's at the top" CCW — i.e. the compass
                                // direction at the top of the viewport rotates by
                                // the opposite of the finger twist, which is the
                                // standard map-app feel.
                                val raw = (angleRad - lastRotAngleRad) * DEG_PER_RAD
                                val wrapped =
                                    when {
                                        raw > 180.0 -> raw - 360.0
                                        raw < -180.0 -> raw + 360.0
                                        else -> raw
                                    }
                                if (kotlin.math.abs(wrapped) > ROT_DEADZONE_DEG) {
                                    rotateCb(-wrapped.toFloat())
                                }
                            }
                            lastRotAngleRad = angleRad
                        }
                    }
                }
            }
        }
    }
