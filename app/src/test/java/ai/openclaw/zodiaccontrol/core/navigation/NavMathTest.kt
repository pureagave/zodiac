package ai.openclaw.zodiaccontrol.core.navigation

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMathTest {
    @Test
    fun `headingUnitVector north is plus Y`() {
        val v = headingUnitVector(0.0)
        assertEquals(0.0, v.eastM, 1e-9)
        assertEquals(1.0, v.northM, 1e-9)
    }

    @Test
    fun `headingUnitVector east is plus X`() {
        val v = headingUnitVector(90.0)
        assertEquals(1.0, v.eastM, 1e-9)
        assertEquals(0.0, v.northM, 1e-9)
    }

    @Test
    fun `bearingFromOriginTo - point due north returns 0`() {
        assertEquals(0.0, bearingFromOriginTo(PlayaPoint(0.0, 100.0)), 1e-6)
    }

    @Test
    fun `bearingFromOriginTo - point due east returns 90`() {
        assertEquals(90.0, bearingFromOriginTo(PlayaPoint(100.0, 0.0)), 1e-6)
    }

    @Test
    fun `pointToSegmentDistance - midpoint returns perpendicular distance`() {
        val a = PlayaPoint(0.0, 0.0)
        val b = PlayaPoint(100.0, 0.0)
        val p = PlayaPoint(50.0, 30.0)
        assertEquals(30.0, pointToSegmentDistance(p, a, b), 1e-6)
    }

    @Test
    fun `pointToSegmentDistance - clamps to endpoint`() {
        val a = PlayaPoint(0.0, 0.0)
        val b = PlayaPoint(100.0, 0.0)
        val p = PlayaPoint(150.0, 30.0)
        assertEquals(Math.hypot(50.0, 30.0), pointToSegmentDistance(p, a, b), 1e-6)
    }

    @Test
    fun `pointToPolylineDistance - takes the minimum across segments`() {
        val pts =
            listOf(
                PlayaPoint(0.0, 0.0),
                PlayaPoint(100.0, 0.0),
                PlayaPoint(100.0, 100.0),
            )
        // (70, 30): perpendicular distance to the bottom segment is 30,
        // perpendicular distance to the right segment is also 30 — the
        // polyline distance must report the smaller (here equal) value.
        assertEquals(30.0, pointToPolylineDistance(PlayaPoint(70.0, 30.0), pts), 1e-6)
        // (100, 50) sits exactly on the right segment.
        assertEquals(0.0, pointToPolylineDistance(PlayaPoint(100.0, 50.0), pts), 1e-6)
    }

    @Test
    fun `rayPolygonForwardHit - ray from inside square exits at +X edge`() {
        val square =
            listOf(
                PlayaPoint(-100.0, -100.0),
                PlayaPoint(100.0, -100.0),
                PlayaPoint(100.0, 100.0),
                PlayaPoint(-100.0, 100.0),
            )
        val origin = PlayaPoint(0.0, 0.0)
        val east = headingUnitVector(90.0)
        val hit = rayPolygonForwardHit(origin, east, square)
        assertNotNull(hit)
        assertEquals(100.0, hit!!.point.eastM, 1e-6)
        assertEquals(0.0, hit.point.northM, 1e-6)
        assertEquals(100.0, hit.distanceM, 1e-6)
    }

    @Test
    fun `rayPolygonForwardHit - takes nearest forward edge, not the far side`() {
        val square =
            listOf(
                PlayaPoint(-100.0, -100.0),
                PlayaPoint(100.0, -100.0),
                PlayaPoint(100.0, 100.0),
                PlayaPoint(-100.0, 100.0),
            )
        // Origin near the +X edge, ray east → hits +X edge first, not -X
        val hit = rayPolygonForwardHit(PlayaPoint(50.0, 0.0), headingUnitVector(90.0), square)
        assertNotNull(hit)
        assertEquals(100.0, hit!!.point.eastM, 1e-6)
        assertEquals(50.0, hit.distanceM, 1e-6)
    }

    @Test
    fun `rayPolygonForwardHit - ignores intersections behind the origin`() {
        val square =
            listOf(
                PlayaPoint(-100.0, -100.0),
                PlayaPoint(100.0, -100.0),
                PlayaPoint(100.0, 100.0),
                PlayaPoint(-100.0, 100.0),
            )
        // Stand outside on the +X side, ray east → no hits in front
        val hit = rayPolygonForwardHit(PlayaPoint(200.0, 0.0), headingUnitVector(90.0), square)
        assertNull(hit)
    }

    @Test
    fun `pointInPolygon - inside square`() {
        val square =
            listOf(
                PlayaPoint(-100.0, -100.0),
                PlayaPoint(100.0, -100.0),
                PlayaPoint(100.0, 100.0),
                PlayaPoint(-100.0, 100.0),
            )
        assertTrue(pointInPolygon(PlayaPoint(0.0, 0.0), square))
        assertFalse(pointInPolygon(PlayaPoint(200.0, 0.0), square))
        assertFalse(pointInPolygon(PlayaPoint(0.0, 200.0), square))
    }
}
