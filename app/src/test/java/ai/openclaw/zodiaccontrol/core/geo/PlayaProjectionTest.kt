package ai.openclaw.zodiaccontrol.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
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

    private companion object {
        const val EPSILON = 1e-6
    }
}
