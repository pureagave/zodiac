package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShockDetectorTest {
    @Test
    fun below_threshold_never_fires() {
        val det = ShockDetector(thresholdG = 1.5)
        assertNull(det.sample(magnitudeMps2 = 9.8, nowMs = 0L)) // ~1.0 g, normal jostle
    }

    @Test
    fun a_spike_above_threshold_fires_with_its_peak_g() {
        val det = ShockDetector(thresholdG = 1.5)
        val g = det.sample(magnitudeMps2 = 20.0, nowMs = 0L) // 20 / 9.80665 ≈ 2.04 g
        assertNotNull(g)
        assertEquals(2.039, g!!, 0.01)
    }

    @Test
    fun refractory_window_suppresses_a_burst_then_re_arms() {
        val det = ShockDetector(thresholdG = 1.5, refractoryMs = 500L)
        assertNotNull(det.sample(20.0, 0L)) // fires
        assertNull(det.sample(20.0, 200L)) // same bump, still inside refractory
        assertNull(det.sample(20.0, 499L)) // still suppressed
        assertNotNull(det.sample(20.0, 600L)) // re-armed after the window
    }
}
