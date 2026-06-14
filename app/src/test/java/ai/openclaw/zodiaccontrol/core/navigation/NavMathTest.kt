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

    @Test
    fun `headingUnitVector south is minus Y`() {
        val v = headingUnitVector(180.0)
        assertEquals(0.0, v.eastM, 1e-9)
        assertEquals(-1.0, v.northM, 1e-9)
    }

    @Test
    fun `headingUnitVector west is minus X`() {
        val v = headingUnitVector(270.0)
        assertEquals(-1.0, v.eastM, 1e-9)
        assertEquals(0.0, v.northM, 1e-9)
    }

    @Test
    fun `bearingFromOriginTo - point due south returns 180`() {
        assertEquals(180.0, bearingFromOriginTo(PlayaPoint(0.0, -100.0)), 1e-6)
    }

    @Test
    fun `bearingFromOriginTo - point due west returns 270`() {
        assertEquals(270.0, bearingFromOriginTo(PlayaPoint(-100.0, 0.0)), 1e-6)
    }

    @Test
    fun `pointToSegmentDistance - degenerate segment returns distance to point a`() {
        val a = PlayaPoint(10.0, 20.0)
        val b = PlayaPoint(10.0, 20.0)
        val p = PlayaPoint(13.0, 24.0)
        // a == b (abLenSq below epsilon) → distance is just |p - a| = hypot(3, 4) = 5.
        assertEquals(5.0, pointToSegmentDistance(p, a, b), 1e-6)
    }

    @Test
    fun `pointToPolylineDistance - empty list returns MAX_VALUE`() {
        assertEquals(
            Double.MAX_VALUE,
            pointToPolylineDistance(PlayaPoint(0.0, 0.0), emptyList()),
            0.0,
        )
    }

    @Test
    fun `pointToPolylineDistance - single point returns distance to it`() {
        val pts = listOf(PlayaPoint(3.0, 4.0))
        // Distance from origin to the lone vertex is hypot(3, 4) = 5.
        assertEquals(5.0, pointToPolylineDistance(PlayaPoint(0.0, 0.0), pts), 1e-6)
    }

    @Test
    fun `pointInPolygon - fewer than three points is false`() {
        assertFalse(pointInPolygon(PlayaPoint(0.0, 0.0), emptyList()))
        assertFalse(pointInPolygon(PlayaPoint(0.0, 0.0), listOf(PlayaPoint(1.0, 1.0))))
    }

    @Test
    fun `pointInPolygon - two point polygon is false`() {
        val twoPoint =
            listOf(
                PlayaPoint(-100.0, 0.0),
                PlayaPoint(100.0, 0.0),
            )
        assertFalse(pointInPolygon(PlayaPoint(0.0, 0.0), twoPoint))
    }

    @Test
    fun `rayPolygonForwardHit - fewer than two points returns null`() {
        assertNull(rayPolygonForwardHit(PlayaPoint(0.0, 0.0), headingUnitVector(90.0), emptyList()))
        assertNull(
            rayPolygonForwardHit(
                PlayaPoint(0.0, 0.0),
                headingUnitVector(90.0),
                listOf(PlayaPoint(10.0, 10.0)),
            ),
        )
    }

    @Test
    fun `rayPolygonForwardHit - hit lands on the square boundary with positive distance`() {
        val square =
            listOf(
                PlayaPoint(-100.0, -100.0),
                PlayaPoint(100.0, -100.0),
                PlayaPoint(100.0, 100.0),
                PlayaPoint(-100.0, 100.0),
            )
        // Cast north from the centre: the boundary point must lie on the +Y edge.
        val hit = rayPolygonForwardHit(PlayaPoint(0.0, 0.0), headingUnitVector(0.0), square)
        assertNotNull(hit)
        assertEquals(100.0, hit!!.point.northM, 1e-6)
        assertEquals(0.0, hit.point.eastM, 1e-6)
        assertTrue(hit.distanceM > 0.0)
    }

    @Test
    fun `dot - returns sum of componentwise products`() {
        // (3, 4) · (5, 6) = 15 + 24 = 39.
        assertEquals(39.0, dot(PlayaPoint(3.0, 4.0), PlayaPoint(5.0, 6.0)), 1e-9)
        // Perpendicular vectors dot to zero.
        assertEquals(0.0, dot(PlayaPoint(1.0, 0.0), PlayaPoint(0.0, 1.0)), 1e-9)
    }

    @Test
    fun `minus - subtracts componentwise`() {
        val d = minus(PlayaPoint(10.0, 5.0), PlayaPoint(3.0, 8.0))
        assertEquals(7.0, d.eastM, 1e-9)
        assertEquals(-3.0, d.northM, 1e-9)
    }

    @Test
    fun `distanceFromOrigin - returns hypot of the components`() {
        assertEquals(5.0, distanceFromOrigin(PlayaPoint(3.0, 4.0)), 1e-9)
        assertEquals(0.0, distanceFromOrigin(PlayaPoint(0.0, 0.0)), 1e-9)
    }
}
