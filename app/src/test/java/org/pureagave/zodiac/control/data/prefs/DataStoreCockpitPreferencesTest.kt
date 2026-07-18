package org.pureagave.zodiac.control.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.pureagave.zodiac.control.burnin.BurnInConfig
import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.sensor.LocationSourceType

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreCockpitPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun returns_default_snapshot_when_unwritten() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = newPrefs()

            assertEquals(CockpitPrefsSnapshot.DEFAULT, prefs.read())
        }

    @Test
    fun round_trips_every_field() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = newPrefs()

            prefs.setLocationSource(LocationSourceType.BLE)
            prefs.setMapMode(MapMode.TILT)
            prefs.setTiltDeg(55)
            prefs.setPixelsPerMeter(0.42)
            prefs.setConcept(CockpitConcept.MAP)

            val snapshot = prefs.read()
            assertEquals(LocationSourceType.BLE, snapshot.locationSource)
            assertEquals(MapMode.TILT, snapshot.mapMode)
            assertEquals(55, snapshot.tiltDeg)
            assertEquals(0.42, snapshot.pixelsPerMeter, ZOOM_TOLERANCE)
            assertEquals(CockpitConcept.MAP, snapshot.concept)
        }

    @Test
    fun clamps_out_of_range_values_on_read() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = newPrefs()

            // A tampered file (or a future version that loosened bounds) must
            // not seed the UI with a value the controls can't reach.
            prefs.setTiltDeg(9999)
            prefs.setPixelsPerMeter(99.0)

            val snapshot = prefs.read()
            assertEquals(80, snapshot.tiltDeg)
            assertEquals(5.0, snapshot.pixelsPerMeter, ZOOM_TOLERANCE)
        }

    @Test
    fun clamps_below_minimum_values_up_to_floor_on_read() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = newPrefs()

            // Existing coverage only exercises the high end (9999 -> 80, 99.0 -> 5.0);
            // the lower coerceIn bound is a separate branch. MIN_TILT_DEG = 0, MIN_ZOOM = 0.05.
            prefs.setTiltDeg(-12)
            prefs.setPixelsPerMeter(0.0001)

            val snapshot = prefs.read()
            assertEquals(0, snapshot.tiltDeg)
            assertEquals(0.05, snapshot.pixelsPerMeter, ZOOM_TOLERANCE)
        }

    @Test
    fun falls_back_to_defaults_for_corrupt_enum_strings() =
        runTest(UnconfinedTestDispatcher()) {
            val store = newStore()
            val prefs = DataStoreCockpitPreferences(store)

            // Simulate a tampered/old file: enum keys hold strings that no longer
            // resolve via valueOf. read() must absorb the IllegalArgumentException
            // and return the documented defaults rather than crash.
            store.edit {
                it[stringPreferencesKey("location_source")] = "GALILEO"
                it[stringPreferencesKey("map_mode")] = "ISOMETRIC"
                it[stringPreferencesKey("cockpit_concept")] = "Z"
            }

            val snapshot = prefs.read()
            assertEquals(CockpitPrefsSnapshot.DEFAULT.locationSource, snapshot.locationSource)
            assertEquals(CockpitPrefsSnapshot.DEFAULT.mapMode, snapshot.mapMode)
            assertEquals(CockpitPrefsSnapshot.DEFAULT.concept, snapshot.concept)
        }

    @Test
    fun round_trips_each_enum_constant_independently() =
        runTest(UnconfinedTestDispatcher()) {
            // Every persisted enum value must survive write+read, not just the
            // single combination in round_trips_every_field.
            LocationSourceType.entries.forEach { source ->
                val prefs = newPrefs()
                prefs.setLocationSource(source)
                assertEquals(source, prefs.read().locationSource)
            }
            MapMode.entries.forEach { mode ->
                val prefs = newPrefs()
                prefs.setMapMode(mode)
                assertEquals(mode, prefs.read().mapMode)
            }
            CockpitConcept.entries.forEach { concept ->
                val prefs = newPrefs()
                prefs.setConcept(concept)
                assertEquals(concept, prefs.read().concept)
            }
        }

    @Test
    fun preserves_in_range_numeric_values_without_coercion() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = newPrefs()

            // Mid-range values sit strictly inside both coerceIn bounds, so they
            // must pass through untouched — confirming clamping is bounds-only.
            prefs.setTiltDeg(40)
            prefs.setPixelsPerMeter(1.5)

            val snapshot = prefs.read()
            assertEquals(40, snapshot.tiltDeg)
            assertEquals(1.5, snapshot.pixelsPerMeter, ZOOM_TOLERANCE)
        }

    @Test
    fun returns_default_burn_in_config_when_unwritten() =
        runTest(UnconfinedTestDispatcher()) {
            assertEquals(BurnInConfig().coerced(), newPrefs().readBurnInConfig())
        }

    @Test
    fun round_trips_burn_in_config() =
        runTest(UnconfinedTestDispatcher()) {
            val prefs = newPrefs()
            val cfg =
                BurnInConfig(
                    pixelShiftEnabled = false,
                    pixelShiftAmplitudePx = 3,
                    pixelShiftPeriodSec = 60,
                    visualModulationEnabled = false,
                    breatheAmplitude = 0.06f,
                    breathePeriodSec = 25,
                    dimTimeoutSec = 120,
                    dimContentAlpha = 0.25f,
                    dimBacklight = 0.5f,
                    deepIdleTimeoutSec = 600,
                    deepIdleBacklight = 0.2f,
                    sleepTimeoutSec = 1_200,
                    sleepBacklight = 0.02f,
                    movementSpeedKph = 2.0,
                    movementMeters = 5.0,
                ).coerced()

            prefs.setBurnInConfig(cfg)

            assertEquals(cfg, prefs.readBurnInConfig())
        }

    private fun TestScope.newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = this.backgroundScope) { tmp.newFile("prefs_${nextFileId++}.preferences_pb") }

    private fun TestScope.newPrefs(): DataStoreCockpitPreferences = DataStoreCockpitPreferences(newStore())

    private companion object {
        const val ZOOM_TOLERANCE = 1e-9
        private var nextFileId = 0
    }
}

private typealias TestScope = kotlinx.coroutines.test.TestScope
