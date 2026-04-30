package ai.openclaw.zodiaccontrol.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockBearingTest {
    @Test
    fun `45 deg true (BRC axis) is 12 colon 00`() {
        val c = bearingToClock(45.0)
        assertEquals(12, c.hours)
        assertEquals(0, c.minutes)
        assertEquals("12:00", c.format())
    }

    @Test
    fun `180 deg true is 4 colon 30 (verified against 4 colon 30 radial in 2025 data)`() {
        val c = bearingToClock(180.0)
        assertEquals(4, c.hours)
        assertEquals(30, c.minutes)
        assertEquals("4:30", c.format())
    }

    @Test
    fun `135 deg true is 3 colon 00 (verified against 3 colon 00 radial)`() {
        val c = bearingToClock(135.0)
        assertEquals(3, c.hours)
        assertEquals(0, c.minutes)
    }

    @Test
    fun `225 deg true is 6 colon 00 (verified against 6 colon 00 radial)`() {
        val c = bearingToClock(225.0)
        assertEquals(6, c.hours)
        assertEquals(0, c.minutes)
    }

    @Test
    fun `315 deg true is 9 colon 00 (verified against 9 colon 00 radial)`() {
        val c = bearingToClock(315.0)
        assertEquals(9, c.hours)
        assertEquals(0, c.minutes)
    }

    @Test
    fun `interpolates to the minute - between 4 colon 30 and 5 colon 00`() {
        // 1° = 2 minutes. 4:30 = 180°, 5:00 = 195°. 4:42 = 12 minutes past 4:30 = 6° past = 186°.
        val c = bearingToClock(186.0)
        assertEquals(4, c.hours)
        assertEquals(42, c.minutes)
        assertEquals("4:42", c.format())
    }

    @Test
    fun `wraps negative bearing input via mod 360`() {
        // -45 mod 360 = 315 → 9:00
        val c = bearingToClock(-45.0)
        assertEquals(9, c.hours)
        assertEquals(0, c.minutes)
    }

    @Test
    fun `bearings just past midnight wrap to 12 colon something`() {
        // 47° true → clockDeg 2 → 4 minutes → 12:04
        val c = bearingToClock(47.0)
        assertEquals(12, c.hours)
        assertEquals(4, c.minutes)
    }

    @Test
    fun `clockToBearing is inverse of bearingToClock for canonical values`() {
        listOf(
            ClockTime(12, 0) to 45.0,
            ClockTime(4, 30) to 180.0,
            ClockTime(6, 0) to 225.0,
            ClockTime(9, 0) to 315.0,
            ClockTime(3, 0) to 135.0,
        ).forEach { (clock, expectedDeg) ->
            assertEquals(expectedDeg, clockToBearing(clock), 1e-6)
            val round = bearingToClock(expectedDeg)
            assertEquals(clock.hours, round.hours)
            assertEquals(clock.minutes, round.minutes)
        }
    }

    @Test
    fun `format always pads minutes to two digits`() {
        assertEquals("4:05", ClockTime(4, 5).format())
        assertEquals("12:00", ClockTime(12, 0).format())
        assertEquals("9:59", ClockTime(9, 59).format())
    }
}
