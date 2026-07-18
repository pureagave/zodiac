package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection

class NearestTargetTest {
    private val projection = PlayaProjection(GoldenSpike.Y2025)

    /** A LatLon [e] metres east and [n] metres north of the Man. */
    private fun at(
        e: Double,
        n: Double,
    ) = projection.unproject(PlayaPoint(eastM = e, northM = n))

    @Test
    fun picks_the_closest_candidate_and_labels_it() {
        val ego = GoldenSpike.Y2025
        val target = nearestDriveTarget("BATH", ego, listOf(at(500.0, 0.0), at(120.0, 0.0), at(0.0, 900.0)), projection)!!
        assertEquals("BATH", target.label)
        assertEquals(120.0, projection.distanceMeters(ego, target.location), 1.0)
    }

    @Test
    fun null_when_there_is_no_fix() {
        assertNull(nearestDriveTarget("BATH", null, listOf(at(1.0, 1.0)), projection))
    }

    @Test
    fun null_when_there_are_no_candidates() {
        assertNull(nearestDriveTarget("BATH", GoldenSpike.Y2025, emptyList(), projection))
    }

    @Test
    fun relative_bearing_is_signed_right_positive_left_negative() {
        assertEquals(90.0, relativeBearingDeg(90.0, 0.0), 1e-9)
        assertEquals(-90.0, relativeBearingDeg(0.0, 90.0), 1e-9)
    }

    @Test
    fun relative_bearing_wraps_the_short_way_across_north() {
        // Target 350°, heading 10° → the short turn is 20° left, not 340° right.
        assertEquals(-20.0, relativeBearingDeg(350.0, 10.0), 1e-9)
        assertEquals(20.0, relativeBearingDeg(10.0, 350.0), 1e-9)
    }
}
