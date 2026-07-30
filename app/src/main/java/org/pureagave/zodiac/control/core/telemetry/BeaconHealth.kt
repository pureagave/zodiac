package org.pureagave.zodiac.control.core.telemetry

/**
 * Health heartbeat from the Sensor Hub (`$ZBCN`) so the fleet knows the beacon
 * phone is alive and when it needs charging: [batteryPct] 0..100, the GNSS
 * [fixQuality] (0 = no fix) and [satellites] in view from the GGA passthrough,
 * and [uptimeSec] since the beacon started broadcasting.
 */
data class BeaconHealth(
    val batteryPct: Int,
    val fixQuality: Int,
    val satellites: Int,
    val uptimeSec: Long,
)
