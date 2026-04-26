package ai.openclaw.zodiaccontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingTest {
    @Test
    fun zero_stays_zero() {
        assertEquals(0, wrapHeading(0))
    }

    @Test
    fun in_range_passes_through() {
        assertEquals(1, wrapHeading(1))
        assertEquals(180, wrapHeading(180))
        assertEquals(359, wrapHeading(359))
    }

    @Test
    fun three_sixty_wraps_to_zero() {
        assertEquals(0, wrapHeading(360))
    }

    @Test
    fun positive_overflow_wraps() {
        assertEquals(1, wrapHeading(361))
        assertEquals(15, wrapHeading(360 + 15))
        assertEquals(0, wrapHeading(720))
    }

    @Test
    fun negative_input_wraps_to_far_side() {
        assertEquals(359, wrapHeading(-1))
        assertEquals(345, wrapHeading(-15))
        assertEquals(180, wrapHeading(-180))
        assertEquals(0, wrapHeading(-360))
    }

    @Test
    fun large_negative_wraps_correctly() {
        assertEquals(359, wrapHeading(-721))
    }
}
