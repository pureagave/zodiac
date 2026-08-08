package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind

class ArtApproachTest {
    private val origin = PlayaPoint(0.0, 0.0)

    private fun art(
        name: String,
        east: Double,
        north: Double,
    ) = PlayaPoi(uid = name, name = name, kind = PoiKind.ART, point = PlayaPoint(east, north), subtitle = "")

    @Test
    fun a_piece_dead_ahead_is_the_one_we_are_approaching() {
        val ahead = art("ahead", east = 0.0, north = 200.0)

        val approach = approachingArt(listOf(ahead), origin, headingDeg = 0.0)

        assertEquals("ahead", approach?.poi?.name)
        assertEquals(200.0, approach!!.distanceM, 0.5)
        assertEquals(0.0, approach.relBearingDeg, 0.5)
    }

    @Test
    fun a_piece_behind_us_is_never_offered() {
        // The whole point: the nearest piece is often one we've already passed,
        // and a card describing what's behind you is worse than no card.
        val behind = art("behind", east = 0.0, north = -50.0)

        assertNull(approachingArt(listOf(behind), origin, headingDeg = 0.0))
    }

    @Test
    fun a_closer_piece_well_off_the_nose_loses_to_one_further_ahead() {
        // 100 m at 75 deg is already sliding past; 200 m dead ahead is the one
        // we're about to meet.
        val sliding = art("sliding", east = 96.6, north = 25.9) // ~75 deg, 100 m
        val meeting = art("meeting", east = 0.0, north = 200.0)

        val approach = approachingArt(listOf(sliding, meeting), origin, headingDeg = 0.0)

        assertEquals("meeting", approach?.poi?.name)
    }

    @Test
    fun heading_is_respected_not_just_position() {
        val east = art("east", east = 200.0, north = 0.0)

        assertNull("not ahead when facing north", approachingArt(listOf(east), origin, headingDeg = 0.0))
        assertEquals("east", approachingArt(listOf(east), origin, headingDeg = 90.0)?.poi?.name)
    }

    @Test
    fun heading_wraps_without_a_seam_at_north() {
        // A vehicle pointing 350 deg approaching a piece at 010 deg is 20 deg
        // off, not 340 — a naive subtraction puts this outside the arc.
        val justRight = art("just-right", east = 34.7, north = 196.9) // bearing ~10 deg

        val approach = approachingArt(listOf(justRight), origin, headingDeg = 350.0)

        assertEquals("just-right", approach?.poi?.name)
        assertEquals(20.0, approach!!.relBearingDeg, 1.0)
    }

    @Test
    fun anything_beyond_the_radius_is_ignored() {
        val far = art("far", east = 0.0, north = APPROACH_RADIUS_M + 10)

        assertNull(approachingArt(listOf(far), origin, headingDeg = 0.0))
    }

    @Test
    fun theme_camps_and_unplaced_pieces_are_not_candidates() {
        val camp = PlayaPoi("c", "camp", PoiKind.CAMP, PlayaPoint(0.0, 100.0), "")
        val unplaced = PlayaPoi("u", "unplaced", PoiKind.ART, null, "")

        assertNull(approachingArt(listOf(camp, unplaced), origin, headingDeg = 0.0))
    }

    @Test
    fun without_a_fix_nothing_is_approaching() {
        assertNull(approachingArt(listOf(art("a", 0.0, 100.0)), ego = null, headingDeg = 0.0))
    }

    @Test
    fun a_piece_well_off_to_the_side_reads_as_abeam() {
        // Used to change the card's wording from APPROACHING to PASSING.
        val side = art("side", east = 100.0, north = 20.0)

        val approach = approachingArt(listOf(side), origin, headingDeg = 0.0)

        assertTrue("expected abeam, bearing ${approach?.relBearingDeg}", approach!!.abeam)
    }

    @Test
    fun driving_past_hands_over_to_the_next_piece_in_turn() {
        // Two pieces along a straight run: as the first goes behind, the second
        // becomes the approach target without any explicit state.
        val first = art("first", east = 0.0, north = 100.0)
        val second = art("second", east = 0.0, north = 300.0)

        val atStart = approachingArt(listOf(first, second), PlayaPoint(0.0, 0.0), 0.0)
        val pastFirst = approachingArt(listOf(first, second), PlayaPoint(0.0, 150.0), 0.0)

        assertEquals("first", atStart?.poi?.name)
        assertEquals("second", pastFirst?.poi?.name)
    }

    @Test
    fun bearing_wrapping_is_symmetric_about_the_seam() {
        assertEquals(0.0, wrapSigned(360.0), 1e-9)
        assertEquals(-90.0, wrapSigned(270.0), 1e-9)
        assertEquals(180.0, wrapSigned(180.0), 1e-9)
        assertEquals(-179.0, wrapSigned(181.0), 1e-9)
        assertEquals(1.0, wrapSigned(-359.0), 1e-9)
    }
}
