package org.pureagave.zodiac.control.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.vision.SurroundRing.Band
import org.pureagave.zodiac.control.core.vision.SurroundRing.HudStatus
import org.pureagave.zodiac.control.core.vision.SurroundRing.Sector
import kotlin.math.cos
import kotlin.math.sin

/**
 * The surround ring exists to show the driver what the forward view cannot.
 * The load-bearing behaviour is the sector logic — a "! BRAKE !" flash for
 * something astern is worse than no alert, because braking moves the vehicle
 * further into a rear contact's path and teaches the driver to distrust the
 * warning that does matter.
 *
 * Rev 2 (Phase 1.5) replaced the linear radius mapping with discrete bands,
 * added angular clustering with a circular mean, added cap hysteresis, and
 * speed-gated the brake advisory — several tests below were rewritten
 * deliberately to match those contract changes rather than deleted; see the
 * commit message for which ones and why.
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

    // -- coverage arcs ----------------------------------------------------------

    @Test
    fun bearings_inside_the_forward_thermal_arc_are_covered() {
        assertTrue(SurroundRing.isCovered(0f))
        assertTrue(SurroundRing.isCovered(63f))
        assertTrue(SurroundRing.isCovered(-63f))
    }

    @Test
    fun coverage_includes_its_own_edges() {
        assertTrue(SurroundRing.isCovered(64f))
        assertTrue(SurroundRing.isCovered(-64f))
    }

    @Test
    fun bearings_outside_the_forward_thermal_arc_are_uncovered() {
        // This is the whole point of 1.5f: an uncovered sector must not read
        // like a watched-and-clear one.
        assertFalse(SurroundRing.isCovered(65f))
        assertFalse(SurroundRing.isCovered(-65f))
        assertFalse(SurroundRing.isCovered(180f))
        assertFalse(SurroundRing.isCovered(-180f))
    }

    // -- proximity bands --------------------------------------------------------

    @Test
    fun band_boundaries_match_the_advertised_thresholds() {
        assertEquals(Band.FAR, SurroundRing.bandOf(0f))
        assertEquals(Band.FAR, SurroundRing.bandOf(0.32f))
        assertEquals(Band.MID, SurroundRing.bandOf(0.33f))
        assertEquals(Band.MID, SurroundRing.bandOf(0.65f))
        assertEquals(Band.NEAR, SurroundRing.bandOf(0.66f))
        assertEquals(Band.NEAR, SurroundRing.bandOf(1f))
    }

    @Test
    fun a_far_contact_sits_at_the_rim() {
        assertEquals(1f, SurroundRing.radiusFraction(0f), 1e-5f)
    }

    @Test
    fun a_near_contact_comes_in_toward_the_ego_mark() {
        assertTrue(SurroundRing.radiusFraction(0.9f) < SurroundRing.radiusFraction(0.2f))
    }

    @Test
    fun each_band_maps_to_its_advertised_radius() {
        assertEquals(SurroundRing.FAR_RADIUS_FRACTION, SurroundRing.radiusFraction(0.1f), 1e-5f)
        assertEquals(SurroundRing.MID_RADIUS_FRACTION, SurroundRing.radiusFraction(0.5f), 1e-5f)
        assertEquals(SurroundRing.NEAR_RADIUS_FRACTION, SurroundRing.radiusFraction(0.9f), 1e-5f)
    }

    @Test
    fun the_nearest_contact_does_not_vanish_under_the_ego_mark() {
        // Radius 0 would bury it exactly when it matters most. (Rev 2 moved
        // this floor from the old linear 0.18 to the NEAR band's 0.34, which
        // actually clears the reticle arms — see NEAR_RADIUS_FRACTION's doc.)
        assertEquals(SurroundRing.NEAR_RADIUS_FRACTION, SurroundRing.radiusFraction(1f), 1e-5f)
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
            assertTrue("$s produced $r", r in SurroundRing.NEAR_RADIUS_FRACTION..1f)
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

    // -- the brake decision -----------------------------------------------------

    private val aboveGate = 20f

    @Test
    fun a_closing_contact_ahead_advises_braking() {
        assertTrue(SurroundRing.brakeAdvised(listOf(t(az = 5f, collision = true)), aboveGate))
    }

    @Test
    fun a_closing_contact_astern_does_not_advise_braking() {
        // Braking puts the vehicle further into its path, and a false BRAKE
        // teaches the driver to ignore the real one.
        val rear = listOf(t(az = 175f, collision = true))
        assertFalse(SurroundRing.brakeAdvised(rear, aboveGate))
        assertTrue("but it is still worth showing", SurroundRing.rearAlert(rear))
    }

    @Test
    fun a_closing_contact_abeam_still_advises_braking() {
        // Slowing changes the geometry in the driver's favour anywhere forward
        // of the rear cone.
        assertTrue(SurroundRing.brakeAdvised(listOf(t(az = 95f, collision = true)), aboveGate))
        assertTrue(SurroundRing.brakeAdvised(listOf(t(az = -95f, collision = true)), aboveGate))
    }

    @Test
    fun a_rear_contact_that_is_not_closing_raises_nothing() {
        val rear = listOf(t(az = 170f, size = 0.9f, collision = false))
        assertFalse(SurroundRing.brakeAdvised(rear, aboveGate))
        assertFalse(SurroundRing.rearAlert(rear))
    }

    @Test
    fun a_forward_collision_alongside_a_rear_one_still_brakes() {
        val mixed = listOf(t(az = 178f, collision = true), t(az = 3f, collision = true))
        assertTrue(SurroundRing.brakeAdvised(mixed, aboveGate))
        assertTrue(SurroundRing.rearAlert(mixed))
    }

    @Test
    fun a_quiet_field_of_contacts_raises_nothing() {
        val quiet = (0..350 step 10).map { t(az = it.toFloat(), size = 0.4f) }
        assertFalse(SurroundRing.brakeAdvised(quiet, aboveGate))
        assertFalse(SurroundRing.rearAlert(quiet))
    }

    @Test
    fun no_contacts_at_all_is_silent() {
        assertFalse(SurroundRing.brakeAdvised(emptyList(), aboveGate))
        assertFalse(SurroundRing.rearAlert(emptyList()))
    }

    // -- speed gating (1.5d) -----------------------------------------------------

    @Test
    fun brake_is_suppressed_below_the_speed_gate_even_with_a_forward_collision() {
        // People deliberately walk up to art cars — a stopped or crawling
        // vehicle must not flash BRAKE at every one of them.
        val boarding = listOf(t(az = 5f, collision = true))
        assertFalse(SurroundRing.brakeAdvised(boarding, speedKph = 0f))
        assertFalse(SurroundRing.brakeAdvised(boarding, speedKph = 4.99f))
    }

    @Test
    fun brake_engages_at_and_above_the_speed_gate_threshold() {
        val closing = listOf(t(az = 5f, collision = true))
        assertTrue(SurroundRing.brakeAdvised(closing, speedKph = SurroundRing.BRAKE_MIN_KPH))
        assertTrue(SurroundRing.brakeAdvised(closing, speedKph = SurroundRing.BRAKE_MIN_KPH + 1f))
    }

    @Test
    fun a_collision_still_draws_red_even_when_braking_is_suppressed_by_speed() {
        // Only the imperative goes quiet below the gate — the mark itself,
        // and its colour, are not gated.
        val threats = listOf(t(az = 5f, size = 0.9f, collision = true, id = 1))
        assertFalse(SurroundRing.brakeAdvised(threats, speedKph = 0f))
        val blip = SurroundRing.blips(threats).single()
        assertTrue(blip.collision)
    }

    // -- what gets drawn: placement --------------------------------------------

    @Test
    fun every_contact_is_placed_when_there_is_room() {
        // Spaced well past CLUSTER_DEG so clustering doesn't fold them together.
        val threats = listOf(t(az = 0f), t(az = 90f), t(az = 180f))
        assertEquals(3, SurroundRing.blips(threats).size)
    }

    @Test
    fun rear_contacts_are_placed_rather_than_filtered_out() {
        // The whole point: these used to be dropped on the floor. Spaced more
        // than CLUSTER_DEG apart so each lands as its own mark, not a cluster.
        val rear = listOf(t(az = 150f), t(az = -150f), t(az = 180f))
        assertEquals(3, SurroundRing.blips(rear).size)
    }

    @Test
    fun a_blip_carries_the_position_its_own_helpers_would_give_it() {
        val threat = t(az = 137f, size = 0.3f)
        val blip = SurroundRing.blips(listOf(threat)).single()
        assertEquals(SurroundRing.screenAngleDeg(137f), blip.screenAngleDeg, 1e-5f)
        assertEquals(SurroundRing.radiusFraction(0.3f), blip.radiusFraction, 1e-5f)
        assertEquals(1, blip.memberCount)
        assertFalse(blip.collision)
    }

    @Test
    fun an_empty_or_zero_cap_ring_draws_nothing() {
        assertTrue(SurroundRing.blips(emptyList()).isEmpty())
        assertTrue(SurroundRing.blips(listOf(t(az = 0f)), max = 0).isEmpty())
    }

    @Test
    fun blips_come_back_in_draw_order_so_the_urgent_one_lands_on_top() {
        // Painted in sequence, the last one drawn wins the pixels.
        val threats = listOf(t(az = 0f, size = 0.2f, id = 1), t(az = 90f, size = 0.2f, collision = true, id = 2))
        assertEquals(2, SurroundRing.blips(threats).last().threat.id)
    }

    @Test
    fun draw_order_puts_the_nearest_above_the_farthest() {
        val threats = listOf(t(az = 0f, size = 0.1f, id = 1), t(az = 0f, size = 0.9f, id = 2))
        assertEquals(2, SurroundRing.blips(threats).last().threat.id)
    }

    @Test
    fun the_cap_prefers_nearer_contacts_among_equals() {
        val threats = listOf(t(az = 0f, size = 0.1f, id = 1), t(az = 20f, size = 0.9f, id = 2))
        val kept = SurroundRing.blips(threats, max = 1)
        assertEquals(2, kept.single().threat.id)
    }

    // -- angular clustering (1.5b) ------------------------------------------------

    @Test
    fun a_tight_crowd_in_the_same_band_merges_into_one_blip() {
        val crowd = listOf(t(az = 0f, size = 0.2f, id = 1), t(az = 5f, size = 0.2f, id = 2), t(az = 10f, size = 0.2f, id = 3))
        val kept = SurroundRing.blips(crowd)
        assertEquals(1, kept.size)
        assertEquals(3, kept.single().memberCount)
    }

    @Test
    fun clustering_requires_the_same_band_not_just_proximity() {
        // A FAR contact and a NEAR contact five degrees apart are not "the
        // same crowd" — different apparent proximity — so they stay distinct.
        val mixed = listOf(t(az = 0f, size = 0.1f, id = 1), t(az = 5f, size = 0.9f, id = 2))
        assertEquals(2, SurroundRing.blips(mixed).size)
    }

    @Test
    fun clustering_requires_proximity_not_just_the_same_band() {
        val apart = listOf(t(az = 0f, size = 0.2f, id = 1), t(az = 40f, size = 0.2f, id = 2))
        assertEquals(2, SurroundRing.blips(apart).size)
    }

    @Test
    fun a_crowd_straddling_dead_astern_merges_toward_astern_not_dead_ahead() {
        // The load-bearing clustering case: a naive arithmetic mean of 175
        // and −175 gives 0° — dead ahead — for a group that is actually right
        // behind the vehicle. The circular mean must give astern instead.
        val rearCrowd = listOf(t(az = 175f, size = 0.2f, id = 1), t(az = -175f, size = 0.2f, id = 2))
        val blip = SurroundRing.blips(rearCrowd).single()
        assertEquals(2, blip.memberCount)
        assertEquals("expected astern (screenAngleDeg 90), not dead ahead", 90f, blip.screenAngleDeg, 1e-3f)
    }

    @Test
    fun collision_contacts_never_merge_even_when_tight_and_same_band() {
        val tight =
            listOf(
                t(az = 0f, size = 0.2f, collision = true, id = 1),
                t(az = 3f, size = 0.2f, collision = true, id = 2),
            )
        val kept = SurroundRing.blips(tight)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.memberCount == 1 && it.collision })
    }

    @Test
    fun the_cap_still_binds_when_bystanders_are_too_spread_out_to_cluster() {
        // Cycle through all three bands so no two same-band contacts land
        // within CLUSTER_DEG of each other — this crowd genuinely can't be
        // reduced by clustering, so the MAX_BLIPS backstop has to do the work.
        val sizes = listOf(0.1f, 0.5f, 0.9f)
        val crowd = (0 until 40).map { i -> t(az = i * 9f, size = sizes[i % 3], id = i + 1) }
        assertEquals(SurroundRing.MAX_BLIPS, SurroundRing.blips(crowd).size)
    }

    @Test
    fun the_cap_keeps_the_collision_and_drops_the_farthest_bystanders() {
        val sizes = listOf(0.1f, 0.5f, 0.9f)
        val bystanders = (0 until 10).map { i -> t(az = i * 20f, size = sizes[i % 3], id = i + 1) }
        val collision = t(az = 179f, size = 0.05f, collision = true, id = 99)
        val kept = SurroundRing.blips(bystanders + collision, max = 3)
        assertEquals(3, kept.size)
        assertTrue("the closing contact must survive the cap", kept.any { it.threat.id == 99 })
    }

    // -- cap hysteresis (1.5c) ----------------------------------------------------

    @Test
    fun an_incumbent_keeps_its_slot_against_a_challenger_within_the_switch_margin() {
        // id=2 barely beats id=1 on raw urgency (0.05 gap, under SWITCH_MARGIN
        // 0.08). If id=1 was drawn last call, it should still hold the slot
        // instead of flickering out for size-estimate noise.
        val threats = listOf(t(az = 0f, size = 0.50f, id = 1), t(az = 90f, size = 0.55f, id = 2))
        val kept = SurroundRing.blips(threats, max = 1, previousKeptIds = setOf(1))
        assertEquals(1, kept.single().threat.id)
    }

    @Test
    fun a_challenger_that_clears_the_switch_margin_displaces_the_incumbent() {
        val threats = listOf(t(az = 0f, size = 0.50f, id = 1), t(az = 90f, size = 0.70f, id = 2))
        val kept = SurroundRing.blips(threats, max = 1, previousKeptIds = setOf(1))
        assertEquals(2, kept.single().threat.id)
    }

    @Test
    fun ad_hoc_contacts_with_id_zero_never_latch_as_incumbents() {
        val threats = listOf(t(az = 0f, size = 0.50f, id = 0), t(az = 90f, size = 0.55f, id = 2))
        // Even if a zero-id contact were named in the previous selection, it
        // must not be protected by it — id 0 has no stable identity.
        val kept = SurroundRing.blips(threats, max = 1, previousKeptIds = setOf(0))
        assertEquals(2, kept.single().threat.id)
    }

    @Test
    fun the_blip_tracker_holds_a_noisy_cap_boundary_steady_across_frames() {
        val tracker = SurroundRing.BlipTracker()
        val frame1 = listOf(t(az = 0f, size = 0.55f, id = 1))
        assertEquals(1, tracker.blips(frame1, max = 1).single().threat.id)

        // A challenger appears just inside the switch margin: without
        // hysteresis this would win on raw urgency (0.60 > 0.55).
        val frame2 = listOf(t(az = 0f, size = 0.55f, id = 1), t(az = 90f, size = 0.60f, id = 2))
        assertEquals(
            "a sub-margin jitter must not flip the drawn contact",
            1,
            tracker.blips(frame2, max = 1).single().threat.id,
        )

        // A challenger that clears the margin still wins.
        val frame3 = listOf(t(az = 0f, size = 0.55f, id = 1), t(az = 90f, size = 0.90f, id = 2))
        assertEquals(2, tracker.blips(frame3, max = 1).single().threat.id)
    }

    // -- HUD status line precedence (3b) -----------------------------------------

    @Test
    fun no_vision_overrides_everything_even_with_a_forward_collision() {
        // A stale "CLEAR" is worse than an honest "no reading at all" — an
        // absent feed must win regardless of what the (stale) contact list says.
        val forward = listOf(t(az = 5f, collision = true))
        assertEquals(HudStatus.NO_VISION, SurroundRing.hudStatus(forward, aboveGate, VisionFeed.ABSENT))
    }

    @Test
    fun brake_takes_precedence_over_check_rear_when_both_apply() {
        // Braking is the more urgent instruction and must not be masked by
        // the simultaneous rear check.
        val mixed = listOf(t(az = 178f, collision = true), t(az = 3f, collision = true))
        assertEquals(HudStatus.BRAKE, SurroundRing.hudStatus(mixed, aboveGate, VisionFeed.LIVE))
    }

    @Test
    fun check_rear_when_only_a_rear_collision_is_present() {
        val rearOnly = listOf(t(az = 178f, collision = true))
        assertEquals(HudStatus.CHECK_REAR, SurroundRing.hudStatus(rearOnly, aboveGate, VisionFeed.LIVE))
    }

    @Test
    fun demo_status_when_the_feed_is_demo_and_the_road_is_quiet() {
        val quiet = listOf(t(az = 0f, collision = false))
        assertEquals(HudStatus.DEMO, SurroundRing.hudStatus(quiet, aboveGate, VisionFeed.DEMO))
    }

    @Test
    fun a_demo_collision_still_shows_brake_not_the_demo_label() {
        // The demo is meant to exercise the real alert path — it should not
        // be silently downgraded to the DEMO label when it fires an alarm.
        val demoCollision = listOf(t(az = 5f, collision = true))
        assertEquals(HudStatus.BRAKE, SurroundRing.hudStatus(demoCollision, aboveGate, VisionFeed.DEMO))
    }

    @Test
    fun clear_when_the_feed_is_live_and_the_road_is_quiet() {
        val quiet = listOf(t(az = 0f, collision = false))
        assertEquals(HudStatus.CLEAR, SurroundRing.hudStatus(quiet, aboveGate, VisionFeed.LIVE))
    }

    @Test
    fun hud_status_falls_back_to_clear_when_brake_is_speed_gated_off() {
        // A forward collision at a dead stop doesn't advise braking (1.5d)
        // and isn't a rear contact either — the aggregate status must not
        // invent an alert the two underlying checks both declined to raise.
        val boarding = listOf(t(az = 5f, collision = true))
        assertEquals(HudStatus.CLEAR, SurroundRing.hudStatus(boarding, speedKph = 0f, visionFeed = VisionFeed.LIVE))
    }
}

/**
 * The complement of the covered arcs — the decision that stops an unwatched
 * sector rendering identically to a watched-and-clear one. Wrong here and the
 * ring quietly tells the driver the stern is clear when nothing is looking at
 * it.
 */
