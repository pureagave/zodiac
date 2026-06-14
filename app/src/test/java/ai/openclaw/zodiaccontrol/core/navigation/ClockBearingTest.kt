package ai.openclaw.zodiaccontrol.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `both 0 deg and 360 deg of clock rotation map to 12 colon 00`() {
        // The 45° axis is the clock origin: 45° -> clockDeg 0, 45+360 -> clockDeg 360.
        // Both rawHours==0 paths must resolve to the displayed hour 12, not 0.
        val atZero = bearingToClock(45.0)
        val atRev = bearingToClock(45.0 + 360.0)
        assertEquals(12, atZero.hours)
        assertEquals(0, atZero.minutes)
        assertEquals(12, atRev.hours)
        assertEquals(0, atRev.minutes)
    }

    @Test
    fun `minute rounding rounds a value just under a tick up to the next minute`() {
        // 1° = 2 min. 4:30 = 180°. Half a clock-minute = 0.25°, so 180.24° rounds
        // to 180° (4:30) but 180.26° (just over half a minute) rounds up to 4:31.
        assertEquals(30, bearingToClock(180.24).minutes)
        val justOver = bearingToClock(180.26)
        assertEquals(4, justOver.hours)
        assertEquals(31, justOver.minutes)
    }

    @Test
    fun `minute rounding can carry into the next hour at 59 and a half minutes`() {
        // 4:59.75 in clock-minutes rounds to 5:00. 5:00 = 195°; back off 0.125°
        // (a quarter clock-minute under the tick) so it rounds up across the hour.
        val c = bearingToClock(195.0 - 0.1)
        assertEquals(5, c.hours)
        assertEquals(0, c.minutes)
    }

    @Test
    fun `a non-zero axis bearing rotates the clock mapping`() {
        // With axis at 0°, the clock origin moves to true north: 0° -> 12:00,
        // and 90° (a quarter turn = 180 clock-min = 3h) -> 3:00.
        assertEquals("12:00", bearingToClock(0.0, axisBearingDeg = 0.0).format())
        val ninety = bearingToClock(90.0, axisBearingDeg = 0.0)
        assertEquals(3, ninety.hours)
        assertEquals(0, ninety.minutes)
    }

    @Test
    fun `clockToBearing round-trips a fractional minute clock under a custom axis`() {
        // Off-radial minute value through both directions with a non-default axis,
        // exercising the axis offset in the inverse path too.
        val axis = 12.0
        val deg = clockToBearing(ClockTime(4, 42), axisBearingDeg = axis)
        val back = bearingToClock(deg, axisBearingDeg = axis)
        assertEquals(4, back.hours)
        assertEquals(42, back.minutes)
    }

    @Test
    fun `ClockTime rejects hours outside 1 to 12`() {
        assertThrows(IllegalArgumentException::class.java) { ClockTime(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClockTime(13, 0) }
    }

    @Test
    fun `ClockTime rejects minutes outside 0 to 59`() {
        assertThrows(IllegalArgumentException::class.java) { ClockTime(4, -1) }
        assertThrows(IllegalArgumentException::class.java) { ClockTime(4, 60) }
    }
}
