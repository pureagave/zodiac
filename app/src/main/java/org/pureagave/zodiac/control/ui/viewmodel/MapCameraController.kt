package org.pureagave.zodiac.control.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.AUTO_RECENTER_MS
import org.pureagave.zodiac.control.core.model.FollowMode
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.data.prefs.CockpitPreferences
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import kotlin.math.abs

/**
 * Everything that moves the *map camera* — mode, tilt, zoom, free pan, view
 * rotation, and the auto-recenter timer that undoes a stale free pan.
 *
 * Split out of [CockpitViewModel] (which owns it and forwards to it) because
 * these six operations only ever touch [CockpitUiState.camera] and the
 * preferences that persist it: they share the follow-mode invariants, share
 * the auto-recenter job, and had nothing to do with the ViewModel's other
 * jobs (telemetry, GPS, threats). The ViewModel keeps the public API so the
 * UI and the test suite see no change.
 *
 * Not a ViewModel and not lifecycle-aware: [scope] is the owning ViewModel's
 * `viewModelScope`, so the auto-recenter job dies with the ViewModel exactly
 * as it did when it lived there.
 *
 * @param state the cockpit's single state holder, shared with the ViewModel —
 *   there is deliberately still only one `MutableStateFlow<CockpitUiState>`
 *   in the process.
 */
