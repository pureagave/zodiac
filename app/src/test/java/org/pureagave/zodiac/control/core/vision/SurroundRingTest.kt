package org.pureagave.zodiac.control.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.vision.SurroundRing.Sector
import kotlin.math.cos
import kotlin.math.sin

/**
 * The surround ring exists to show the driver what the forward view cannot.
 * The load-bearing behaviour is the sector logic — a "! BRAKE !" flash for
 * something astern is worse than no alert, because braking moves the vehicle
 * further into a rear contact's path and teaches the driver to distrust the
 * warning that does matter.
 */
class SurroundRingTest {
    private fun t(
        az: Float,
        size: Float = 0.5f,
        collision: Boolean = false,
        id: Int = 0,
    ) = DriverThreat(relAzDeg = az, size = size, collision = collision, id = id)

    /** Where a blip actually lands, in canvas pixels, on a unit ring at origin. */
    private fun xy(az: Float): Pair<Float, Float> {
        val a = Math.toRadians(SurroundRing.screenAngleDeg(az).toDouble())
        return cos(a).toFloat() to sin(a).toFloat()
    }

    // -- nose-up placement ----------------------------------------------------

    @Test
    fun straight_ahead_is_the_top_of_the_ring() {
        val (x, y) = xy(0f)
        assertEquals(0f, x, 1e-5f)
        assertEquals("canvas +Y is down, so the top is −1", -1f, y, 1e-5f)
    }

    @Test
    fun dead_astern_is_the_bottom_of_the_ring() {
        val (x, y) = xy(180f)
        assertEquals(0f, x, 1e-5f)
        assertEquals(1f, y, 1e-5f)
    }

    @Test
    fun starboard_is_on_the_right_where_the_drivers_head_turns() {
        val (x, y) = xy(90f)
        assertEquals(1f, x, 1e-5f)
        assertEquals(0f, y, 1e-5f)
    }

    @Test
    fun port_is_on_the_left() {
        val (x, y) = xy(-90f)
        assertEquals(-1f, x, 1e-5f)
        assertEquals(0f, y, 1e-5f)
    }

    @Test
    fun the_two_ways_to_name_dead_astern_land_in_the_same_place() {
        // The wire can report either sign at the seam; they must not draw at
        // opposite ends of the ring.
        val (xp, yp) = xy(180f)
        val (xn, yn) = xy(-180f)
        assertEquals(xp, xn, 1e-5f)
        assertEquals(yp, yn, 1e-5f)
    }

    @Test
    fun bearings_outside_the_principal_range_are_folded_in() {
        assertEquals(SurroundRing.screenAngleDeg(30f), SurroundRing.screenAngleDeg(390f), 1e-4f)
        assertEquals(SurroundRing.screenAngleDeg(-30f), SurroundRing.screenAngleDeg(330f), 1e-4f)
    }

    @Test
    fun a_corrupt_bearing_does_not_produce_a_corrupt_position() {
        assertTrue(SurroundRing.screenAngleDeg(Float.NaN).isFinite())
        assertTrue(SurroundRing.screenAngleDeg(Float.POSITIVE_INFINITY).isFinite())
    }

    // -- range ----------------------------------------------------------------

    @Test
    fun a_far_contact_sits_at_the_rim() {
        assertEquals(1f, SurroundRing.radiusFraction(0f), 1e-5f)
    }

    @Test
    fun a_near_contact_comes_in_toward_the_ego_mark() {
        assertTrue(SurroundRing.radiusFraction(0.9f) < SurroundRing.radiusFraction(0.2f))
    }

    @Test
    fun the_nearest_contact_does_not_vanish_under_the_ego_mark() {
        // Radius 0 would bury it exactly when it matters most.
        assertEquals(SurroundRing.MIN_RADIUS_FRACTION, SurroundRing.radiusFraction(1f), 1e-5f)
        assertTrue(SurroundRing.radiusFraction(1f) > 0f)
    }

    @Test
    fun radius_is_monotonic_in_proximity() {
        var prev = Float.MAX_VALUE
        for (i in 0..10) {
            val r = SurroundRing.radiusFraction(i / 10f)
            assertTrue("radius must never grow as a contact closes", r <= prev + 1e-6f)
            prev = r
        }
    }

    @Test
    fun out_of_range_and_corrupt_sizes_stay_on_the_ring() {
        for (s in listOf(-5f, 9f, Float.NaN, Float.NEGATIVE_INFINITY)) {
            val r = SurroundRing.radiusFraction(s)
            assertTrue("$s produced $r", r in SurroundRing.MIN_RADIUS_FRACTION..1f)
        }
    }

    // -- sectors --------------------------------------------------------------

    @Test
    fun sectors_split_the_circle_where_advertised() {
        assertEquals(Sector.FORWARD, SurroundRing.sectorOf(0f))
        assertEquals(Sector.FORWARD, SurroundRing.sectorOf(-60f))
        assertEquals(Sector.SIDE, SurroundRing.sectorOf(61f))
        assertEquals(Sector.SIDE, SurroundRing.sectorOf(-120f))
        assertEquals(Sector.REAR, SurroundRing.sectorOf(121f))
        assertEquals(Sector.REAR, SurroundRing.sectorOf(180f))
    }

    @Test
    fun sectors_are_symmetric_port_and_starboard() {
        for (a in 0..180) {
            assertEquals("asymmetry at $a°", SurroundRing.sectorOf(a.toFloat()), SurroundRing.sectorOf(-a.toFloat()))
        }
    }

