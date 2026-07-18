package org.pureagave.zodiac.control.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.model.StreetKind

/**
 * Synthetic city used by every test. Keeps the math hand-checkable:
 *  - trash fence: 4 km square centred on the Man.
 *  - Esplanade: ring of 12 points at radius 660 m.
 *  - Atwood, Bradbury, Cherryh: rings at 800, 940, 1080 m.
 *  - Radials: 4:30 (180° true) and 9:00 (315° true), each as a single
 *    LineString from the origin out to ~1500 m.
 *
 * BRC axis = 45° true → 12:00 maps to bearing 45°. The 4:30 radial sits at
 * 180° true (verified against real 2025 data); 9:00 sits at 315°.
 */
private fun fakeCity(): PlayaCityModel {
    val fence =
        listOf(
            PlayaPoint(-2000.0, -2000.0),
            PlayaPoint(2000.0, -2000.0),
            PlayaPoint(2000.0, 2000.0),
            PlayaPoint(-2000.0, 2000.0),
        )
    val esplanade = ring("Esplanade", 660.0)
    val atwood = ring("Atwood", 800.0)
    val bradbury = ring("Bradbury", 940.0)
    val cherryh = ring("Cherryh", 1080.0)

    val r430 = radial("4:30", trueBearingDeg = 180.0, lengthM = 1500.0)
    val r900 = radial("9:00", trueBearingDeg = 315.0, lengthM = 1500.0)

    return PlayaCityModel(
        trashFenceM = fence,
        streetsM = esplanade + atwood + bradbury + cherryh + r430 + r900,
        arcsInnerToOuter = listOf("Esplanade", "Atwood", "Bradbury", "Cherryh"),
        arcRadiiM =
            mapOf(
                "Esplanade" to 660.0,
                "Atwood" to 800.0,
                "Bradbury" to 940.0,
                "Cherryh" to 1080.0,
            ),
        cityOuterRadiusM = 1080.0,
        axisBearingDeg = BRC_AXIS_BEARING_DEG_2025,
    )
}

private fun ring(
    name: String,
    radius: Double,
): List<PlayaStreet> {
    val pts =
        (0..36).map { i ->
            val deg = i * 10.0
            val rad = Math.toRadians(deg)
            PlayaPoint(eastM = radius * Math.sin(rad), northM = radius * Math.cos(rad))
        }
    return listOf(PlayaStreet(name = name, kind = StreetKind.Arc, pointsM = pts))
}

private fun radial(
    name: String,
    trueBearingDeg: Double,
    lengthM: Double,
): List<PlayaStreet> {
    val rad = Math.toRadians(trueBearingDeg)
    val pts =
        (0..40).map { i ->
            val r = (i * lengthM / 40.0)
            PlayaPoint(eastM = r * Math.sin(rad), northM = r * Math.cos(rad))
        }
    return listOf(PlayaStreet(name = name, kind = StreetKind.Radial, pointsM = pts))
}

class PlayaNavigatorTest {
    private val city = fakeCity()

    @Test
    fun `deep playa heading directly at the Man emits TowardClock at the opposite-side gate`() {
        // Sit 1500 m SE of the Man on the diagonal — well outside the city
        // ring (1080 m), nowhere near a radial. Heading 315° true points
        // straight at the origin, then continues out the NW corner of the
        // 2 km square fence at (−2000, +2000) → bearing-from-Man 315° true
        // → BRC clock 9:00 cleanly.
        val ego = PlayaPoint(eastM = 1500.0, northM = -1500.0)
        val cue = computeNavigationCue(ego, headingDeg = 315, city = city)
        assertTrue("expected TowardClock, got $cue", cue is NavigationCue.TowardClock)
        val toward = cue as NavigationCue.TowardClock
        assertEquals(9, toward.clock.hours)
        assertEquals(0, toward.clock.minutes)
        // Distance from (1500, −1500) along (−√2/2, +√2/2) to (−2000, 2000):
        // |Δ| = √(3500² + 3500²) ≈ 4949 m.
        assertEquals(4950.0, toward.distanceM, 5.0)
    }

    @Test
    fun `deep playa heading directly outward emits AwayFromClock with backward clock`() {
        // Same SE position but heading 135° (outward from Man, away from city).
        // Backward ray runs at 315° → exits the fence at (−2000, +2000) → 9:00.
        val ego = PlayaPoint(eastM = 1500.0, northM = -1500.0)
        val cue = computeNavigationCue(ego, headingDeg = 135, city = city)
        assertTrue("expected AwayFromClock, got $cue", cue is NavigationCue.AwayFromClock)
        val away = cue as NavigationCue.AwayFromClock
        assertEquals(9, away.clock.hours)
        assertEquals(0, away.clock.minutes)
    }

