package org.pureagave.zodiac.control.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon

/**
 * The failure this guards is silent by construction: a receiver that loses sky
 * stops delivering fixes, and a source that only publishes on success holds its
 * last position forever — a frozen ego marker presented as where the vehicle is
 * now. `Searching` is the honest answer and lets a routed source fail over.
 */
class FixFreshnessTest {
    private fun fix(lat: Double = 40.78) = LocationSourceState.Active(GpsFix(location = LatLon(lon = -119.2, lat = lat)))

    private class Clock(var t: Long = 0L) : () -> Long {
        override fun invoke(): Long = t
    }

    @Test
    fun a_source_that_has_never_had_a_fix_is_not_stale() {
        // It is already Searching; reporting stale would only churn the state.
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        clock.t = 999_999
        assertFalse(f.isStale())
    }

    @Test
    fun a_fresh_fix_is_not_stale() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 500
        assertFalse(f.isStale())
    }

    @Test
    fun a_fix_older_than_the_window_is_stale() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 1_001
        assertTrue(f.isStale())
    }

    @Test
    fun the_boundary_is_exclusive() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 1_000
        assertFalse("exactly at the window is still fresh", f.isStale())
    }

    @Test
    fun a_new_fix_refreshes_the_clock() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 900
        f.onFix()
        clock.t = 1_500
        assertFalse("the second fix should have reset the window", f.isStale())
    }

    // -- what the source actually does with it -------------------------------

    @Test
    fun a_stale_active_state_is_demoted_to_searching() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 5_000
        assertEquals(LocationSourceState.Searching, f.demoteIfStale(fix()))
    }

    @Test
    fun a_fresh_active_state_is_returned_untouched() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        val current = fix()
        // Same instance back, so a caller can assign unconditionally without
        // churning a StateFlow.
        assertSame(current, f.demoteIfStale(current))
    }

    @Test
    fun an_error_state_survives_staleness() {
        // An Error carries a reason worth keeping on screen — "no device",
        // "permission denied". Replacing it with Searching would hide why.
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 9_999
        val err = LocationSourceState.Error("USB: no device")
        assertSame(err, f.demoteIfStale(err))
    }

    @Test
    fun already_honest_states_are_left_alone() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 9_999
        assertSame(LocationSourceState.Searching, f.demoteIfStale(LocationSourceState.Searching))
        assertSame(LocationSourceState.Disconnected, f.demoteIfStale(LocationSourceState.Disconnected))
    }

    @Test
    fun demotion_is_idempotent() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 5_000
        val once = f.demoteIfStale(fix())
        assertEquals(LocationSourceState.Searching, f.demoteIfStale(once))
    }

    @Test
    fun reset_forgets_the_last_fix() {
        // On a reconnect the pre-drop fix must not still count as recent.
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 9_999
        assertTrue(f.isStale())
        f.reset()
        assertFalse("after reset it is 'never had a fix', not 'stale'", f.isStale())
        assertSame(LocationSourceState.Searching, f.demoteIfStale(LocationSourceState.Searching))
    }

    @Test
    fun recovery_after_a_gap_republishes_normally() {
        val clock = Clock()
        val f = FixFreshness(staleMs = 1_000, nowMs = clock)
        f.onFix()
        clock.t = 5_000
        assertTrue(f.isStale())
        f.onFix() // sky came back
        assertFalse(f.isStale())
        val current = fix()
        assertSame(current, f.demoteIfStale(current))
    }

    @Test
    fun the_default_window_matches_the_network_source() {
        // One definition of "stale" across every source; if these drift, the
        // fleet's sources disagree about when a fix is dead.
        assertEquals(5_000L, FixFreshness.STALE_MS)
    }
}
