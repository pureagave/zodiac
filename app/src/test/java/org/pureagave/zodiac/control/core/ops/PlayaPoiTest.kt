package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import kotlin.math.atan2
import kotlin.math.hypot

class PlayaPoiTest {
    private val projection = PlayaProjection(GoldenSpike.Y2025)

    private fun bearingOf(
        east: Double,
        north: Double,
    ): Double = ((Math.toDegrees(atan2(east, north)) % 360.0) + 360.0) % 360.0

    @Test
    fun the_mans_own_gps_projects_to_the_origin() {
        val p = artPoint(GoldenSpike.Y2025.lat, GoldenSpike.Y2025.lon, projection)
        assertEquals(0.0, hypot(p.eastM, p.northM), 1.0)
    }

    @Test
    fun temple_gps_lands_on_the_12_oclock_axis() {
        // 2025 "Temple of the Deep" GPS — ~762 m out on the 12:00 radial (45° true).
        val p = artPoint(40.791799, -119.196602, projection)
        assertEquals(762.0, hypot(p.eastM, p.northM), 25.0)
        assertEquals(45.0, bearingOf(p.eastM, p.northM), 3.0)
    }

    @Test
    fun camp_clock_and_street_project_to_the_right_ring_and_bearing() {
        // "2:00 & E": 2:00 → 105° true (axis 45° + 60°); E ring ≈ 1237 m.
        val p = campPoint("2:00", "E")!!
        assertEquals(1237.0, hypot(p.eastM, p.northM), 1.0)
        assertEquals(105.0, bearingOf(p.eastM, p.northM), 0.5)
    }

    @Test
    fun camp_with_unplaceable_address_is_null() {
        assertNull(campPoint("2:00", "Center Camp Plaza"))
        assertNull(campPoint(null, "E"))
        assertNull(campPoint("2:00", null))
    }
}