class SurroundRingCoverageGapTest {
    @Test
    fun a_forward_only_rig_leaves_one_gap_spanning_the_whole_stern() {
        val gaps = SurroundRing.uncoveredArcs(listOf(-64f..64f))
        assertEquals(1, gaps.size)
        assertEquals(64f, gaps.single().start, 1e-4f)
        assertEquals(296f, gaps.single().endInclusive, 1e-4f)
    }

    @Test
    fun the_gap_is_returned_unwrapped_so_a_renderer_can_sweep_it_directly() {
        // Splitting at the seam would need the caller to draw two arcs and get
        // the wrap right itself — exactly the bug this avoids.
        val gap = SurroundRing.uncoveredArcs(listOf(-64f..64f)).single()
        assertTrue("must run past +180 rather than wrapping", gap.endInclusive > 180f)
        assertEquals("and must sweep the 232 deg the rig cannot see", 232f, gap.endInclusive - gap.start, 1e-4f)
    }

    @Test
    fun the_gaps_and_the_covered_arcs_together_account_for_the_whole_circle() {
        for (covered in listOf(listOf(-64f..64f), listOf(-30f..30f, 100f..170f), listOf(-179f..179f))) {
            val spans =
                covered.sumOf { (it.endInclusive - it.start).toDouble() } +
                    SurroundRing.uncoveredArcs(covered).sumOf { (it.endInclusive - it.start).toDouble() }
            assertEquals("covered=$covered", 360.0, spans, 1e-3)
        }
    }

