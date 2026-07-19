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
}
