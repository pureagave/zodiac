package ai.openclaw.zodiaccontrol.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockEntryTest {
    @Test
    fun three_digits_2_to_9_parse_as_h_mm() {
        assertEquals(ClockTime(2, 15), parseClockEntry("215"))
        assertEquals(ClockTime(6, 0), parseClockEntry("600"))
        assertEquals(ClockTime(9, 45), parseClockEntry("945"))
    }

    @Test
    fun leading_one_parses_as_ten_oclock() {
        assertEquals(ClockTime(10, 0), parseClockEntry("1000"))
        assertEquals(ClockTime(10, 30), parseClockEntry("1030"))
    }

    @Test
    fun incomplete_entries_are_null() {
        assertNull(parseClockEntry(""))
        assertNull(parseClockEntry("2"))
        assertNull(parseClockEntry("21"))
        assertNull(parseClockEntry("10"))
        assertNull(parseClockEntry("100"))
    }

    @Test
    fun invalid_minutes_or_hours_are_null() {
        assertNull(parseClockEntry("260")) // 2:60
        assertNull(parseClockEntry("1160")) // leading 1 not followed by 0
        assertNull(parseClockEntry("1100")) // 11:00 — leading 1 must be 10:MM
    }

    @Test
    fun required_digits_switch_on_the_first_digit() {
        assertEquals(3, requiredClockDigits(""))
        assertEquals(3, requiredClockDigits("2"))
        assertEquals(4, requiredClockDigits("1"))
    }
}
