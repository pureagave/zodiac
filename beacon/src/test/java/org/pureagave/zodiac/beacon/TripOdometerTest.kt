package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Test

class TripOdometerTest {
    @Test
    fun first_fix_adds_nothing() {
        val odo = TripOdometer()
        assertEquals(0.0, odo.add(0.0, 0.0), 0.0)
        assertEquals(0.0, odo.tripMeters, 0.0)
    }

    @Test
    fun a_real_move_accumulates_haversine_distance() {
        val odo = TripOdometer()
        odo.add(0.0, 0.0)
        // 0.001° of longitude at the equator ≈ 111.32 m.
        val d = odo.add(0.0, 0.001)
        assertEquals(111.32, d, 1.0)
        assertEquals(111.32, odo.tripMeters, 1.0)
    }

    @Test
    fun sub_floor_jitter_is_ignored_and_the_anchor_is_held() {
        val odo = TripOdometer(jitterFloorM = 5.0)
        odo.add(0.0, 0.0)
        assertEquals(0.0, odo.add(0.0, 0.00001), 0.0) // ~1.1 m, under the 5 m floor
        // Anchor stayed at (0,0), so a step clearing the floor counts the full
        // distance from the original anchor (~11.1 m), not just the last leg.
        val d = odo.add(0.0, 0.00010)
        assertEquals(11.13, d, 1.0)
        assertEquals(11.13, odo.tripMeters, 1.0)
    }

    @Test
    fun total_is_seeded_and_accumulates_alongside_trip() {
        val odo = TripOdometer(totalSeedM = 1_000.0)
        odo.add(0.0, 0.0)
        odo.add(0.0, 0.001) // ~111 m
        assertEquals(111.32, odo.tripMeters, 1.0)
        assertEquals(1_111.32, odo.totalMeters, 1.0)
    }
}
