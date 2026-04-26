package ai.openclaw.zodiaccontrol.ui.playamap

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

private const val PINCH_FINGERS: Int = 2
const val MAP_MIN_ZOOM: Double = 0.05
const val MAP_MAX_ZOOM: Double = 5.0

/**
 * Combined gesture handler for the cockpit map viewport.
 *
 * - One-finger drag → calls [onPan] with the screen-pixel delta since the
 *   previous frame. The caller is responsible for converting that delta into
 *   world-space (heading-aware) movement of the camera.
 * - Two-or-more fingers → pinch-zoom. Tracks the inter-finger distance at the
 *   start of each pinch session and scales `pixelsPerMeter` by the live
 *   distance ratio. The session resets whenever the second finger lifts, so a
 *   fresh pinch starts a new ratio.
 */
fun Modifier.cockpitTouchInput(
    currentZoom: () -> Double,
    onPan: (Float, Float) -> Unit,
    onZoom: (Double) -> Unit,
): Modifier =
    pointerInput(Unit) {
        var pinchStartDist = 0f
        var pinchStartZoom = currentZoom()
        var lastPanX = 0f
        var lastPanY = 0f
        var hadOneFinger = false
        awaitPointerEventScope {
            while (true) {
                val pressed = awaitPointerEvent().changes.filter { it.pressed }
                if (pressed.size < PINCH_FINGERS) pinchStartDist = 0f
                if (pressed.size != 1) hadOneFinger = false
                if (pressed.size == 1) {
                    val pos = pressed[0].position
                    if (hadOneFinger) onPan(pos.x - lastPanX, pos.y - lastPanY)
                    lastPanX = pos.x
                    lastPanY = pos.y
                    hadOneFinger = true
                } else if (pressed.size >= PINCH_FINGERS) {
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val distance = hypot(b.x - a.x, b.y - a.y)
                    if (pinchStartDist == 0f) {
                        pinchStartDist = distance
                        pinchStartZoom = currentZoom()
                    } else {
                        onZoom((pinchStartZoom * distance / pinchStartDist).coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM))
                    }
                }
            }
        }
    }
