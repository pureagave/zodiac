package org.pureagave.zodiac.control.data.prefs

import org.pureagave.zodiac.control.burnin.BurnInConfig
import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.ui.state.CockpitUiState

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
    val concept: CockpitConcept,
) {
    companion object {
        val DEFAULT =
            CockpitPrefsSnapshot(
                locationSource = LocationSourceType.FAKE,
                mapMode = MapMode.TOP,
                tiltDeg = CockpitUiState.DEFAULT_TILT_DEG,
                pixelsPerMeter = DEFAULT_PIXELS_PER_METER,
                concept = CockpitConcept.RADAR,
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

    suspend fun setConcept(concept: CockpitConcept)

    /**
     * Burn-in mitigation tuning, persisted as individual keys so each timeout /
     * modulation parameter is independently adjustable from the on-device tuning
     * panel and survives a relaunch. Returns coerced defaults for any key the
     * store hasn't seen yet.
     */
    suspend fun readBurnInConfig(): BurnInConfig

    suspend fun setBurnInConfig(config: BurnInConfig)
}