    @Test
    fun `inner playa heading outward returns TowardClock with no minus sign`() {
        // 200 m south-east of Man (inside Esplanade, "inner playa"), heading SE (135° true).
        val ego = PlayaPoint(eastM = 100.0, northM = -100.0)
        val cue = computeNavigationCue(ego, headingDeg = 135, city = city)
        assertTrue("expected TowardClock, got $cue", cue is NavigationCue.TowardClock)
        val toward = cue as NavigationCue.TowardClock
        // 135° true → 90° clockDeg → 3:00 — we're aimed at the 3:00 gate on the trash fence.
        assertEquals(3, toward.clock.hours)
        assertEquals(0, toward.clock.minutes)
    }

    @Test
    fun `on the 4 colon 30 radial heading toward Man - returns OnRadialInbound to Esplanade`() {
        // Sit on the 4:30 radial (180° true) at 900 m — between Atwood and Bradbury.
        val ego = PlayaPoint(eastM = 0.0, northM = -900.0)
        // Heading north (0° true) = toward Man = inbound.
        val cue = computeNavigationCue(ego, headingDeg = 0, city = city)
        assertTrue("expected OnRadialInbound, got $cue", cue is NavigationCue.OnRadialInbound)
        val inbound = cue as NavigationCue.OnRadialInbound
        assertEquals("4:30", inbound.radialName)
        assertEquals("Esplanade", inbound.nextArc)
    }

    @Test
    fun `on the 4 colon 30 radial heading away from Man - returns OnRadialOutbound with last arc`() {
        // Sit on 4:30 at 900 m. Last passed arc going outward = Atwood (800 m).
        val ego = PlayaPoint(eastM = 0.0, northM = -900.0)
        // Heading south (180° true) = outward.
        val cue = computeNavigationCue(ego, headingDeg = 180, city = city)
        assertTrue("expected OnRadialOutbound, got $cue", cue is NavigationCue.OnRadialOutbound)
        val outbound = cue as NavigationCue.OnRadialOutbound
        assertEquals("4:30", outbound.radialName)
        assertEquals("Atwood", outbound.lastArc)
    }

    @Test
    fun `on Atwood arc - returns OnArc with current clock position`() {
        // Place on Atwood (radius 800) at clock-bearing 4:30 (true 180°).
        val ego = PlayaPoint(eastM = 0.0, northM = -800.0)
        val cue = computeNavigationCue(ego, headingDeg = 270, city = city)
        assertTrue("expected OnArc, got $cue", cue is NavigationCue.OnArc)
        val arc = cue as NavigationCue.OnArc
        assertEquals("Atwood", arc.arcName)
        assertEquals(4, arc.clock.hours)
        assertEquals(30, arc.clock.minutes)
    }

    @Test
    fun `arc clock ticks down as ego moves clockwise toward 4 colon 30`() {
        // Atwood, just past 5:00 going toward 4:30 (clockwise on the BRC face,
        // which means moving "left" relative to the man axis).
        val rad500 = Math.toRadians(195.0) // 5:00 = 5*30 + 45 = 195° true
        val rad445 = Math.toRadians(187.5) // 4:45 = 4*30 + 22.5 + 45 = 187.5°
        val ego500 = PlayaPoint(eastM = 800.0 * Math.sin(rad500), northM = 800.0 * Math.cos(rad500))
        val ego445 = PlayaPoint(eastM = 800.0 * Math.sin(rad445), northM = 800.0 * Math.cos(rad445))

        val c500 = computeNavigationCue(ego500, headingDeg = 270, city = city) as NavigationCue.OnArc
        val c445 = computeNavigationCue(ego445, headingDeg = 270, city = city) as NavigationCue.OnArc
        assertEquals(5, c500.clock.hours)
        assertEquals(0, c500.clock.minutes)
        assertEquals(4, c445.clock.hours)
        assertEquals(45, c445.clock.minutes)
    }

    @Test
    fun `outside trash fence returns Unknown`() {
        // Place ego well outside our 4 km square fence.
        val ego = PlayaPoint(eastM = 5000.0, northM = 0.0)
        val cue = computeNavigationCue(ego, headingDeg = 0, city = city)
        assertEquals(NavigationCue.Unknown, cue)
    }

    @Test
    fun `empty trash fence returns Unknown`() {
        val empty = city.copy(trashFenceM = emptyList())
        val cue = computeNavigationCue(PlayaPoint(0.0, 0.0), headingDeg = 0, city = empty)
        assertEquals(NavigationCue.Unknown, cue)
    }