    @Test
    fun several_cameras_leave_a_gap_between_each_pair() {
        val gaps = SurroundRing.uncoveredArcs(listOf(-30f..30f, 100f..170f))
        assertEquals(2, gaps.size)
        assertEquals(30f, gaps[0].start, 1e-4f)
        assertEquals(100f, gaps[0].endInclusive, 1e-4f)
        assertEquals(170f, gaps[1].start, 1e-4f)
        assertEquals(330f, gaps[1].endInclusive, 1e-4f)
    }

    @Test
    fun arcs_given_out_of_order_still_produce_the_right_gaps() {
        assertEquals(
            SurroundRing.uncoveredArcs(listOf(-30f..30f, 100f..170f)),
            SurroundRing.uncoveredArcs(listOf(100f..170f, -30f..30f)),
        )
    }

    @Test
    fun a_rig_with_no_cameras_leaves_the_entire_circle_unwatched() {
        // Not a hypothetical: it is what COVERED_ARCS reduces to if someone
        // empties it while reconfiguring the rig.
        val gaps = SurroundRing.uncoveredArcs(emptyList())
        assertEquals(360f, gaps.single().endInclusive - gaps.single().start, 1e-4f)
    }

    @Test
    fun full_coverage_leaves_no_gap_at_all() {
        assertTrue(SurroundRing.uncoveredArcs(listOf(-180f..180f)).isEmpty())
    }

    @Test
    fun the_shipped_rig_leaves_the_stern_unwatched() {
        // Guards the constant itself: today's rig is one forward 160 deg
        // thermal, so most of the circle is genuinely blind.
        val blind = SurroundRing.uncoveredArcs().sumOf { (it.endInclusive - it.start).toDouble() }
        assertEquals(232.0, blind, 1e-3)
        assertFalse(SurroundRing.isCovered(180f))
    }
}
