package org.pureagave.zodiac.control.core.net

/**
 * The vehicle's local data bus. One-to-many realtime streams ride **fixed
 * multicast groups** — the group address is a constant baked into the app, so
 * it's independent of whatever the travel router's DHCP hands out (no hardcoded
 * peer IPs, survives dynamic leases). A source announces itself simply by
 * transmitting to its group; every device that joined the group receives it.
 * All link-local (TTL 1) → nothing leaves the vehicle and it works with the
 * internet down.
 *
 * Addresses are in the administratively-scoped 239.0.0.0/8 range (never routed
 * off-link). The beacon (`:beacon` module) mirrors these constants.
 */
object FleetBus {
    /** Vehicle telemetry (NMEA GPS + compass) from the Sensor Hub. */
    const val TELEMETRY_GROUP = "239.7.7.10"
    const val TELEMETRY_PORT = 10110

    /** Thermal detections from the edge box (Jetson). */
    const val THREAT_GROUP = "239.7.7.20"
    const val THREAT_PORT = 10120

    /** Link-local only — one hop, never past the vehicle's own switch/AP. */
    const val TTL = 1
}
