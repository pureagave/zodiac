package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarContactTest {
    private fun poi(
        name: String,
        east: Double,
        north: Double,
        kind: PoiKind = PoiKind.CAMP,
    ) = PlayaPoi(uid = name, name = name, kind = kind, point = PlayaPoint(east, north), subtitle = "")

    private val center = PlayaPoint(0.0, 0.0)

    @Test
    fun keeps_only_contacts_within_range_nearest_first() {
        val pois =
            listOf(
                poi("far", 3000.0, 0.0),
                poi("near", 100.0, 0.0),
                poi("mid", 500.0, 0.0),
            )
        val result = contactsWithinRange(pois, center, rangeM = 1000.0, max = 10)
        assertEquals(listOf("near", "mid"), result.map { it.poi.name })
        assertEquals(100.0, result.first().distanceM, 0.001)
    }

    @Test
    fun caps_at_max_keeping_the_closest() {
        val pois = (1..50).map { poi("p$it", it * 10.0, 0.0) }
        val result = contactsWithinRange(pois, center, rangeM = 100_000.0, max = 5)
        assertEquals(5, result.size)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), result.map { it.poi.name })
    }

    @Test
    fun drops_unplaceable_pois() {
        val pois =
            listOf(
                poi("placed", 50.0, 0.0),
                PlayaPoi(uid = "u", name = "no-loc", kind = PoiKind.ART, point = null, subtitle = ""),
            )
        val result = contactsWithinRange(pois, center, rangeM = 1000.0, max = 10)
        assertEquals(listOf("placed"), result.map { it.poi.name })
    }

    @Test
    fun distance_is_measured_from_the_given_center_not_the_origin() {
        val pois = listOf(poi("p", 1000.0, 0.0))
        // Ego sits right next to the POI: it should be in range even for a small radius.
        val result = contactsWithinRange(pois, PlayaPoint(990.0, 0.0), rangeM = 50.0, max = 10)
        assertEquals(1, result.size)
        assertEquals(10.0, result.first().distanceM, 0.001)
    }

    @Test
    fun pulse_is_brightest_when_the_arm_is_on_the_blip() {
        assertEquals(1f, contactPulse(sweepDeg = 90f, blipAngleDeg = 90f), 0.001f)
    }

    @Test
    fun pulse_decays_to_the_floor_behind_the_arm() {
        // Arm has swept far past the blip (270° of trailing angle) → floored.
        val floored = contactPulse(sweepDeg = 300f, blipAngleDeg = 30f, floor = 0.28f, fadeSpanDeg = 200f)
        assertEquals(0.28f, floored, 0.001f)
    }

    @Test
    fun pulse_fades_monotonically_through_the_span() {
        val justPassed = contactPulse(sweepDeg = 100f, blipAngleDeg = 90f)
        val longAgo = contactPulse(sweepDeg = 180f, blipAngleDeg = 90f)
        assertTrue("closer to the arm should be brighter", justPassed > longAgo)
    }

    @Test
    fun pulse_handles_wraparound_across_zero() {
        // Arm at 10°, blip at 350°: the arm passed the blip 20° ago (not 340°).
        val nearPass = contactPulse(sweepDeg = 10f, blipAngleDeg = 350f, fadeSpanDeg = 200f)
        val expected = contactPulse(sweepDeg = 20f, blipAngleDeg = 0f, fadeSpanDeg = 200f)
        assertEquals(expected, nearPass, 0.001f)
    }
}
