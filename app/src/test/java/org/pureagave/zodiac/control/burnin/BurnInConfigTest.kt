package org.pureagave.zodiac.control.burnin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInConfigTest {
    @Test
    fun default_config_is_already_in_range() {
        val d = BurnInConfig()
        assertEquals(d, d.coerced())
    }

    @Test
    fun out_of_range_values_are_clamped() {
        val c =
            BurnInConfig(
                pixelShiftAmplitudePx = 999,
                breatheAmplitude = 5f,
                dimBacklight = 9f,
                movementMeters = -4.0,
            ).coerced()
        assertEquals(BurnInConfig.MAX_SHIFT_AMPLITUDE_PX, c.pixelShiftAmplitudePx)
        assertEquals(BurnInConfig.MAX_BREATHE_AMPLITUDE, c.breatheAmplitude, 1e-6f)
        assertEquals(1f, c.dimBacklight, 1e-6f)
        assertEquals(0.0, c.movementMeters, 1e-9)
    }

    @Test
    fun negative_pixel_shift_clamps_to_zero() {
        assertEquals(0, BurnInConfig(pixelShiftAmplitudePx = -5).coerced().pixelShiftAmplitudePx)
    }

    @Test
    fun inverted_idle_timeouts_are_forced_strictly_increasing() {
        // A tampered/stale persisted config with dim > deep > sleep must never
        // leave the phase math with an inverted or zero-width band.
        val c = BurnInConfig(dimTimeoutSec = 1000, deepIdleTimeoutSec = 500, sleepTimeoutSec = 100).coerced()
        assertTrue(
            "dim < deepIdle < sleep after coercion",
            c.dimTimeoutSec < c.deepIdleTimeoutSec && c.deepIdleTimeoutSec < c.sleepTimeoutSec,
        )
    }

    @Test
    fun ceiling_values_do_not_throw_and_stay_ordered() {
        // All three timeouts pinned at the persisted/tuning-panel ceiling used to
        // make deepIdleTimeoutSec.coerceIn(dim + 1, MAX) an empty range and throw.
        val c =
            BurnInConfig(
                dimTimeoutSec = 86_400L,
                deepIdleTimeoutSec = 86_400L,
                sleepTimeoutSec = 86_400L,
            ).coerced()
        assertTrue("dim < deep", c.dimTimeoutSec < c.deepIdleTimeoutSec)
        assertTrue("deep < sleep", c.deepIdleTimeoutSec < c.sleepTimeoutSec)
        assertTrue("sleep <= ceiling", c.sleepTimeoutSec <= 86_400L)
    }

    @Test
    fun every_edge_combination_of_timeouts_is_total_and_ordered() {
        val edges = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 2L, 86_398L, 86_399L, 86_400L, 86_401L, Long.MAX_VALUE)
        for (dimIn in edges) {
            for (deepIn in edges) {
                for (sleepIn in edges) {
                    val c =
                        BurnInConfig(
                            dimTimeoutSec = dimIn,
                            deepIdleTimeoutSec = deepIn,
                            sleepTimeoutSec = sleepIn,
                        ).coerced()
                    val case = "dimIn=$dimIn deepIn=$deepIn sleepIn=$sleepIn -> $c"
                    assertTrue("dim >= 1 ($case)", c.dimTimeoutSec >= 1L)
                    assertTrue("dim < deep ($case)", c.dimTimeoutSec < c.deepIdleTimeoutSec)
                    assertTrue("deep < sleep ($case)", c.deepIdleTimeoutSec < c.sleepTimeoutSec)
                    assertTrue("sleep <= 86_400 ($case)", c.sleepTimeoutSec <= 86_400L)
                }
            }
        }
    }
}
