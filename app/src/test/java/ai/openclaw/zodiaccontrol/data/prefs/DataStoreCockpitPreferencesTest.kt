package ai.openclaw.zodiaccontrol.data.prefs

import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
            prefs.setConcept(CockpitConcept.C)

            val snapshot = prefs.read()
            assertEquals(LocationSourceType.BLE, snapshot.locationSource)
            assertEquals(MapMode.TILT, snapshot.mapMode)
            assertEquals(55, snapshot.tiltDeg)
            assertEquals(0.42, snapshot.pixelsPerMeter, ZOOM_TOLERANCE)
            assertEquals(CockpitConcept.C, snapshot.concept)
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

    private fun TestScope.newPrefs(): DataStoreCockpitPreferences {
        val store = PreferenceDataStoreFactory.create(scope = this.backgroundScope) { tmp.newFile("prefs.preferences_pb") }
        return DataStoreCockpitPreferences(store)
    }

    private companion object {
        const val ZOOM_TOLERANCE = 1e-9
    }
}

private typealias TestScope = kotlinx.coroutines.test.TestScope
