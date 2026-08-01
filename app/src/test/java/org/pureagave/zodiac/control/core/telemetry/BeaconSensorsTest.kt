package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconSensorsTest {
    @Test
    fun default_construction_is_all_empty() {
        val s = BeaconSensors()
        assertNull(s.ambientLight)
        assertNull(s.beaconHealth)
        assertNull(s.odometer)
        assertEquals(0.0, s.lastShockG, 0.0)
        assertEquals(0L, s.shockCount)
    }

    @Test
    fun copy_increments_shock_count_monotonically() {
        val first = BeaconSensors(lastShockG = 1.8, shockCount = 1)
        val second = first.copy(shockCount = first.shockCount + 1)
        assertEquals(2L, second.shockCount)
        // The peak g rides along untouched by the count bump.
        assertEquals(1.8, second.lastShockG, 0.0)
    }

    @Test
    fun value_equality_matches_on_all_fields() {
        val a =
            BeaconSensors(
                ambientLight = AmbientLight(lux = 315.0),
                beaconHealth = BeaconHealth(batteryPct = 87, fixQuality = 1, satellites = 9, uptimeSec = 3600L),
                odometer = Odometer(tripMeters = 10.0, totalMeters = 20.0),
                lastShockG = 2.0,
                shockCount = 3,
            )
        val b = a.copy()
        assertEquals(a, b)
        // A single differing field breaks equality.
        assertNotEquals(a, a.copy(shockCount = 4))
    }
}
