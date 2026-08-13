package org.pureagave.zodiac.control.core.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskExitCodeTest {
    private val b = KioskTapZone.BOTTOM_END
    private val t = KioskTapZone.TOP_END

    private fun code() = KioskExitCode(code = listOf(b, t, b), windowMs = 1_000L)

    @Test
    fun the_exact_code_entered_in_time_completes_on_the_last_tap() {
        val c = code()
        assertFalse(c.tap(b, 0))
        assertFalse(c.tap(t, 500))
        assertTrue("the third correct tap completes the code", c.tap(b, 1_000))
    }

    @Test
    fun a_tap_later_than_the_window_restarts_instead_of_completing() {
        val c = code()
        assertFalse(c.tap(b, 0))
        assertFalse(c.tap(t, 500))
        // 1500 ms after the last tap (> 1000 window): the tap that would have
        // completed instead restarts. It equals code[0], so it re-seeds at 1.
        assertFalse("a stale tap must not complete the code", c.tap(b, 2_000))
        // ...and from that re-seed a fresh in-time continuation still completes.
        assertFalse(c.tap(t, 2_500))
        assertTrue(c.tap(b, 3_000))
    }

    @Test
    fun a_wrong_tap_mid_sequence_reseeds_on_the_first_element_so_a_fumble_still_completes() {
        val c = code() // [b, t, b]
        assertFalse(c.tap(b, 0)) // index 1
        // Wrong (expected t) but equals code[0]=b, so restart *at* 1, not 0.
        assertFalse(c.tap(b, 100))
        assertFalse(c.tap(t, 200)) // index 2
        assertTrue("the fumbled retry still completes", c.tap(b, 300))
    }

    @Test
    fun a_wrong_first_tap_makes_no_progress() {
        val c = code() // starts expecting b
        assertFalse(c.tap(t, 0)) // t != code[0]=b -> no progress
        assertFalse(c.tap(t, 100))
        assertFalse(c.tap(b, 200)) // only now is this code[0]
    }

    @Test
    fun completing_the_code_resets_it_so_the_next_entry_also_works() {
        val c = code()
        c.tap(b, 0)
        c.tap(t, 100)
        assertTrue(c.tap(b, 200))
        assertFalse(c.tap(b, 300))
        assertFalse(c.tap(t, 400))
        assertTrue("the recogniser rearms after a completion", c.tap(b, 500))
    }

    @Test
    fun the_default_code_is_the_documented_six_alternating_right_corners() {
        // Pins docs/KIOSK.md against the code: bottom, top, x3, in time.
        val c = KioskExitCode()
        var last = false
        listOf(b, t, b, t, b, t).forEachIndexed { i, z -> last = c.tap(z, i * 500L) }
        assertTrue("the documented six-tap default code completes", last)
    }
}
