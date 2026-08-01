package org.pureagave.zodiac.beacon

/**
 * Pure, Android-free helpers pulled out of [TelemetryBroadcaster] so the JVM
 * unit tests can exercise them without a device: GGA fix-health parsing and the
 * subnet-directed broadcast address math. Behaviour-preserving extractions —
 * the broadcaster now delegates to these instead of inlining the logic.
 */
internal object BeaconNet {
    private const val BYTE_MASK = 0xFF
    private const val OCTET2_SHIFT = 8
    private const val OCTET3_SHIFT = 16

    // GGA field indices for the fix-quality + satellite-count the heartbeat reports.
    private const val GGA_FIX_QUALITY_FIELD = 6
    private const val GGA_SATS_FIELD = 7
    private const val SENTENCE_TYPE_LEN = 3

    /** The fix-quality + satellite count parsed out of a GGA sentence. */
    data class FixHealth(
        val fixQuality: Int?,
        val satellites: Int?,
    )

    /**
     * Pull fix-quality + satellite count out of a passing GGA sentence for the
     * heartbeat. Returns nulls for a non-GGA sentence or a truncated GGA that's
     * missing the relevant fields (the caller keeps the last good value).
     */
    fun parseFixHealth(nmea: String): FixHealth {
        val body = nmea.trim().trimEnd('\r', '\n').substringBefore('*')
        if (!body.startsWith("$")) return FixHealth(null, null)
        val fields = body.drop(1).split(',')
        if (fields.firstOrNull()?.takeLast(SENTENCE_TYPE_LEN) != "GGA") return FixHealth(null, null)
        return FixHealth(
            fixQuality = fields.getOrNull(GGA_FIX_QUALITY_FIELD)?.toIntOrNull(),
            satellites = fields.getOrNull(GGA_SATS_FIELD)?.toIntOrNull(),
        )
    }

    /**
     * The /24 subnet-directed broadcast host string for a little-endian WiFi
     * [ipAddress] (Android reports `dhcpInfo.ipAddress` little-endian, so the low
     * three octets are the address's first three): e.g. 192.168.0.234 →
     * "192.168.0.255". Returns null when [ipAddress] is 0 (no address yet) so the
     * caller can fall back to the limited broadcast.
     */
    fun subnetBroadcastHost(ipAddress: Int): String? {
        if (ipAddress == 0) return null
        val a = ipAddress and BYTE_MASK
        val b = (ipAddress shr OCTET2_SHIFT) and BYTE_MASK
        val c = (ipAddress shr OCTET3_SHIFT) and BYTE_MASK
        return "$a.$b.$c.255"
    }
}
