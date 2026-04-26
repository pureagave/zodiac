package ai.openclaw.zodiaccontrol.data.prefs

import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState

/**
 * Snapshot of every user-facing setting the cockpit persists across launches.
 * Pan offset and ego/connection state are intentionally excluded — those are
 * session state, not preferences.
 */
data class CockpitPrefsSnapshot(
    val locationSource: LocationSourceType,
    val mapMode: MapMode,
    val tiltDeg: Int,
    val pixelsPerMeter: Double,
) {
    companion object {
        val DEFAULT =
            CockpitPrefsSnapshot(
                locationSource = LocationSourceType.FAKE,
                mapMode = MapMode.TOP,
                tiltDeg = CockpitUiState.DEFAULT_TILT_DEG,
                pixelsPerMeter = DEFAULT_PIXELS_PER_METER,
            )

        const val DEFAULT_PIXELS_PER_METER: Double = 0.18
    }
}

/**
 * One-shot read at startup + fire-and-forget setters per user action. The VM
 * does not subscribe — there's no external mutation path, so the snapshot is
 * only re-read when the process restarts.
 */
interface CockpitPreferences {
    suspend fun read(): CockpitPrefsSnapshot

    suspend fun setLocationSource(type: LocationSourceType)

    suspend fun setMapMode(mode: MapMode)

    suspend fun setTiltDeg(deg: Int)

    suspend fun setPixelsPerMeter(zoom: Double)
}
