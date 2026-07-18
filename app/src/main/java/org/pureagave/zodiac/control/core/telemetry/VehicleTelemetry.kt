package org.pureagave.zodiac.control.core.telemetry

/**
 * Vehicle motion telemetry from the Sensor Hub beyond position + heading —
 * the IMU tilt and ground speed the fleet broadcasts in the proprietary
 * `$ZTLM` sentence. [pitchDeg] / [rollDeg] are the device tilt (from the
 * rotation vector); [speedKph] is ground speed.
 */
data class VehicleTelemetry(
    val pitchDeg: Double,
    val rollDeg: Double,
    val speedKph: Double,
)
