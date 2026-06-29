package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SunTimesTest {
    private val brcLat = GoldenSpike.Y2025.lat
    private val brcLon = GoldenSpike.Y2025.lon
    private val pacific = ZoneId.of("America/Los_Angeles")

    @Test
    fun brc_late_august_matches_noaa_reference() {
        // NOAA reference for BRC on 2025-08-30 (PDT): 06:21:40 / 12:57:32 / 19:33:23.
        val s = sunTimes(LocalDate.of(2025, 8, 30), brcLat, brcLon, pacific)
        assertCloseTo(LocalTime.of(6, 21, 40), s.sunrise)
        assertCloseTo(LocalTime.of(12, 57, 32), s.solarNoon)
        assertCloseTo(LocalTime.of(19, 33, 23), s.sunset)
    }

    @Test
    fun brc_early_september_matches_noaa_reference() {
        // NOAA reference for BRC on 2026-09-05 (PDT): 06:27:19 / 19:24:03.
        val s = sunTimes(LocalDate.of(2026, 9, 5), brcLat, brcLon, pacific)
        assertCloseTo(LocalTime.of(6, 27, 19), s.sunrise)
        assertCloseTo(LocalTime.of(19, 24, 3), s.sunset)
    }

    @Test
    fun sunrise_is_before_sunset() {
        val s = sunTimes(LocalDate.of(2026, 8, 30), brcLat, brcLon, pacific)
        assertNotNull(s.sunrise)
        assertNotNull(s.sunset)
        assertEquals(true, s.sunrise!!.isBefore(s.sunset!!))
    }

    @Test
    fun polar_day_has_no_sunrise_or_sunset() {
        // Above the Arctic Circle at the solstice the sun never sets.
        val s = sunTimes(LocalDate.of(2025, 6, 21), 80.0, 0.0, ZoneId.of("UTC"))
        assertNull(s.sunrise)
        assertNull(s.sunset)
        assertNotNull(s.solarNoon)
    }

    private fun assertCloseTo(
        expected: LocalTime,
        actual: LocalTime?,
    ) {
        assertNotNull(actual)
        val deltaSec = kotlin.math.abs(expected.toSecondOfDay() - actual!!.toSecondOfDay())
        assertEquals("expected ~$expected, got $actual (Δ${deltaSec}s)", true, deltaSec <= TOLERANCE_SEC)
    }

    private companion object {
        const val TOLERANCE_SEC = 120
    }
}
