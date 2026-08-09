package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TickHealthTest {
    @Test
    fun reports_dead_after_five_seconds_of_silence() {
        // Requirement: 5 seconds (literal), not the implementation's TICK_DEAD_MS.
        val lastTick = 10_000L

        val atThreshold = tickHealthLine(nowMs = lastTick + 5_000L, lastTickAtMs = lastTick, tickErrors = 0L, lastError = null)
        assertNull("exactly at the 5s boundary must not scream yet", atThreshold)

        val pastThreshold = tickHealthLine(nowMs = lastTick + 5_001L, lastTickAtMs = lastTick, tickErrors = 0L, lastError = null)
        assertTrue(pastThreshold != null && pastThreshold.contains("DEAD"))
    }

    @Test
    fun reports_error_count_when_loop_alive_but_erroring() {
        // Loop is still ticking recently (well under 5s silence) but has logged errors.
        val line = tickHealthLine(nowMs = 1_100L, lastTickAtMs = 1_000L, tickErrors = 3L, lastError = "boom")
        assertTrue(line != null && line.contains("3") && line.contains("boom"))
    }

    @Test
    fun healthy_loop_with_no_errors_reports_nothing() {
        val line = tickHealthLine(nowMs = 1_100L, lastTickAtMs = 1_000L, tickErrors = 0L, lastError = null)
        assertNull(line)
    }

    @Test
    fun silent_before_first_tick() {
        // lastTickAtMs == 0 means the loop hasn't run yet at all — a fresh start
        // must not be reported as DEAD just because "now" is far from zero.
        val line = tickHealthLine(nowMs = 999_999L, lastTickAtMs = 0L, tickErrors = 0L, lastError = null)
        assertNull(line)
    }

    @Test
    fun exact_second_count_appears_in_the_dead_banner() {
        // Pins the seconds-since-death arithmetic to a literal, independent of
        // TICK_DEAD_MS: 12 real seconds of silence must read "12s" in the banner.
        val line = tickHealthLine(nowMs = 13_000L, lastTickAtMs = 1_000L, tickErrors = 0L, lastError = null)
        assertEquals(true, line != null && line.contains("12s"))
    }
}
