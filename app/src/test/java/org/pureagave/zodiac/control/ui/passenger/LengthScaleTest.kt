package org.pureagave.zodiac.control.ui.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthScaleTest {
    @Test
    fun short_values_are_not_shrunk() {
        // "0", "47.3 KM", "2H 14M" — the common case must stay full size.
        assertEquals(1f, lengthScale("0"), 0f)
        assertEquals(1f, lengthScale("47.3 KM"), 0f)
    }

    @Test
    fun long_art_titles_step_down_instead_of_being_cut_off() {
        val scaled = lengthScale("ELECTRIC DANDELION AND FRIENDS")

        assertTrue("expected shrink, got $scaled", scaled < 1f)
        assertTrue("but not to nothing: $scaled", scaled >= FLOOR)
    }

    @Test
    fun the_scale_is_monotonic_so_longer_never_renders_bigger() {
        val samples = listOf("A", "ESPLANADE", "ELECTRIC DANDELION", "A".repeat(60))

        samples.zipWithNext().forEach { (shorter, longer) ->
            assertTrue(
                "'$shorter' (${lengthScale(shorter)}) must not be smaller than '$longer' (${lengthScale(longer)})",
                lengthScale(shorter) >= lengthScale(longer),
            )
        }
    }

    @Test
    fun an_absurd_string_still_has_a_floor() {
        assertTrue(lengthScale("X".repeat(ABSURD)) >= FLOOR)
    }

    private companion object {
        const val FLOOR = 0.4f
        const val ABSURD = 500
    }
}
