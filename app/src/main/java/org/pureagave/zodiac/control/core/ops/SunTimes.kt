package org.pureagave.zodiac.control.core.ops

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun event times for a given date and position, computed locally (no API,
 * no library) via the NOAA solar-position equations — accurate to ~1 minute,
 * ample for the dashboard's sunrise/sunset readout. Times are returned as
 * wall-clock [LocalTime] in [zone]; [sunrise]/[sunset] are null on a polar
 * day/night (the sun never crosses the horizon), which never happens at BRC
 * but is handled for correctness.
 */
data class SunTimes(
    val sunrise: LocalTime?,
    val sunset: LocalTime?,
    val solarNoon: LocalTime?,
)

private const val ZENITH_OFFICIAL_DEG = 90.833
private const val J2000 = 2_451_545.0
private const val DAYS_PER_CENTURY = 36_525.0
private const val MINUTES_NOON_UTC = 720.0
private const val MIN_PER_DEG_LON = 4.0

fun sunTimes(
    date: LocalDate,
    latDeg: Double,
    lonDeg: Double,
    zone: ZoneId,
): SunTimes {
    val t = (julianDay0hUtc(date) - J2000) / DAYS_PER_CENTURY

    val meanLongDeg = norm360(280.46646 + t * (36000.76983 + t * 0.0003032))
    val meanAnomalyDeg = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    val m = Math.toRadians(meanAnomalyDeg)
    val center =
        sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
    val omega = Math.toRadians(125.04 - 1934.136 * t)
    val apparentLongRad = Math.toRadians(meanLongDeg + center - 0.00569 - 0.00478 * sin(omega))
    val obliquityDeg =
        23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 +
            0.00256 * cos(omega)
    val obliquityRad = Math.toRadians(obliquityDeg)
    val declRad = asin(sin(obliquityRad) * sin(apparentLongRad))

    val y = tan(obliquityRad / 2.0) * tan(obliquityRad / 2.0)
    val l0 = Math.toRadians(meanLongDeg)
    val eqTimeMin =
        MIN_PER_DEG_LON *
            Math.toDegrees(
                y * sin(2 * l0) - 2 * eccentricity * sin(m) +
                    4 * eccentricity * y * sin(m) * cos(2 * l0) -
                    0.5 * y * y * sin(4 * l0) - 1.25 * eccentricity * eccentricity * sin(2 * m),
            )

    val solarNoonUtcMin = MINUTES_NOON_UTC - MIN_PER_DEG_LON * lonDeg - eqTimeMin
    val solarNoon = utcMinutesToLocalTime(date, solarNoonUtcMin, zone)

    val latRad = Math.toRadians(latDeg)
    val cosHourAngle =
        cos(Math.toRadians(ZENITH_OFFICIAL_DEG)) / (cos(latRad) * cos(declRad)) -
            tan(latRad) * tan(declRad)
    if (abs(cosHourAngle) > 1.0) {
        return SunTimes(sunrise = null, sunset = null, solarNoon = solarNoon)
    }

    val hourAngleDeg = Math.toDegrees(acos(cosHourAngle))
    return SunTimes(
        sunrise = utcMinutesToLocalTime(date, solarNoonUtcMin - MIN_PER_DEG_LON * hourAngleDeg, zone),
        sunset = utcMinutesToLocalTime(date, solarNoonUtcMin + MIN_PER_DEG_LON * hourAngleDeg, zone),
        solarNoon = solarNoon,
    )
}

/**
 * Treats [utcMinutes] as minutes past 00:00 UTC on [date] (may be negative or
 * exceed a day — the event can fall on an adjacent UTC day) and returns its
 * wall-clock time in [zone]. Instant arithmetic carries any day rollover, so
 * the local time-of-day is always correct.
 */
private fun utcMinutesToLocalTime(
    date: LocalDate,
    utcMinutes: Double,
    zone: ZoneId,
): LocalTime {
    val startOfDayUtc: Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()
    return startOfDayUtc
        .plusSeconds((utcMinutes * 60.0).toLong())
        .atZone(zone)
        .toLocalTime()
}

/** Julian Day number at 00:00 UTC of [date] (Fliegel–Van Flandern). */
private fun julianDay0hUtc(date: LocalDate): Double {
    val a = (14 - date.monthValue) / 12
    val y = date.year + 4800 - a
    val m = date.monthValue + 12 * a - 3
    val jdn = date.dayOfMonth + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    return jdn - 0.5
}

private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
