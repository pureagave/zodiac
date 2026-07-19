package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.bearingFromOriginTo
import org.pureagave.zodiac.control.core.navigation.clockToBearing
import org.pureagave.zodiac.control.core.navigation.distanceFromOrigin

class DriveTargetTest {
    private val projection = PlayaProjection(GoldenSpike.Y2025)

    @Test
    fun address_lands_on_the_ring_radius_at_the_clock_bearing() {
        val target = addressTarget(ClockTime(2, 15), "H", projection)!!
        assertEquals("2:15 & H", target.label)
        // Project back: it should sit on the H ring (1555 m) at the 2:15 bearing.
        val p = projection.project(target.location)
        assertEquals(1555.0, distanceFromOrigin(p), 5.0)
        assertEquals(clockToBearing(ClockTime(2, 15)), bearingFromOriginTo(p), 0.5)
    }

    @Test
    fun esplanade_and_ring_lookup_is_case_and_whitespace_insensitive() {
        val esp = addressTarget(ClockTime(6, 0), "Esplanade", projection)!!
        assertEquals(752.0, distanceFromOrigin(projection.project(esp.location)), 5.0)
        assertNotNull(addressTarget(ClockTime(3, 0), " k ", projection)) // lowercase + spaces resolve
    }

    @Test
    fun unknown_ring_is_null() {
        assertNull(addressTarget(ClockTime(4, 0), "Z", projection))
    }

    @Test
    fun preset_to_drive_target_keeps_label_and_location() {
        val dt = NavTarget.HOME.toDriveTarget()
        assertEquals(NavTarget.HOME.label, dt.label)
        assertEquals(NavTarget.HOME.location, dt.location)
    }
}
