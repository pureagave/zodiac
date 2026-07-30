package org.pureagave.zodiac.control.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenBrightnessTest {
    @Test
    fun full_dark_clamps_to_the_night_floor() {
        assertEquals(0.05f, luxToBrightness(0.0), 1e-4f)
        assertEquals(0.05f, luxToBrightness(5.0), 1e-4f) // at the night threshold
    }

    @Test
    fun daylight_clamps_to_full() {
        assertEquals(1.0f, luxToBrightness(2_000.0), 1e-4f)
        assertEquals(1.0f, luxToBrightness(100_000.0), 1e-4f)
    }

    @Test
    fun the_geometric_midpoint_is_roughly_half() {
        // sqrt(5 * 2000) = 100 lux sits at the log-scale midpoint → ~0.525.
        assertEquals(0.525f, luxToBrightness(100.0), 0.01f)
    }

    @Test
    fun brighter_is_never_dimmer() {
        assertEquals(true, luxToBrightness(500.0) > luxToBrightness(50.0))
    }

    @Test
    fun non_finite_falls_back_to_the_floor() {
        assertEquals(0.05f, luxToBrightness(Double.NaN), 1e-4f)
        assertEquals(0.05f, luxToBrightness(Double.NEGATIVE_INFINITY), 1e-4f)
    }
}
