"""Vehicle data-bus constants — the exact mirror of the Kotlin ``FleetBus``
object and the ``:beacon`` module.

Fixed multicast groups in the administratively-scoped 239.0.0.0/8 range (never
routed off-link), TTL 1 so nothing leaves the vehicle. A source announces itself
simply by transmitting to its group; every device that joined receives it — no
hardcoded peer IPs, survives whatever the travel router's DHCP hands out.
"""

# Vehicle telemetry (NMEA GPS + compass) from the Sensor Hub.
TELEMETRY_GROUP = "239.7.7.10"
TELEMETRY_PORT = 10110

# Thermal detections from the edge box (this Jetson).
THREAT_GROUP = "239.7.7.20"
THREAT_PORT = 10120

# Link-local only — one hop, never past the vehicle's own switch/AP.
TTL = 1
