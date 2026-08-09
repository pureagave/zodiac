package org.pureagave.zodiac.control.burnin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private const val ONE_DAY_NANOS = 24L * 3600 * 1_000_000_000L
private const val FOURTEEN_DAYS_NANOS = 14L * ONE_DAY_NANOS
private const val ONE_120HZ_FRAME_NANOS = 8_333_333L

/**
 * C2 (`effectiveBacklight`) and C3 (the burn-in animation clock) covered as
 * plain JVM tests — no Compose runtime needed, which is the point: these pure
 * functions are the seam that makes the fix testable without a UI test.
 */
class BurnInScaffoldTest {
    // ---- C2: effectiveBacklight -------------------------------------------

    @Test
    fun sleep_backlight_is_not_raised_by_a_bright_lux_reading() {
        val config = BurnInConfig(sleepBacklight = 0.01f)
        val result = effectiveBacklight(BurnInPhase.SLEEP, config, ambientLux = 5000.0)
        assertEquals(0.01f, result, 0.0001f)
    }

    @Test
    fun deep_idle_is_not_raised_by_a_lux_tick() {
        val config = BurnInConfig(deepIdleBacklight = 0.15f)
        val result = effectiveBacklight(BurnInPhase.DEEP_IDLE, config, ambientLux = 5000.0)
        assertEquals(0.15f, result, 0.0001f)
    }

    @Test
    fun a_dark_room_still_wins_over_the_dim_ceiling() {
        val config = BurnInConfig(dimBacklight = 0.4f)
        val result = effectiveBacklight(BurnInPhase.DIM, config, ambientLux = 0.0)
        // luxToBrightness(0.0) floors at the night minimum, 0.05 — that must
        // still beat DIM's larger 0.4 ceiling.
        assertEquals(0.05f, result, 0.0001f)
    }

    @Test
    fun active_defers_to_the_lux_curve() {
        val config = BurnInConfig()
        val result = effectiveBacklight(BurnInPhase.ACTIVE, config, ambientLux = 2000.0)
        assertEquals(1.0f, result, 0.0001f)
    }

    @Test
    fun no_beacon_feed_still_gets_the_phase_backlight() {
        val config = BurnInConfig(sleepBacklight = 0.01f)
        val result = effectiveBacklight(BurnInPhase.SLEEP, config, ambientLux = null)
        // Must be the phase backlight, not BRIGHTNESS_OVERRIDE_NONE (-1f) —
        // that would hand the screen back to system brightness with no beacon.
        assertEquals(0.01f, result, 0.0001f)
    }

    // ---- C3: the animation clock -------------------------------------------

    @Test
    fun pixel_shift_still_moves_after_fourteen_days() {
        // Amplitude/period chosen so a single 120Hz-frame nudge crosses an
        // integer-pixel rounding boundary with a wide safety margin (~0.005 px,
        // many orders of magnitude above double-precision noise) — not a
        // property of the default config, just enough to make the assertion
        // observable through IntOffset's integer rounding.
        val config = BurnInConfig(pixelShiftAmplitudePx = 6, pixelShiftPeriodSec = 11)
        val t1 = FOURTEEN_DAYS_NANOS
        val t2 = t1 + ONE_120HZ_FRAME_NANOS

        val offset1 = pixelShift(t1, config)
        val offset2 = pixelShift(t2, config)

        // The shipped bug (a Float-seconds accumulator, or feeding these
        // helpers a Float seconds value) collapses t1 and t2 to the identical
        // Float at 14 days, so this is the assertion that catches it.
        assertNotEquals(offset1, offset2)
    }

    @Test
    fun breathe_still_oscillates_after_fourteen_days() {
        val config = BurnInConfig(breatheAmplitude = 0.2f, breathePeriodSec = 11)
        val t1 = FOURTEEN_DAYS_NANOS
        val t2 = t1 + ONE_120HZ_FRAME_NANOS

        val alpha1 = contentAlpha(BurnInPhase.ACTIVE, t1, config)
        val alpha2 = contentAlpha(BurnInPhase.ACTIVE, t2, config)

        assertTrue(
            "breathe alpha should differ between adjacent frames at day 14, got $alpha1 and $alpha2",
            abs(alpha1 - alpha2) > 1e-5f,
        )
    }

    @Test
    fun phase_fraction_stays_in_range_across_a_fortnight() {
        val hourNanos = 3600L * 1_000_000_000L
        var t = 0L
        while (t <= FOURTEEN_DAYS_NANOS) {
            for (periodSec in intArrayOf(45, 20)) {
                val frac = phaseFraction(t, periodSec)
                assertTrue(
                    "phaseFraction($t, $periodSec) = $frac out of [0, 1)",
                    frac >= 0.0 && frac < 1.0,
                )
            }
            t += hourNanos
        }
    }

    @Test
    fun shift_is_periodic() {
        val periodSec = 37
        val periodNanos = periodSec.toLong() * 1_000_000_000L
        val t = FOURTEEN_DAYS_NANOS + 12_345_678L

        val fracAtT = phaseFraction(t, periodSec)
        val fracOnePeriodLater = phaseFraction(t + periodNanos, periodSec)

        assertEquals(fracAtT, fracOnePeriodLater, 1e-9)
    }
}
