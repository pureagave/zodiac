package org.pureagave.zodiac.control.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class PlayaProjectionTest {
    private val origin = GoldenSpike.Y2025
    private val proj = PlayaProjection(origin)

    @Test
    fun origin_projectsToZero() {
        val p = proj.project(origin)
        assertEquals(0.0, p.eastM, EPSILON)
        assertEquals(0.0, p.northM, EPSILON)
    }

    @Test
    fun north_offset_only_affects_north() {
        // 0.001° lat ≈ 111 m north at any longitude
        val p = proj.project(LatLon(lon = origin.lon, lat = origin.lat + 0.001))
        assertEquals(0.0, p.eastM, EPSILON)
        assertEquals(111.19, p.northM, 0.5)
    }

    @Test
    fun east_offset_only_affects_east() {
        // 0.001° lon ≈ 111 m * cos(40.79°) ≈ 84 m east
        val p = proj.project(LatLon(lon = origin.lon + 0.001, lat = origin.lat))
        assertEquals(84.10, p.eastM, 0.5)
        assertEquals(0.0, p.northM, EPSILON)
    }

    @Test
    fun project_then_unproject_is_identity_within_micro_degree() {
        val target = LatLon(lon = -119.21, lat = 40.79)
        val round = proj.unproject(proj.project(target))
        assertEquals(target.lon, round.lon, 1e-9)
        assertEquals(target.lat, round.lat, 1e-9)
    }

    @Test
    fun trash_fence_radius_is_in_expected_range() {
        // Outer trash-fence vertex from the 2025 dataset; should sit ~2.5 km from the Spike.
        val fenceVertex = LatLon(lon = -119.23273810046265, lat = 40.783393446219854)
        val p = proj.project(fenceVertex)
        val r = hypot(p.eastM, p.northM)
        assertTrue("fence radius $r m outside [2000, 3500]", r in 2_000.0..3_500.0)
    }

    @Test
    fun distance_is_symmetric() {
        val a = LatLon(lon = -119.20, lat = 40.78)
        val b = LatLon(lon = -119.21, lat = 40.79)
        val ab = proj.distanceMeters(a, b)
        val ba = proj.distanceMeters(b, a)
        assertTrue(abs(ab - ba) < 1e-6)
    }

    @Test
    fun roundTrip_origin_recoversExactly() {
        val round = proj.unproject(proj.project(origin))
        assertEquals(origin.lon, round.lon, TIGHT)
        assertEquals(origin.lat, round.lat, TIGHT)
    }

    @Test
    fun roundTrip_northPoint_recoversExactly() {
        val target = LatLon(lon = origin.lon, lat = origin.lat + 0.02)
        val round = proj.unproject(proj.project(target))
        assertEquals(target.lon, round.lon, TIGHT)
        assertEquals(target.lat, round.lat, TIGHT)
    }

    @Test
    fun roundTrip_eastPoint_recoversExactly() {
        val target = LatLon(lon = origin.lon + 0.02, lat = origin.lat)
        val round = proj.unproject(proj.project(target))
        assertEquals(target.lon, round.lon, TIGHT)
        assertEquals(target.lat, round.lat, TIGHT)
    }

    @Test
    fun roundTrip_diagonalKilometerScaleOffset_recoversExactly() {
        // ~1 km diagonal offset at BRC's latitude (≈0.009° lat, 0.012° lon).
        val target = LatLon(lon = origin.lon + 0.012, lat = origin.lat + 0.009)
        val p = proj.project(target)
        assertTrue("expected ~1 km offset, got ${hypot(p.eastM, p.northM)} m", hypot(p.eastM, p.northM) in 1_000.0..1_800.0)
        val round = proj.unproject(p)
        assertEquals(target.lon, round.lon, TIGHT)
        assertEquals(target.lat, round.lat, TIGHT)
    }

    @Test
    fun projectInline_matchesProject_forDiagonalPoint() {
        val target = LatLon(lon = origin.lon + 0.012, lat = origin.lat + 0.009)
        val expected = proj.project(target)
        var east = Double.NaN
        var north = Double.NaN
        proj.projectInline(lon = target.lon, lat = target.lat) { e, n ->
            east = e
            north = n
        }
        assertEquals(expected.eastM, east, EPSILON)
        assertEquals(expected.northM, north, EPSILON)
    }

    @Test
    fun projectInline_matchesProject_atOrigin() {
        val expected = proj.project(origin)
        var east = Double.NaN
        var north = Double.NaN
        proj.projectInline(lon = origin.lon, lat = origin.lat) { e, n ->
            east = e
            north = n
        }
        assertEquals(expected.eastM, east, EPSILON)
        assertEquals(expected.northM, north, EPSILON)
    }

    @Test
    fun distance_fromPointToItself_isZero() {
        val a = LatLon(lon = -119.21, lat = 40.79)
        assertEquals(0.0, proj.distanceMeters(a, a), EPSILON)
    }

    @Test
    fun distance_pureLatitudeOffset_matchesEarthRadiusArc() {
        // A pure-latitude offset of d degrees ≈ EARTH_RADIUS_M * toRadians(d).
        val d = 0.01
        val a = origin
        val b = LatLon(lon = origin.lon, lat = origin.lat + d)
        val expected = PlayaProjection.EARTH_RADIUS_M * Math.toRadians(d)
        val actual = proj.distanceMeters(a, b)
        assertTrue("expected ~$expected m, got $actual m", abs(actual - expected) / expected < 1e-9)
    }

    @Test
    fun project_greaterLat_yieldsPositiveNorth() {
        val p = proj.project(LatLon(lon = origin.lon, lat = origin.lat + 0.005))
        assertTrue("northM should be positive, got ${p.northM}", p.northM > 0.0)
        assertEquals(0.0, p.eastM, EPSILON)
    }

    @Test
    fun project_greaterLon_yieldsPositiveEastScaledByCosLat() {
        val dLon = 0.005
        val p = proj.project(LatLon(lon = origin.lon + dLon, lat = origin.lat))
        assertTrue("eastM should be positive, got ${p.eastM}", p.eastM > 0.0)
        assertEquals(0.0, p.northM, EPSILON)
        // east is scaled by cos(lat0): EARTH_RADIUS_M * cosLat0 * toRadians(dLon).
        val expected = PlayaProjection.EARTH_RADIUS_M * cos(Math.toRadians(origin.lat)) * Math.toRadians(dLon)
        assertEquals(expected, p.eastM, EPSILON)
    }

    @Test
    fun project_twoBrcPoints_haveExpectedMetreDelta() {
        // Two points 0.001° lat apart (pure north): metre delta ≈ EARTH_RADIUS_M * toRadians(0.001).
        val a = LatLon(lon = -119.21, lat = 40.785)
        val b = LatLon(lon = -119.21, lat = 40.786)
        val pa = proj.project(a)
        val pb = proj.project(b)
        val expected = PlayaProjection.EARTH_RADIUS_M * Math.toRadians(0.001)
        assertEquals(expected, pb.northM - pa.northM, EPSILON)
        assertEquals(0.0, pb.eastM - pa.eastM, EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-6
        const val TIGHT = 1e-6
    }
}
