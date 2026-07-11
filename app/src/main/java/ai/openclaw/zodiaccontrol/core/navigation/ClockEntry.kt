package ai.openclaw.zodiaccontrol.core.navigation

/**
 * Digits the address keypad still expects for [entry], given its first digit:
 * a leading 2–9 is a 3-digit `H:MM` clock (e.g. `215` = 2:15); a leading 1 is a
 * 4-digit `10:MM` (e.g. `1030` = 10:30). Drives the keypad's auto-advance.
 */
fun requiredClockDigits(entry: String): Int =
    when (entry.firstOrNull()) {
        '1' -> TEN_OCLOCK_DIGITS
        else -> SINGLE_HOUR_DIGITS
    }

/**
 * Parse a keypad [entry] into a BRC clock time, or null if it isn't a complete,
 * valid city address in 2:00–10:00. `215` → 2:15; `1030` → 10:30. A leading 1
 * must be `10MM` (only 10:00 uses the tens digit); leading 2–9 is `HMM`.
 */
fun parseClockEntry(entry: String): ClockTime? {
    val first = entry.firstOrNull() ?: return null
    val parts: Pair<Int, String>? =
        if (first == '1') {
            if (entry.length == TEN_OCLOCK_DIGITS && entry[1] == '0') 10 to entry.substring(2) else null
        } else {
            if (entry.length == SINGLE_HOUR_DIGITS) (first - '0') to entry.substring(1) else null
        }
    val hour = parts?.first
    val minute = parts?.second?.toIntOrNull()
    return if (hour != null && minute != null && isCityClock(hour, minute)) {
        ClockTime(hours = hour, minutes = minute)
    } else {
        null
    }
}

private fun isCityClock(
    hour: Int,
    minute: Int,
): Boolean = hour in CITY_MIN_HOUR..CITY_MAX_HOUR && minute in 0..MAX_MINUTE

private const val SINGLE_HOUR_DIGITS = 3
private const val TEN_OCLOCK_DIGITS = 4
private const val CITY_MIN_HOUR = 2
private const val CITY_MAX_HOUR = 10
private const val MAX_MINUTE = 59
