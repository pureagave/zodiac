package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelsTest {
    private fun buffer(
        amplitude: Int,
        n: Int = 256,
    ) = ShortArray(n) { i -> if (i % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    @Test
    fun silence_reads_zero_and_never_beats() {
        val f = AudioLevels().analyze(ShortArray(256))
        assertEquals(0.0, f.rms, 1e-9)
        assertEquals(0.0, f.peak, 1e-9)
        assertFalse(f.beat)
    }

    @Test
    fun empty_count_is_safe() {
        val f = AudioLevels().analyze(ShortArray(0))
        assertEquals(0.0, f.rms, 1e-9)
        assertFalse(f.beat)
    }

    @Test
    fun full_scale_reads_near_one() {
        val f = AudioLevels().analyze(buffer(Short.MAX_VALUE.toInt()))
        assertEquals(1.0, f.rms, 0.001)
        assertEquals(1.0, f.peak, 0.001)
    }

    @Test
    fun a_loud_frame_after_quiet_flags_a_beat() {
        val al = AudioLevels()
        repeat(5) { al.analyze(buffer(100)) } // establish a quiet baseline
        val loud = al.analyze(buffer(12_000)) // sudden jump
        assertTrue(loud.beat)
        assertTrue(loud.rms > 0.3)
    }

    @Test
    fun the_first_frame_never_beats() {
        // No baseline yet, so even a loud opening frame can't be a beat.
        assertFalse(AudioLevels().analyze(buffer(20_000)).beat)
    }

    @Test
    fun count_reads_only_the_first_n_samples_ignoring_the_tail() {
        // First 100 samples are full-scale; the rest are silent. With count=100 we
        // measure only the loud head (rms ~ 1.0), not the diluted whole buffer.
        val head =
            ShortArray(256) { i ->
                if (i < 100 && i % 2 == 0) {
                    Short.MAX_VALUE
                } else if (i < 100) {
                    Short.MIN_VALUE
                } else {
                    0
                }
            }
        val loud = AudioLevels().analyze(head, count = 100)
        assertEquals(1.0, loud.rms, 0.001)

        // The same buffer read in full averages in 156 silent samples -> much quieter.
        val whole = AudioLevels().analyze(head)
        assertTrue("full read (${whole.rms}) should be quieter than the head-only read", whole.rms < loud.rms)
    }

    @Test
    fun sustained_loud_stops_beating_once_the_average_catches_up() {
        val al = AudioLevels()
        repeat(5) { al.analyze(buffer(100)) } // quiet baseline
        // The first loud frame is a beat (jump above the average).
        assertTrue(al.analyze(buffer(12_000)).beat)
        // Held loud: the running average climbs toward the new energy, so after a
        // few frames the ratio drops below the sensitivity and beats stop.
        var stillBeating = true
        repeat(30) { stillBeating = al.analyze(buffer(12_000)).beat }
        assertFalse("sustained loudness should stop registering as beats", stillBeating)
    }
}
