package ai.openclaw.zodiaccontrol.core.navigation

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.model.StreetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PlayaRouterTest {
    private val man = PlayaPoint(0.0, 0.0)

    private fun polar(
        radiusM: Double,
        bearingDeg: Double,
    ): PlayaPoint {
        val rad = Math.toRadians(bearingDeg)
        return PlayaPoint(eastM = radiusM * sin(rad), northM = radiusM * cos(rad))
    }

    // Dense polylines (unlike a bare inner/outer pair) so the router's snap-to-
    // real-street logic has vertices to walk — mirroring the real GIS data.
    private fun radial(
        name: String,
        clock: ClockTime,
    ): PlayaStreet {
        val b = clockToBearing(clock)
        val pts = (0..10).map { polar(ESPLANADE_R + it * 100.0, b) } // 752 m out to 1752 m
        return PlayaStreet(name, StreetKind.Radial, pts)
    }

    private fun arc(
        name: String,
        radiusM: Double,
    ): PlayaStreet {
        val pts = (100..140 step 4).map { polar(radiusM, it.toDouble()) } // 2:00 → ~3:00 sweep
        return PlayaStreet(name, StreetKind.Arc, pts)
    }

    private val arcRadii = mapOf("Esplanade" to ESPLANADE_R, "H" to 1555.0, "K" to 1753.0)

    private val city =
        PlayaCityModel(
            trashFenceM = emptyList(),
            streetsM =
                listOf(
                    radial("2:00", ClockTime(2, 0)),
                    radial("2:30", ClockTime(2, 30)),
                    radial("3:00", ClockTime(3, 0)),
                ) + arcRadii.map { (n, r) -> arc(n, r) },
            arcsInnerToOuter = listOf("Esplanade", "H", "K"),
            arcRadiiM = arcRadii,
            cityOuterRadiusM = 1753.0,
            axisBearingDeg = BRC_AXIS_BEARING_DEG_2025,
        )

    private fun near(
        a: PlayaPoint,
        b: PlayaPoint,
        tolM: Double,
    ): Boolean = hypot(a.eastM - b.eastM, a.northM - b.northM) < tolM

    @Test
    fun a_city_camp_routes_in_through_the_nearest_entrance_radial() {
        // H & 2:30, from the Man out on the open playa.
        val dest = polar(1555.0, clockToBearing(ClockTime(2, 30)))
        val route = routeTo(man, dest, city)

        assertEquals("2:30", route.entranceRadial)
        // First corner is the Esplanade entrance on the 2:30 radial.
        val entrance = route.waypointsM.first()
        assertEquals(ESPLANADE_R, distanceFromOrigin(entrance), 5.0)
        assertEquals(clockToBearing(ClockTime(2, 30)), bearingFromOriginTo(entrance), 1.0)
        // Route ends on the ring at the address.
        assertTrue(near(dest, route.waypointsM.last(), 10.0))
    }

    @Test
    fun a_camp_off_2_15_snaps_to_a_real_entrance_radial() {
        val dest = polar(1555.0, clockToBearing(ClockTime(2, 15)))
        val route = routeTo(man, dest, city)
        // 2:15 has no radial street; enter on 2:00 or 2:30 (both 7.5° away).
        assertTrue(route.entranceRadial in setOf("2:00", "2:30"))
    }

    @Test
    fun in_city_route_stays_on_the_radial_then_the_ring() {
        // Off-radial address → the route has a real arc leg; every corner must
        // sit on the entrance radial (constant bearing) or the ring (constant
        // radius), never floating across the blocks between streets.
        val dest = polar(1555.0, clockToBearing(ClockTime(2, 15)))
        val route = routeTo(man, dest, city)
        val entranceBearing = clockToBearing(if (route.entranceRadial == "2:00") ClockTime(2, 0) else ClockTime(2, 30))
        route.waypointsM.forEach { p ->
            val onRadial = abs(bearingFromOriginTo(p) - entranceBearing) < 1.0
            val onRing = abs(distanceFromOrigin(p) - 1555.0) < 20.0
            assertTrue("waypoint $p is off both the radial and the ring", onRadial || onRing)
        }
    }

    @Test
    fun open_playa_targets_get_a_straight_line() {
        // The Temple on the 12:00 axis sits in the open 10–2 mouth.
        val temple = polar(762.0, clockToBearing(ClockTime(12, 0)))
        val route = routeTo(man, temple, city)
        assertNull(route.entranceRadial)
        assertEquals(listOf(temple), route.waypointsM)
    }

    @Test
    fun inside_the_esplanade_is_free_drive() {
        val inner = polar(400.0, clockToBearing(ClockTime(4, 0)))
        val route = routeTo(man, inner, city)
        assertNull(route.entranceRadial)
        assertEquals(1, route.waypointsM.size)
    }

    @Test
    fun next_waypoint_is_the_entrance_before_you_start_and_advances_as_you_go() {
        val dest = polar(1555.0, clockToBearing(ClockTime(2, 30)))
        val route = routeTo(man, dest, city)

        // Parked at the Man: steer to the first corner (the entrance).
        assertEquals(route.waypointsM.first(), nextWaypoint(route.waypointsM, man))

        // Sitting on the last leg (ring∩radial → dest): steer to the far end.
        val last = route.waypointsM.last()
        val lastCorner = route.waypointsM[route.waypointsM.size - 2]
        val onLastLeg =
            PlayaPoint(
                eastM = (lastCorner.eastM + last.eastM) / 2,
                northM = (lastCorner.northM + last.northM) / 2,
            )
        assertEquals(last, nextWaypoint(route.waypointsM, onLastLeg))
    }

    private companion object {
        const val ESPLANADE_R = 752.0
    }
}