    @Test
    fun every_bearing_on_the_circle_lands_in_some_sector() {
        for (a in -360..360) {
            assertNotEquals(null, SurroundRing.sectorOf(a.toFloat()))
        }
    }

    // -- the brake decision ---------------------------------------------------

    @Test
    fun a_closing_contact_ahead_advises_braking() {
        assertTrue(SurroundRing.brakeAdvised(listOf(t(az = 5f, collision = true))))
    }

    @Test
    fun a_closing_contact_astern_does_not_advise_braking() {
        // Braking puts the vehicle further into its path, and a false BRAKE
        // teaches the driver to ignore the real one.
        val rear = listOf(t(az = 175f, collision = true))
        assertFalse(SurroundRing.brakeAdvised(rear))
        assertTrue("but it is still worth showing", SurroundRing.rearAlert(rear))
    }

    @Test
    fun a_closing_contact_abeam_still_advises_braking() {
        // Slowing changes the geometry in the driver's favour anywhere forward
        // of the rear cone.
        assertTrue(SurroundRing.brakeAdvised(listOf(t(az = 95f, collision = true))))
        assertTrue(SurroundRing.brakeAdvised(listOf(t(az = -95f, collision = true))))
    }

    @Test
    fun a_rear_contact_that_is_not_closing_raises_nothing() {
        val rear = listOf(t(az = 170f, size = 0.9f, collision = false))
        assertFalse(SurroundRing.brakeAdvised(rear))
        assertFalse(SurroundRing.rearAlert(rear))
    }

    @Test
    fun a_forward_collision_alongside_a_rear_one_still_brakes() {
        val mixed = listOf(t(az = 178f, collision = true), t(az = 3f, collision = true))
        assertTrue(SurroundRing.brakeAdvised(mixed))
        assertTrue(SurroundRing.rearAlert(mixed))
    }

    @Test
    fun a_quiet_field_of_contacts_raises_nothing() {
        val quiet = (0..350 step 10).map { t(az = it.toFloat(), size = 0.4f) }
        assertFalse(SurroundRing.brakeAdvised(quiet))
        assertFalse(SurroundRing.rearAlert(quiet))
    }

    @Test
    fun no_contacts_at_all_is_silent() {
        assertFalse(SurroundRing.brakeAdvised(emptyList()))
        assertFalse(SurroundRing.rearAlert(emptyList()))
    }

    // -- what gets drawn ------------------------------------------------------

    @Test
    fun every_contact_is_placed_when_there_is_room() {
        val threats = listOf(t(az = 0f), t(az = 90f), t(az = 180f))
        assertEquals(3, SurroundRing.blips(threats).size)
    }

    @Test
    fun the_ring_is_capped_so_a_crowd_does_not_become_noise() {
        val crowd = (1..40).map { t(az = it * 9f, size = 0.5f, id = it) }
        assertEquals(SurroundRing.MAX_BLIPS, SurroundRing.blips(crowd).size)
    }

    @Test
    fun the_cap_keeps_the_collisions_and_drops_the_distant_bystanders() {
        val crowd =
            (1..30).map { t(az = it * 12f, size = 0.05f, id = it) } +
                t(az = 10f, size = 0.1f, collision = true, id = 99)
        val kept = SurroundRing.blips(crowd, max = 3)
        assertTrue("the closing contact must survive the cap", kept.any { it.threat.id == 99 })
    }

    @Test
    fun the_cap_prefers_nearer_contacts_among_equals() {
        val threats = listOf(t(az = 0f, size = 0.1f, id = 1), t(az = 20f, size = 0.9f, id = 2))
        val kept = SurroundRing.blips(threats, max = 1)
        assertEquals(2, kept.single().threat.id)
    }

    @Test
    fun blips_come_back_in_draw_order_so_the_urgent_one_lands_on_top() {
        // Painted in sequence, the last one drawn wins the pixels.
        val threats = listOf(t(az = 0f, size = 0.2f, id = 1), t(az = 5f, size = 0.2f, collision = true, id = 2))
        assertEquals(2, SurroundRing.blips(threats).last().threat.id)
    }

    @Test
    fun draw_order_puts_the_nearest_above_the_farthest() {
        val threats = listOf(t(az = 0f, size = 0.1f, id = 1), t(az = 0f, size = 0.9f, id = 2))
        assertEquals(2, SurroundRing.blips(threats).last().threat.id)
    }

    @Test
    fun a_blip_carries_the_position_its_own_helpers_would_give_it() {
        val threat = t(az = 137f, size = 0.3f)
        val blip = SurroundRing.blips(listOf(threat)).single()
        assertEquals(SurroundRing.screenAngleDeg(137f), blip.screenAngleDeg, 1e-5f)
        assertEquals(SurroundRing.radiusFraction(0.3f), blip.radiusFraction, 1e-5f)
    }

    @Test
    fun rear_contacts_are_placed_rather_than_filtered_out() {
        // The whole point: these used to be dropped on the floor.
        val rear = listOf(t(az = 150f), t(az = -170f), t(az = 180f))
        assertEquals(3, SurroundRing.blips(rear).size)
    }

    @Test
    fun an_empty_or_zero_cap_ring_draws_nothing() {
        assertTrue(SurroundRing.blips(emptyList()).isEmpty())
        assertTrue(SurroundRing.blips(listOf(t(az = 0f)), max = 0).isEmpty())
    }
}
