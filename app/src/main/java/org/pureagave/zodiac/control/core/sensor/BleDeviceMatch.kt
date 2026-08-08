package org.pureagave.zodiac.control.core.sensor

// Which paired Bluetooth device to treat as the GPS, and what to say when none
// of them qualifies. Pure string work, deliberately: the failure this guards
// against is a borrowed receiver whose name doesn't match our pattern, and
// reproducing that needs the one thing we don't have on the bench — an
// unfamiliar BLE GPS. Kept out of BleLocationSource so the matching *and its
// diagnostic* can be tested without an adapter.

/** First paired device whose name matches [pattern], or null. */
fun matchGpsDeviceName(
    pairedNames: List<String>,
    pattern: Regex,
): String? = pairedNames.firstOrNull { pattern.matches(it) }

/**
 * The message for "nothing matched". Names what *is* paired, because the old
 * text ("No paired Bluetooth GPS device matched") left the operator with no
 * next move — they couldn't tell whether the receiver was unpaired, named
 * something unexpected, or off. Listing the candidates makes the mismatch
 * visible, which is the difference between a dead end and a rename.
 *
 * Truncated to [MAX_LISTED] so one absurd pairing list can't push a log line
 * off the end of a segment.
 */
fun noGpsDeviceMessage(pairedNames: List<String>): String =
    when {
        pairedNames.isEmpty() -> "No Bluetooth devices are paired with this tablet"
        else -> {
            val shown = pairedNames.take(MAX_LISTED)
            val suffix = if (pairedNames.size > MAX_LISTED) ", +${pairedNames.size - MAX_LISTED} more" else ""
            "No paired device matched the GPS name pattern; paired: ${shown.joinToString(", ")}$suffix"
        }
    }

private const val MAX_LISTED = 6
