package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampGuidanceTest {
    private val projection = PlayaProjection(GoldenSpike.Y2025)

    @Test
    fun from_the_man_camp_is_on_the_2_15_radial_at_the_h_ring() {
        val g = campGuidance(ego = GoldenSpike.Y2025, target = Camp.GALACTIC_RELAY, projection = projection)
        // 2:15 radial = 112.5° true; Heiau ≈ Herbert H-ring ≈ 1555 m from the Man.
        assertEquals(112.5, g.bearingDeg, 1.0)
        assertEquals(1555.0, g.distanceM, 15.0)
    }

    @Test
    fun standing_at_camp_distance_is_zero() {
        val g = campGuidance(ego = Camp.GALACTIC_RELAY, target = Camp.GALACTIC_RELAY, projection = projection)
        assertTrue("expected ~0 m, got ${g.distanceM}", g.distanceM < 1.0)
    }

    @Test
    fun bearing_points_back_toward_camp_from_the_far_side() {
        // A point due *north-west* of camp (out past 8:00) should send the driver
        // roughly south-east — bearing in the lower-right quadrant (90..180).
        val northWestOfCamp = LatLon(lon = Camp.GALACTIC_RELAY.lon - 0.02, lat = Camp.GALACTIC_RELAY.lat + 0.02)
        val g = campGuidance(ego = northWestOfCamp, target = Camp.GALACTIC_RELAY, projection = projection)
        assertTrue("bearing ${g.bearingDeg} should be SE-ish", g.bearingDeg in 100.0..170.0)
        assertTrue(g.distanceM > 1_000.0)
    }
}
