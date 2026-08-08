package org.pureagave.zodiac.control.ui.playamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchSessionTest {
    private var zoom = 1.0

    private fun session() = PinchSession { zoom }

    private fun one(
        x: Float,
        y: Float,
    ) = listOf(TouchPoint(x, y))

    private fun two(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ) = listOf(TouchPoint(ax, ay), TouchPoint(bx, by))

    // --- drag-pan ---------------------------------------------------------

    @Test
    fun the_first_frame_of_a_drag_does_not_pan() {
        // Otherwise the map jumps by the distance from wherever the last
        // gesture happened to end.
        val s = session()

        val first = s.onPointers(one(100f, 100f))

        assertEquals(GestureUpdate.NONE, first)
    }

    @Test
    fun subsequent_drag_frames_pan_by_the_delta() {
        val s = session()
        s.onPointers(one(100f, 100f))

        val moved = s.onPointers(one(130f, 80f))

        assertEquals(30f, moved.panDx, 0f)
        assertEquals(-20f, moved.panDy, 0f)
        assertNull("a drag must not touch zoom", moved.zoom)
    }

    @Test
    fun lifting_and_re_touching_does_not_pan_across_the_gap() {
        val s = session()
        s.onPointers(one(100f, 100f))
        s.onPointers(one(120f, 100f))

        s.onPointers(emptyList()) // finger up
        val reTouch = s.onPointers(one(900f, 700f)) // finger down far away

        assertEquals(GestureUpdate.NONE, reTouch)
    }

    @Test
    fun dropping_from_a_pinch_to_one_finger_does_not_pan() {
        // The remaining finger is nowhere near where the last *single* finger
        // was; panning by that difference would fling the map across the playa.
        val s = session()
        s.onPointers(one(100f, 100f))
        s.onPointers(one(110f, 100f))
        s.onPointers(two(100f, 100f, 300f, 100f))

        val afterLift = s.onPointers(one(800f, 600f))

        assertEquals(GestureUpdate.NONE, afterLift)
    }

    // --- pinch-zoom -------------------------------------------------------

    @Test
    fun the_establishing_pinch_frame_only_records_the_grip() {
        val s = session()

        val grip = s.onPointers(two(0f, 0f, 100f, 0f))

        assertEquals(GestureUpdate.NONE, grip)
    }

    @Test
    fun spreading_the_fingers_zooms_in_proportionally() {
        zoom = 1.0
        val s = session()
        s.onPointers(two(0f, 0f, 100f, 0f))

        val spread = s.onPointers(two(0f, 0f, 200f, 0f))

        assertEquals(2.0, spread.zoom!!, 1e-9)
    }

    @Test
    fun a_second_pinch_starts_from_the_current_zoom_not_the_original() {
        // The reset-on-lift behaviour: without it the second grip snaps the
        // map back to the zoom the first grip started from.
        val s = session()
        s.onPointers(two(0f, 0f, 100f, 0f))
        s.onPointers(two(0f, 0f, 200f, 0f)) // 1.0 -> 2.0

        zoom = 2.0 // caller applied it
        s.onPointers(emptyList()) // both fingers up
        s.onPointers(two(0f, 0f, 100f, 0f)) // fresh grip
        val second = s.onPointers(two(0f, 0f, 200f, 0f))

        assertEquals("should continue from 2.0, not restart at 1.0", 4.0, second.zoom!!, 1e-9)
    }

    @Test
    fun zoom_is_clamped_to_the_map_limits() {
        zoom = 1.0
        val s = session()
        s.onPointers(two(0f, 0f, 100f, 0f))

        val huge = s.onPointers(two(0f, 0f, 100_000f, 0f))
        assertEquals(MAP_MAX_ZOOM, huge.zoom!!, 1e-9)

        val tiny = s.onPointers(two(0f, 0f, 1f, 0f))
        assertTrue("zoom $tiny stays above the floor", tiny.zoom!! >= MAP_MIN_ZOOM)
    }

    // --- rotation ---------------------------------------------------------

    @Test
    fun a_fresh_grip_does_not_rotate_by_the_angle_between_two_gestures() {
        val s = session()
        s.onPointers(two(0f, 0f, 100f, 0f)) // grip along +X
        s.onPointers(two(0f, 0f, 120f, 0f))

        s.onPointers(emptyList())
        // Second grip is at 90 degrees to the first; that must not twist.
        val newGrip = s.onPointers(two(0f, 0f, 0f, 100f))

        assertEquals(0f, newGrip.rotateStepDeg, 0f)
    }

    @Test
    fun twisting_an_established_grip_rotates() {
        val s = session()
        s.onPointers(two(0f, 0f, 100f, 0f))

        val twisted = s.onPointers(two(0f, 0f, 0f, 100f)) // +90 degrees

        assertTrue("expected a rotation step, got ${twisted.rotateStepDeg}", twisted.rotateStepDeg != 0f)
        assertNotNull("a twist still reports zoom for the same frame", twisted.zoom)
    }

    @Test
    fun a_sub_deadzone_twist_reports_no_rotation() {
        // Fingers are never perfectly still; without the deadzone the map
        // creeps whenever two fingers rest on the glass. The deadzone is
        // 0.05 deg, so 0.5 px across a 2000 px span (~0.014 deg) is inside it
        // and 2 px (~0.057 deg) is outside — checked both ways so this pins
        // the threshold rather than just asserting "small is zero".
        val s = session()
        s.onPointers(two(0f, 0f, 2000f, 0f))

        val jitter = s.onPointers(two(0f, 0f, 2000f, 0.5f))
        assertEquals(0f, jitter.rotateStepDeg, 0f)

        val deliberate = s.onPointers(two(0f, 0f, 2000f, 4f))
        assertTrue("a real twist must survive the deadzone", deliberate.rotateStepDeg != 0f)
    }

    @Test
    fun a_third_finger_is_ignored_rather_than_breaking_the_pinch() {
        val s = session()
        s.onPointers(two(0f, 0f, 100f, 0f))

        val withThird =
            s.onPointers(listOf(TouchPoint(0f, 0f), TouchPoint(200f, 0f), TouchPoint(50f, 400f)))

        assertEquals(2.0, withThird.zoom!!, 1e-9)
    }
}
