package org.pureagave.zodiac.control.data.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeThreatSourceTest {
    @Test
    fun demo_emits_three_contacts() {
        assertEquals(3, FakeThreatSource.demo(0).size)
    }

    @Test
    fun contacts_move_over_time() {
        val crosser0 = FakeThreatSource.demo(0).first { it.id == 1 }.relAzDeg
        val crosser30 = FakeThreatSource.demo(30).first { it.id == 1 }.relAzDeg
        assertNotEquals(crosser0, crosser30)
    }

    @Test
    fun approacher_eventually_trips_the_collision_flag() {
        val hitsCollision = (0..300).any { tick -> FakeThreatSource.demo(tick).any { it.id == 2 && it.collision } }
        assertTrue("the approacher should reach a collision at some tick", hitsCollision)
    }

    @Test
    fun approacher_size_ramps_and_resets() {
        val sizes = (0..300).map { tick -> FakeThreatSource.demo(tick).first { it.id == 2 }.size }
        assertTrue("size should reach near-contact", sizes.max() > 0.8f)
        assertTrue("size should reset to far", sizes.min() < 0.3f)
    }
}
