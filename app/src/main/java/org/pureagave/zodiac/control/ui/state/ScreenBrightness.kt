package org.pureagave.zodiac.control.ui.state

import kotlin.math.log10

/**
 * Map ambient lux (from the Sensor Hub's `$ZENV`) to a window-brightness fraction
 * (0..1) for the auto-dim. Perceptual log curve between a night floor and a day
 * ceiling: pitch dark clamps to [MIN_BRIGHTNESS] (never fully black — the driver
 * still needs to read the HUD), bright daylight clamps to full. Pure + testable;
 * `burnInScaffold` folds the result into `effectiveBacklight` and is the single
 * writer of `window.attributes.screenBrightness`. It used to be applied here by
 * `MainActivity` as well, and the two writers fought: whichever ran last won,
 * so a lux tick would undo the burn-in phase backlight within seconds.
 */
fun luxToBrightness(lux: Double): Float {
    if (!lux.isFinite() || lux <= NIGHT_LUX) return MIN_BRIGHTNESS
    if (lux >= DAY_LUX) return MAX_BRIGHTNESS
    val frac = (log10(lux) - log10(NIGHT_LUX)) / (log10(DAY_LUX) - log10(NIGHT_LUX))
    return (MIN_BRIGHTNESS + frac * (MAX_BRIGHTNESS - MIN_BRIGHTNESS)).toFloat()
}

private const val MIN_BRIGHTNESS = 0.05f // deep-night floor; readable but dim
private const val MAX_BRIGHTNESS = 1.0f
private const val NIGHT_LUX = 5.0 // at/below this = full dark → floor
private const val DAY_LUX = 2_000.0 // at/above this = daylight → full
