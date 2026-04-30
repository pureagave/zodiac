package ai.openclaw.zodiaccontrol.core.navigation

import kotlin.math.roundToInt

/**
 * True-north bearing (degrees clockwise from geographic north) of the BRC
 * 12:00 axis — the line from the Man through the Temple. Verified against
 * the 2025 Innovate GIS street data: the 4:30 radial measures exactly 180°
 * true, the 3:00 radial 135°, the 9:00 radial 315°, so 12:00 = 45° true.
 *
 * BRC rotates the city plan a few degrees year-over-year; if/when we add a
 * 2026 dataset we'll add a 2026 constant here and parameterise the picker.
 */
const val BRC_AXIS_BEARING_DEG_2025 = 45.0

private const val DEG_PER_HOUR = 30.0
private const val MIN_PER_DEG = 2.0
private const val MIN_PER_REV = 720
private const val MIN_PER_HOUR = 60
private const val MAX_HOURS_DISPLAY = 12

/**
 * Burning Man clock-position notation. Hours are in 1..12 (where 12 means
 * "12:00"); minutes 0..59. Maps a continuous bearing onto the BRC clock face
 * — the published city only uses 2:00 through 10:00 for radials, but the
 * navigator interpolates to the minute so a fractional value like 4:42 is
 * meaningful between named radials.
 */
data class ClockTime(val hours: Int, val minutes: Int) {
    init {
        require(hours in 1..MAX_HOURS_DISPLAY) { "hours out of range: $hours" }
        require(minutes in 0..(MIN_PER_HOUR - 1)) { "minutes out of range: $minutes" }
    }

    /** Canonical "H:MM" formatting (single-digit hours, two-digit minutes). */
    fun format(): String = "$hours:${"%02d".format(minutes)}"
}

/**
 * Convert a true-north bearing (any double, mod 360 internally) to a BRC
 * clock time, rotating by the 12:00 axis offset. 1° = 2 clock-minutes.
 */
fun bearingToClock(
    trueBearingDeg: Double,
    axisBearingDeg: Double = BRC_AXIS_BEARING_DEG_2025,
): ClockTime {
    val clockDeg = normaliseDeg(trueBearingDeg - axisBearingDeg)
    val totalMin = (clockDeg * MIN_PER_DEG).roundToInt() % MIN_PER_REV
    val rawHours = totalMin / MIN_PER_HOUR
    val minutes = totalMin % MIN_PER_HOUR
    val hours = if (rawHours == 0) MAX_HOURS_DISPLAY else rawHours
    return ClockTime(hours = hours, minutes = minutes)
}

/**
 * Inverse of [bearingToClock]: emit the true-north bearing for a clock time,
 * useful for unit tests and any "snap to known radial" UI affordance.
 */
fun clockToBearing(
    clock: ClockTime,
    axisBearingDeg: Double = BRC_AXIS_BEARING_DEG_2025,
): Double {
    val displayHours = if (clock.hours == MAX_HOURS_DISPLAY) 0 else clock.hours
    val clockDeg = displayHours * DEG_PER_HOUR + clock.minutes / MIN_PER_DEG
    return normaliseDeg(clockDeg + axisBearingDeg)
}

private fun normaliseDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
