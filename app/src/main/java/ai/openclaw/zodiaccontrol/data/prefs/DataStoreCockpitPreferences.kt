package ai.openclaw.zodiaccontrol.data.prefs

import ai.openclaw.zodiaccontrol.burnin.BurnInConfig
import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

    // 15 independent per-field `?: default` reads — flat, not branching logic;
    // the cyclomatic count is a mechanical artefact of the field count.
    @Suppress("CyclomaticComplexMethod")
    override suspend fun readBurnInConfig(): BurnInConfig {
        val prefs = dataStore.data.first()
        val d = BurnInConfig()
        return BurnInConfig(
            pixelShiftEnabled = prefs[KEY_BI_SHIFT_ENABLED] ?: d.pixelShiftEnabled,
            pixelShiftAmplitudePx = prefs[KEY_BI_SHIFT_AMP] ?: d.pixelShiftAmplitudePx,
            pixelShiftPeriodSec = prefs[KEY_BI_SHIFT_PERIOD] ?: d.pixelShiftPeriodSec,
            visualModulationEnabled = prefs[KEY_BI_VISUAL_ENABLED] ?: d.visualModulationEnabled,
            breatheAmplitude = prefs[KEY_BI_BREATHE_AMP] ?: d.breatheAmplitude,
            breathePeriodSec = prefs[KEY_BI_BREATHE_PERIOD] ?: d.breathePeriodSec,
            dimTimeoutSec = prefs[KEY_BI_DIM_TIMEOUT] ?: d.dimTimeoutSec,
            dimContentAlpha = prefs[KEY_BI_DIM_ALPHA] ?: d.dimContentAlpha,
            dimBacklight = prefs[KEY_BI_DIM_BACKLIGHT] ?: d.dimBacklight,
            deepIdleTimeoutSec = prefs[KEY_BI_DEEP_TIMEOUT] ?: d.deepIdleTimeoutSec,
            deepIdleBacklight = prefs[KEY_BI_DEEP_BACKLIGHT] ?: d.deepIdleBacklight,
            sleepTimeoutSec = prefs[KEY_BI_SLEEP_TIMEOUT] ?: d.sleepTimeoutSec,
            sleepBacklight = prefs[KEY_BI_SLEEP_BACKLIGHT] ?: d.sleepBacklight,
            movementSpeedKph = prefs[KEY_BI_MOVE_SPEED] ?: d.movementSpeedKph,
            movementMeters = prefs[KEY_BI_MOVE_METERS] ?: d.movementMeters,
        ).coerced()
    }

    override suspend fun setBurnInConfig(config: BurnInConfig) {
        val c = config.coerced()
        dataStore.edit {
            it[KEY_BI_SHIFT_ENABLED] = c.pixelShiftEnabled
            it[KEY_BI_SHIFT_AMP] = c.pixelShiftAmplitudePx
            it[KEY_BI_SHIFT_PERIOD] = c.pixelShiftPeriodSec
            it[KEY_BI_VISUAL_ENABLED] = c.visualModulationEnabled
            it[KEY_BI_BREATHE_AMP] = c.breatheAmplitude
            it[KEY_BI_BREATHE_PERIOD] = c.breathePeriodSec
            it[KEY_BI_DIM_TIMEOUT] = c.dimTimeoutSec
            it[KEY_BI_DIM_ALPHA] = c.dimContentAlpha
            it[KEY_BI_DIM_BACKLIGHT] = c.dimBacklight
            it[KEY_BI_DEEP_TIMEOUT] = c.deepIdleTimeoutSec
            it[KEY_BI_DEEP_BACKLIGHT] = c.deepIdleBacklight
            it[KEY_BI_SLEEP_TIMEOUT] = c.sleepTimeoutSec
            it[KEY_BI_SLEEP_BACKLIGHT] = c.sleepBacklight
            it[KEY_BI_MOVE_SPEED] = c.movementSpeedKph
            it[KEY_BI_MOVE_METERS] = c.movementMeters
        }
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

        val KEY_BI_SHIFT_ENABLED = booleanPreferencesKey("bi_shift_enabled")
        val KEY_BI_SHIFT_AMP = intPreferencesKey("bi_shift_amp_px")
        val KEY_BI_SHIFT_PERIOD = intPreferencesKey("bi_shift_period_sec")
        val KEY_BI_VISUAL_ENABLED = booleanPreferencesKey("bi_visual_enabled")
        val KEY_BI_BREATHE_AMP = floatPreferencesKey("bi_breathe_amp")
        val KEY_BI_BREATHE_PERIOD = intPreferencesKey("bi_breathe_period_sec")
        val KEY_BI_DIM_TIMEOUT = longPreferencesKey("bi_dim_timeout_sec")
        val KEY_BI_DIM_ALPHA = floatPreferencesKey("bi_dim_alpha")
        val KEY_BI_DIM_BACKLIGHT = floatPreferencesKey("bi_dim_backlight")
        val KEY_BI_DEEP_TIMEOUT = longPreferencesKey("bi_deep_timeout_sec")
        val KEY_BI_DEEP_BACKLIGHT = floatPreferencesKey("bi_deep_backlight")
        val KEY_BI_SLEEP_TIMEOUT = longPreferencesKey("bi_sleep_timeout_sec")
        val KEY_BI_SLEEP_BACKLIGHT = floatPreferencesKey("bi_sleep_backlight")
        val KEY_BI_MOVE_SPEED = doublePreferencesKey("bi_move_speed_kph")
        val KEY_BI_MOVE_METERS = doublePreferencesKey("bi_move_meters")

        // Mirror MapTouchInput.MAP_MIN_ZOOM / MAX_ZOOM so a tampered preferences
        // file can't seed the UI with an out-of-range zoom that would then need
        // a pinch gesture to recover from.
        const val MIN_ZOOM = 0.05
        const val MAX_ZOOM = 5.0
    }
}
