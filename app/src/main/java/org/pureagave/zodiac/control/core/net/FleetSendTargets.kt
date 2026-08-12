package org.pureagave.zodiac.control.core.net

import java.net.InetAddress

/**
 * Send-target math for [org.pureagave.zodiac.control.data.nav.NavShareSender] —
 * the app's first device-to-device transmit path. A pure, JVM-testable mirror
 * of `beacon/.../BeaconNet.kt`'s `subnetBroadcastHost` + `broadcastTargets`
 * (decision 10: the two modules share no code, so this is a parallel
 * implementation, not an import).
 */
object FleetSendTargets {
    private const val BYTE_MASK = 0xFF
    private const val OCTET2_SHIFT = 8
    private const val OCTET3_SHIFT = 16

    /**
     * The `/24` subnet-directed broadcast host for a little-endian WiFi
     * [ipAddress] (Android reports `dhcpInfo.ipAddress` little-endian, so the
     * low three octets are the address's first three): e.g. `192.168.0.234`
     * -> `"192.168.0.255"`. Null when [ipAddress] is 0 (no lease yet).
     */
    fun subnetBroadcastHost(ipAddress: Int): String? {
        if (ipAddress == 0) return null
        val a = ipAddress and BYTE_MASK
        val b = (ipAddress shr OCTET2_SHIFT) and BYTE_MASK
        val c = (ipAddress shr OCTET3_SHIFT) and BYTE_MASK
        return "$a.$b.$c.255"
    }

    /**
     * The two addresses a `$ZNAV` datagram is sent to, group first: the fixed
     * fleet multicast [group], and the subnet-directed broadcast derived from
     * [ipAddress] (falling back to [limitedBroadcast] when there is no lease
     * yet, or when the derived host fails to resolve).
     */
    fun broadcastTargets(
        group: String,
        ipAddress: Int,
        limitedBroadcast: String,
    ): List<InetAddress> {
        val host = subnetBroadcastHost(ipAddress) ?: limitedBroadcast
        val fallback =
            runCatching { InetAddress.getByName(host) }
                .getOrElse { InetAddress.getByName(limitedBroadcast) }
        return listOf(InetAddress.getByName(group), fallback)
    }
}
