package ai.openclaw.zodiaccontrol.ui.playamap

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

private const val PINCH_FINGERS: Int = 2
private const val HEADING_MAX_DEG: Float = 359f
private const val SPEED_MAX_KPH: Float = 120f
const val MAP_MIN_ZOOM: Double = 0.05
const val MAP_MAX_ZOOM: Double = 5.0

/**
 * Combined gesture handler for the cockpit map viewport. One finger sets
 * heading (X) and speed (Y); two-or-more fingers pinch-zoom the map. The
 * pinch session resets whenever the second finger lifts, so a fresh pinch
 * starts a new ratio.
 */
fun Modifier.cockpitTouchInput(
    currentZoom: () -> Double,
    onHeading: (Int) -> Unit,
    onSpeed: (Int) -> Unit,
    onZoom: (Double) -> Unit,
): Modifier =
    pointerInput(Unit) {
        var pinchStartDist = 0f
        var pinchStartZoom = currentZoom()
        awaitPointerEventScope {
            while (true) {
                val pressed = awaitPointerEvent().changes.filter { it.pressed }
                if (pressed.size < PINCH_FINGERS) pinchStartDist = 0f
                if (pressed.size == 1) {
                    val pos = pressed[0].position
                    onHeading((pos.x / size.width * HEADING_MAX_DEG).toInt())
                    onSpeed((pos.y / size.height * SPEED_MAX_KPH).toInt())
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
