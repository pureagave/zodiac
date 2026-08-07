package org.pureagave.zodiac.control.ui.playamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * The map's two-finger gesture arithmetic, lifted out of the `pointerInput`
 * block so it can be exercised without a touchscreen. The seam case below is
 * the one that matters: it is invisible in casual testing because it only
 * fires when the finger pair happens to sweep through dead-horizontal-left.
 */
class MapGestureMathTest {
    private fun deg(d: Double) = d * PI / 180.0

    // -- rotation -------------------------------------------------------------

    @Test
    fun a_twist_rotates_the_view_the_opposite_way() {
        // Standard map-app feel: twisting the fingers clockwise turns what is
        // at the top of the viewport counter-clockwise.
        val step = mapRotationStepDeg(deg(0.0), deg(10.0))
        assertEquals(-10f, step, 1e-3f)
    }

    @Test
    fun the_reverse_twist_is_symmetric() {
        assertEquals(10f, mapRotationStepDeg(deg(0.0), deg(-10.0)), 1e-3f)
    }

    @Test
    fun crossing_the_atan2_seam_is_a_small_step_not_a_full_spin() {
        // atan2 returns (-π, π]. Rotating a few degrees across dead-horizontal-
        // left jumps the raw angle by nearly a full turn; without the shortest-
        // arc correction the map would spin 358° from 2° of finger movement.
        val step = mapRotationStepDeg(deg(179.0), deg(-179.0))
        assertEquals(-2f, step, 1e-3f)
        assertTrue("a hair of twist must not spin the map", abs(step) < 5f)
    }

    @Test
    fun crossing_the_seam_the_other_way_is_also_small() {
        val step = mapRotationStepDeg(deg(-179.0), deg(179.0))
        assertEquals(2f, step, 1e-3f)
    }

    /** What `atan2` actually hands us: every angle folded into `(-π, π]`. */
    private fun atan2Like(degrees: Double): Double {
        var d = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (d == -180.0) d = 180.0
        return deg(d)
    }

    @Test
    fun a_full_sweep_across_the_seam_accumulates_correctly() {
        // Walk the finger pair once around the circle in 5° steps, feeding the
        // wrapped angles the pointer loop really sees. Every step is small and
        // same-signed, and the total is exactly one turn — nothing is dropped
        // or doubled where the angle folds over.
        var prev = atan2Like(0.0)
        var total = 0f
        var maxStep = 0f
        for (i in 1..72) {
            val next = atan2Like(i * 5.0)
            val step = mapRotationStepDeg(prev, next)
            maxStep = maxOf(maxStep, abs(step))
            total += step
            prev = next
        }
        assertEquals(-360f, total, 0.1f)
        assertTrue("no step should exceed the 5° actually travelled", maxStep < 6f)
    }

    @Test
    fun jitter_inside_the_deadzone_is_ignored() {
        // Two fingers resting on the glass dither by fractions of a pixel; the
        // view must not creep.
        assertEquals(0f, mapRotationStepDeg(deg(0.0), deg(0.01)), 0f)
        assertEquals(0f, mapRotationStepDeg(deg(0.0), deg(-0.01)), 0f)
    }

    @Test
    fun deadzone_jitter_cannot_accumulate_into_visible_drift() {
        var prev = 0.0
        var total = 0f
        repeat(1_000) { i ->
            val next = deg(if (i % 2 == 0) 0.02 else 0.0)
            total += mapRotationStepDeg(prev, next)
            prev = next
        }
        assertEquals(0f, total, 0f)
    }

    @Test
    fun motion_just_past_the_deadzone_still_registers() {
        val step = mapRotationStepDeg(deg(0.0), deg(0.2))
        assertTrue("real movement must not be swallowed", abs(step) > 0f)
    }

    @Test
    fun no_movement_produces_no_rotation() {
        assertEquals(0f, mapRotationStepDeg(deg(42.0), deg(42.0)), 0f)
    }

    // -- zoom -----------------------------------------------------------------

    @Test
    fun spreading_the_fingers_zooms_in_proportionally() {
        assertEquals(2.0, mapPinchZoom(1.0, 100f, 200f), 1e-9)
    }

    @Test
    fun pinching_in_zooms_out_proportionally() {
        assertEquals(0.5, mapPinchZoom(1.0, 200f, 100f), 1e-9)
    }

    @Test
    fun zoom_is_relative_to_where_the_pinch_started() {
        // The second pinch continues from the level the first left, rather
        // than snapping back to 1.0.
        assertEquals(1.5, mapPinchZoom(3.0, 100f, 50f), 1e-9)
    }

    @Test
    fun holding_the_spread_holds_the_zoom() {
        assertEquals(2.5, mapPinchZoom(2.5, 137f, 137f), 1e-9)
    }

    @Test
    fun zoom_is_clamped_at_both_ends() {
        assertEquals(MAP_MAX_ZOOM, mapPinchZoom(4.0, 10f, 10_000f), 1e-9)
        assertEquals(MAP_MIN_ZOOM, mapPinchZoom(0.1, 10_000f, 1f), 1e-9)
    }

    @Test
    fun a_collapsed_pinch_does_not_divide_by_zero() {
        // Two fingers landing on the same pixel, or a session with no baseline
        // captured yet.
        val z = mapPinchZoom(1.0, 0f, 120f)
        assertEquals(1.0, z, 1e-9)
        assertTrue(z.isFinite())
    }

    @Test
    fun fingers_meeting_at_a_point_clamps_rather_than_going_to_zero() {
        assertEquals(MAP_MIN_ZOOM, mapPinchZoom(1.0, 200f, 0f), 1e-9)
    }

    @Test
    fun an_out_of_range_starting_zoom_is_brought_back_in_range() {
        assertEquals(MAP_MAX_ZOOM, mapPinchZoom(99.0, 0f, 0f), 1e-9)
    }
}
