package org.pureagave.zodiac.control.ui.playamap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration

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
        val touchSlop = LocalViewConfiguration.current.touchSlop
        pointerInput(Unit) {
            // All the decisions live in PinchSession (unit-tested); this loop
            // only adapts pointer frames in and callbacks out. touchSlop is the
            // device-measured platform constant, captured once when the
            // modifier attaches (matching pointerInput(Unit)'s one-shot setup).
            val session = PinchSession(currentZoom = { zoomGetter() }, touchSlopPx = touchSlop)
            awaitPointerEventScope {
                while (true) {
                    val pressed =
                        awaitPointerEvent().changes
                            .filter { it.pressed }
                            .map { TouchPoint(it.position.x, it.position.y, it.id.value) }
                    val update = session.onPointers(pressed)
                    if (update.panDx != 0f || update.panDy != 0f) panCb(update.panDx, update.panDy)
                    update.zoom?.let(zoomCb)
                    if (update.rotateStepDeg != 0f) rotateCb(update.rotateStepDeg)
                }
            }
        }
    }
