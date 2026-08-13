package org.pureagave.zodiac.beacon

import java.net.InetAddress

/**
 * Pure, Android-free helpers pulled out of [TelemetryBroadcaster] so the JVM
 * unit tests can exercise them without a device: GGA fix-health parsing, GGA
 * staleness, and the subnet-directed broadcast address math. Behaviour-preserving
 * extractions — the broadcaster now delegates to these instead of inlining the
 * logic.
 */
internal object BeaconNet {
    private const val BYTE_MASK = 0xFF
    private const val OCTET2_SHIFT = 8
    private const val OCTET3_SHIFT = 16
    private const val OCTET4_SHIFT = 24

    // Fallback netmask when the device reports none (0, the common DhcpInfo
    // failure) or something non-contiguous (garbage). Android reports
    // DhcpInfo.netmask little-endian like ipAddress, so 255.255.255.0's
    // little-endian int is 0x00FFFFFF, not the familiar 0xFFFFFF00.
    private const val DEFAULT_MASK_LE = 0x00FF_FFFF

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
     * The fix-quality/satellite values the heartbeat should actually report,
     * plus how stale they are (`null` when not stale).
     */
    data class ReportedFixHealth(
        val fixQuality: Int,
        val satellites: Int,
        val staleMs: Long?,
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
     * What `$ZBCN`/the status readout should actually report for fix health,
     * given how long it's been since the last GGA. Without a freshness clock, a
     * GNSS chip that goes silent (antenna unplugged, chip wedged, no fix ever
     * regained) leaves the last-seen "healthy fix, N sats" on the wire forever —
     * this is what stops that.
     *
     * [lastGgaAtMs] `<= 0` means no GGA has ever arrived (fresh boot): [fixQuality]
     * / [satellites] are already `0`/`0` in that case and are passed through
     * un-annotated, since nothing has gone stale — there was just never anything
     * there. The staleness boundary is strictly greater-than, mirroring
     * [tickHealthLine]'s dead-loop check.
     */
    fun reportedFixHealth(
        nowMs: Long,
        lastGgaAtMs: Long,
        fixQuality: Int,
        satellites: Int,
        staleAfterMs: Long,
    ): ReportedFixHealth =
        when {
            lastGgaAtMs <= 0 -> ReportedFixHealth(fixQuality, satellites, staleMs = null)
            nowMs - lastGgaAtMs > staleAfterMs -> ReportedFixHealth(0, 0, staleMs = nowMs - lastGgaAtMs)
            else -> ReportedFixHealth(fixQuality, satellites, staleMs = null)
        }

    /**
     * True when [leMask] (Android's little-endian `DhcpInfo.netmask` form) is a
     * real, usable netmask: a contiguous run of network-bits then host-bits, with
     * at least one host bit. `0` — the common DhcpInfo failure mode — is
     * rejected outright rather than falling into the general math, where it
     * would otherwise read as a (technically contiguous) `/0`.
     */
    private fun isUsableMask(leMask: Int): Boolean {
        if (leMask == 0) return false
        // reverseBytes: Android's little-endian octet order -> the normal
        // big-endian integer a netmask's bits are conventionally reasoned about
        // in (255.255.255.0 == 0xFFFFFF00). Inverting that yields the host
        // suffix; a mask is only valid if that suffix is all 1s, i.e. of the
        // form 2^n - 1.
        val host = Integer.reverseBytes(leMask).inv()
        return host and (host + 1) == 0
    }

    /**
     * The subnet-directed broadcast host string for a little-endian WiFi
     * [ipAddress] and [netmask] (Android reports `dhcpInfo.ipAddress` /
     * `dhcpInfo.netmask` little-endian, so the low three octets are the
     * address's first three): e.g. 192.168.0.234 / 255.255.255.0 →
     * "192.168.0.255". [netmask] is used only when [isUsableMask] accepts it;
     * a `0` or non-contiguous netmask (the device reporting none, or garbage)
     * falls back to the historical /24 assumption, which is what this network
     * has always been. Returns null when [ipAddress] is 0 (no address yet) so
     * the caller can fall back to the limited broadcast.
     */
    fun directedBroadcastHost(
        ipAddress: Int,
        netmask: Int,
    ): String? {
        if (ipAddress == 0) return null
        val mask = if (isUsableMask(netmask)) netmask else DEFAULT_MASK_LE
        val bcast = (ipAddress and mask) or mask.inv()
        val a = bcast and BYTE_MASK
        val b = (bcast shr OCTET2_SHIFT) and BYTE_MASK
        val c = (bcast shr OCTET3_SHIFT) and BYTE_MASK
        val d = (bcast shr OCTET4_SHIFT) and BYTE_MASK
        return "$a.$b.$c.$d"
    }

    /**
     * The two addresses a beacon frame is sent to: the fixed fleet multicast
     * group, and the subnet-directed broadcast derived from the current DHCP
     * lease (consumer APs deliver that reliably; the limited 255.255.255.255 they
     * often do not).
     *
     * Takes the raw DHCP address + netmask so the decision is testable without a
     * device. [ipAddress] of 0 means "no lease yet" — which is the normal state
     * for the first seconds after a vehicle powers up, since the phone boots
     * faster than the travel router. That is why the caller re-resolves
     * periodically instead of trusting the value it got at start-up.
     */
    fun broadcastTargets(
        group: String,
        ipAddress: Int,
        netmask: Int,
        limitedBroadcast: String,
    ): List<InetAddress> {
        val host = directedBroadcastHost(ipAddress, netmask) ?: limitedBroadcast
        val fallback =
            runCatching { InetAddress.getByName(host) }
                .getOrElse { InetAddress.getByName(limitedBroadcast) }
        return listOf(InetAddress.getByName(group), fallback)
    }
}
