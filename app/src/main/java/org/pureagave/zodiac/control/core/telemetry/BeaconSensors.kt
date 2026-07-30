package org.pureagave.zodiac.control.core.telemetry

/**
 * The latest reading of each low-rate Sensor Hub channel, bundled into one value
 * so the cockpit consumes a single flow (and the ViewModel takes one constructor
 * param instead of five). Audio (`$ZAUD`) is deliberately NOT here — it updates
 * ~15 Hz and is a Jetson/DMX concern, not a tablet-UI one.
 *
 * Shock is event-driven, so it rides as a monotonic [shockCount] plus the peak of
 * the most recent event: a consumer flashes an alert when the count increases
 * (comparing counts, not the g value, so two equal-magnitude bumps still register
 * as two events).
 */
data class BeaconSensors(
    val ambientLight: AmbientLight? = null,
    val beaconHealth: BeaconHealth? = null,
    val odometer: Odometer? = null,
    val lastShockG: Double = 0.0,
    val shockCount: Long = 0,
)
