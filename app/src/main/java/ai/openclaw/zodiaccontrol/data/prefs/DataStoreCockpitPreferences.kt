package ai.openclaw.zodiaccontrol.data.prefs

import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * DataStore-backed implementation. Enums are stored by name so renaming an
 * enum constant invalidates that key (read returns the default) instead of
 * silently mis-mapping to a different value.
 */
class DataStoreCockpitPreferences(
    private val dataStore: DataStore<Preferences>,
) : CockpitPreferences {
    override suspend fun read(): CockpitPrefsSnapshot {
        val prefs = dataStore.data.first()
        val default = CockpitPrefsSnapshot.DEFAULT
        return CockpitPrefsSnapshot(
            locationSource = prefs[KEY_LOCATION_SOURCE]?.toLocationSourceOrNull() ?: default.locationSource,
            mapMode = prefs[KEY_MAP_MODE]?.toMapModeOrNull() ?: default.mapMode,
            tiltDeg =
                (prefs[KEY_TILT_DEG] ?: default.tiltDeg)
                    .coerceIn(CockpitUiState.MIN_TILT_DEG, CockpitUiState.MAX_TILT_DEG),
            pixelsPerMeter =
                (prefs[KEY_PIXELS_PER_METER] ?: default.pixelsPerMeter)
                    .coerceIn(MIN_ZOOM, MAX_ZOOM),
            concept = prefs[KEY_CONCEPT]?.toConceptOrNull() ?: default.concept,
        )
    }

    override suspend fun setLocationSource(type: LocationSourceType) {
        dataStore.edit { it[KEY_LOCATION_SOURCE] = type.name }
    }

    override suspend fun setMapMode(mode: MapMode) {
        dataStore.edit { it[KEY_MAP_MODE] = mode.name }
    }

    override suspend fun setTiltDeg(deg: Int) {
        dataStore.edit { it[KEY_TILT_DEG] = deg }
    }

    override suspend fun setPixelsPerMeter(zoom: Double) {
        dataStore.edit { it[KEY_PIXELS_PER_METER] = zoom }
    }

    override suspend fun setConcept(concept: CockpitConcept) {
        dataStore.edit { it[KEY_CONCEPT] = concept.name }
    }

    private fun String.toLocationSourceOrNull(): LocationSourceType? = runCatching { LocationSourceType.valueOf(this) }.getOrNull()

    private fun String.toMapModeOrNull(): MapMode? = runCatching { MapMode.valueOf(this) }.getOrNull()

    private fun String.toConceptOrNull(): CockpitConcept? = runCatching { CockpitConcept.valueOf(this) }.getOrNull()

    private companion object {
        val KEY_LOCATION_SOURCE = stringPreferencesKey("location_source")
        val KEY_MAP_MODE = stringPreferencesKey("map_mode")
        val KEY_TILT_DEG = intPreferencesKey("tilt_deg")
        val KEY_PIXELS_PER_METER = doublePreferencesKey("pixels_per_meter")
        val KEY_CONCEPT = stringPreferencesKey("cockpit_concept")

        // Mirror MapTouchInput.MAP_MIN_ZOOM / MAX_ZOOM so a tampered preferences
        // file can't seed the UI with an out-of-range zoom that would then need
        // a pinch gesture to recover from.
        const val MIN_ZOOM = 0.05
        const val MAX_ZOOM = 5.0
    }
}