    @Test
    fun `two point trash fence returns Unknown`() {
        // One point short of MIN_FENCE_POINTS (3): a degenerate fence can't be
        // a polygon, so we bail before any street/ray work.
        val twoPoint =
            city.copy(
                trashFenceM = listOf(PlayaPoint(-2000.0, -2000.0), PlayaPoint(2000.0, -2000.0)),
            )
        val cue = computeNavigationCue(PlayaPoint(0.0, 0.0), headingDeg = 0, city = twoPoint)
        assertEquals(NavigationCue.Unknown, cue)
    }

    @Test
    fun `on an arc but within a metre of the Man returns Unknown`() {
        // Arc polyline that runs straight through the origin (a short E-W line).
        // Sitting at the Man, bearing-from-origin is undefined, so the
        // ORIGIN_EPSILON_M guard fires and we emit Unknown instead of a fake 12:00.
        val throughOrigin =
            PlayaStreet(
                name = "OriginArc",
                kind = StreetKind.Arc,
                pointsM = listOf(PlayaPoint(-50.0, 0.0), PlayaPoint(50.0, 0.0)),
            )
        val withArc = city.copy(streetsM = listOf(throughOrigin))
        val cue = computeNavigationCue(PlayaPoint(0.0, 0.0), headingDeg = 90, city = withArc)
        assertEquals(NavigationCue.Unknown, cue)
    }

    @Test
    fun `on radial inside Esplanade heading outward falls back to Esplanade as last arc`() {
        // Sit on the 4:30 radial at 300 m — inside the innermost arc (660 m),
        // so no arc has actually been passed yet. The lastArcPassed fallback
        // keeps the cue stable by naming Esplanade.
        val ego = PlayaPoint(eastM = 0.0, northM = -300.0)
        val cue = computeNavigationCue(ego, headingDeg = 180, city = city)
        assertTrue("expected OnRadialOutbound, got $cue", cue is NavigationCue.OnRadialOutbound)
        val outbound = cue as NavigationCue.OnRadialOutbound
        assertEquals("4:30", outbound.radialName)
        assertEquals("Esplanade", outbound.lastArc)
    }

    @Test
    fun `on radial beyond Cherryh heading outward names the outermost arc`() {
        // Sit on the 4:30 radial at 1100 m — past Cherryh (1080 m), the
        // outermost arc. takeWhile walks all four arcs and lands on Cherryh.
        // (1100 is 20 m off the Cherryh ring, outside the 15 m snap, so we stay
        // pinned to the radial rather than the arc.)
        val ego = PlayaPoint(eastM = 0.0, northM = -1100.0)
        val cue = computeNavigationCue(ego, headingDeg = 180, city = city)
        assertTrue("expected OnRadialOutbound, got $cue", cue is NavigationCue.OnRadialOutbound)
        val outbound = cue as NavigationCue.OnRadialOutbound
        assertEquals("4:30", outbound.radialName)
        assertEquals("Cherryh", outbound.lastArc)
    }

    @Test
    fun `on the 9 colon 00 radial heading toward Man returns OnRadialInbound carrying that radial name`() {
        // 9:00 radial sits at 315° true. A fix 900 m out along it, then heading
        // 135° (back at the Man) → inbound. Confirms radialName plumbs through
        // for a radial other than 4:30, and nextArc is the innermost arc.
        val rad = Math.toRadians(315.0)
        val ego = PlayaPoint(eastM = 900.0 * Math.sin(rad), northM = 900.0 * Math.cos(rad))
        val cue = computeNavigationCue(ego, headingDeg = 135, city = city)
        assertTrue("expected OnRadialInbound, got $cue", cue is NavigationCue.OnRadialInbound)
        val inbound = cue as NavigationCue.OnRadialInbound
        assertEquals("9:00", inbound.radialName)
        assertEquals("Esplanade", inbound.nextArc)
    }

    @Test
    fun `between 4 colon 30 and 5 colon 00 from deep playa - heading toward city`() {
        // Stand 1500 m at clock 4:42 (true bearing = 4:42 in clock-deg + 45°).
        // 4:42 = 4*30 + 42*0.5 = 141° clock-deg → 186° true.
        val rad = Math.toRadians(186.0)
        val ego = PlayaPoint(eastM = 1500.0 * Math.sin(rad), northM = 1500.0 * Math.cos(rad))
        // Heading toward Man (bearing = 186° + 180° = 6° true).
        val cue = computeNavigationCue(ego, headingDeg = 6, city = city) as NavigationCue.TowardClock
        // After passing through ego's column heading toward Man, the forward exit
        // is on the opposite side of the fence — clock 12:00ish (north). Sanity-
        // check that we got *some* finite clock and a positive distance.
        assertTrue(cue.distanceM > 0)
        assertTrue(cue.clock.hours in 1..12)
    }
}
