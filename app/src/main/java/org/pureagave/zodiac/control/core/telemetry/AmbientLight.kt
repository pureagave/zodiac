package org.pureagave.zodiac.control.core.telemetry

/**
 * Ambient light level from the Sensor Hub (`$ZENV`), in lux. Drives the fleet's
 * automatic day/night HUD switch — low lux at dusk flips the cockpit to the
 * DRIVER night display.
 */
data class AmbientLight(
    val lux: Double,
)
