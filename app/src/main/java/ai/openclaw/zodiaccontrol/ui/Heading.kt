package ai.openclaw.zodiaccontrol.ui

private const val HEADING_WRAP: Int = 360

/**
 * Normalize a heading delta into the canonical 0..359 range so debug chips
 * (-15 / -1 / +1 / +15) wrap around the compass instead of clamping at the
 * end-points. `(deg % 360 + 360) % 360` handles both negative and positive
 * input.
 */
fun wrapHeading(deg: Int): Int = ((deg % HEADING_WRAP) + HEADING_WRAP) % HEADING_WRAP
