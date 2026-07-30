package org.pureagave.zodiac.control.core.telemetry

/**
 * Distance travelled from the Sensor Hub (`$ZODO`), in metres: [tripMeters] this
 * session and [totalMeters] lifetime (persisted on the beacon across restarts).
 * Accumulated from successive GNSS fixes with a jitter floor so a parked phone
 * doesn't clock miles from GPS wander.
 */
data class Odometer(
    val tripMeters: Double,
    val totalMeters: Double,
)