internal class MapCameraController(
    private val state: MutableStateFlow<CockpitUiState>,
    private val scope: CoroutineScope,
    private val preferences: CockpitPreferences,
    private val projection: PlayaProjection,
) {
    /**
     * Coroutine that flips [FollowMode.FREE] back to [FollowMode.TRACK_UP]
     * after [AUTO_RECENTER_MS] of map-gesture inactivity. Restarted on
     * every pan / pinch / rotate; cancelled when the recenter button is
     * tapped. Held as a field so consecutive gestures coalesce into one
     * pending revert rather than stacking.
     */
    private var autoRecenterJob: Job? = null

    /**
     * Cumulative twist, in degrees, accumulated while still in
     * [FollowMode.TRACK_UP]. Lets [nudgeViewRotation] tell "a deliberate
     * rotate gesture" from "incidental finger twist during a pinch-zoom" —
     * see [ROTATE_FREE_COMMIT_DEG]. Reset on every commit to FREE and on
     * every [recenterPan].
     */
    private var pendingTwistDeg: Float = 0f

    fun setMapMode(mode: MapMode) {
        state.update { it.copy(camera = it.camera.copy(mapMode = mode)) }
        scope.launch { preferences.setMapMode(mode) }
    }

    fun setTiltDeg(deg: Int) {
        val clamped = deg.coerceIn(CockpitUiState.MIN_TILT_DEG, CockpitUiState.MAX_TILT_DEG)
        state.update { it.copy(camera = it.camera.copy(tiltDeg = clamped)) }
        scope.launch { preferences.setTiltDeg(clamped) }
    }

    fun setPixelsPerMeter(zoom: Double) {
        val clamped = zoom.coerceIn(CockpitUiState.MIN_PIXELS_PER_METER, CockpitUiState.MAX_PIXELS_PER_METER)
        state.update { it.copy(camera = it.camera.copy(pixelsPerMeter = clamped)) }
        // Pinch is a map gesture — counts as user interaction in FREE mode
        // and resets the auto-recenter timer, but doesn't itself switch
        // out of TRACK_UP (the camera still tracks ego, just at new zoom).
        if (state.value.followMode == FollowMode.FREE) scheduleAutoRecenter()
        scope.launch { preferences.setPixelsPerMeter(clamped) }
    }

    /**
     * One-finger drag delta, already converted to playa metres (heading-
     * aware) by the touch input layer. Switches the cockpit into
     * [FollowMode.FREE] on first call: the camera detaches from the live
     * GPS fix and parks at an absolute world position, so the user sees
     * the ego marker slide on screen as the fix updates instead of the
     * map sliding under a stationary marker.
     */
    fun panBy(
        dEastM: Double,
        dNorthM: Double,
    ) {
        val cap = CockpitUiState.MAX_CAMERA_OFFSET_M
        state.update { current ->
            val ego = current.egoFix?.location?.let(projection::project) ?: PlayaPoint(0.0, 0.0)
            val fromCamera = current.cameraOverride ?: ego
            val newCamera =
                PlayaPoint(
                    eastM = (fromCamera.eastM + dEastM).coerceIn(ego.eastM - cap, ego.eastM + cap),
                    northM = (fromCamera.northM + dNorthM).coerceIn(ego.northM - cap, ego.northM + cap),
                )
            current.copy(camera = current.camera.copy(cameraOverride = newCamera, followMode = FollowMode.FREE))
        }
        scheduleAutoRecenter()
    }

    /**
     * Two-finger rotate delta in degrees (CW positive on screen). Spins
     * the *display* — what compass direction sits at the top of the
     * viewport — without touching the ego's physical heading.
     *
     * While still in [FollowMode.TRACK_UP], the delta only *accumulates*
     * ([pendingTwistDeg]) until it clears [ROTATE_FREE_COMMIT_DEG]: a pinch
     * co-fires a per-frame rotate step for every incidental twist of the
     * fingers, and committing to FREE on the first sub-degree step flipped
     * essentially every zoom out of track-up. Once accumulated twist (or a
     * single big nudge) clears the threshold, the whole accumulated amount
     * is applied in one step — so the display continues smoothly from
     * heading + accumulated twist rather than jumping — and the gesture
     * commits to FREE exactly like a pan does. Already-FREE nudges are
     * unaffected: every delta applies immediately, same fine-rotation feel
     * as before.
     */
    fun nudgeViewRotation(deltaDeg: Float) {
        if (deltaDeg == 0f) return
        val wasTrackUp = state.value.followMode == FollowMode.TRACK_UP
        if (wasTrackUp) {
            pendingTwistDeg += deltaDeg
            if (abs(pendingTwistDeg) < ROTATE_FREE_COMMIT_DEG) return
        }
        val appliedDeg = if (wasTrackUp) pendingTwistDeg else deltaDeg
        pendingTwistDeg = 0f
        state.update { current ->
            // Floored modulo keeps the accumulated rotation in [0, 360) so it
            // never grows unbounded and bleeds float precision over a long
            // session of rotate gestures.
            val raw = current.viewRotationDeg + appliedDeg
            val normalized = ((raw % FULL_CIRCLE_DEG) + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG
            current.copy(
                camera = current.camera.copy(viewRotationDeg = normalized, followMode = FollowMode.FREE),
            )
        }
        scheduleAutoRecenter()
    }

    /**
     * Snap back to [FollowMode.TRACK_UP]: clear the camera override so the
     * camera follows the GPS fix again, sync display rotation to the
     * ego's heading so it points up, and cancel any pending auto-recenter.
     * Bound to the on-screen recenter button and to the auto-revert
     * timer when the user is idle for [AUTO_RECENTER_MS].
     */
    fun recenterPan() {
        autoRecenterJob?.cancel()
        autoRecenterJob = null
        pendingTwistDeg = 0f
        state.update { current ->
            // One named operation rather than three coordinated field
            // edits — recentring is a single idea and now reads as one.
            current.copy(camera = current.camera.recentredOn(current.headingDeg))
        }
    }

    private fun scheduleAutoRecenter() {
        autoRecenterJob?.cancel()
        autoRecenterJob =
            scope.launch {
                delay(AUTO_RECENTER_MS)
                recenterPan()
            }
    }
}

/** Degrees in a full revolution — used to normalize accumulated view rotation. */
private const val FULL_CIRCLE_DEG: Double = 360.0

/**
 * Cumulative twist, in degrees, required to commit a rotate gesture out of
 * [FollowMode.TRACK_UP] into [FollowMode.FREE]. A chosen UX threshold, NOT a
 * measured/datasheet constant — a few degrees, picked to sit below a
 * deliberate two-finger rotate and above the incidental twist a pinch-zoom
 * puts on the fingers. Device-verify the felt behaviour on the glass.
 */
private const val ROTATE_FREE_COMMIT_DEG: Float = 5.0f
