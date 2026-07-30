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
}
