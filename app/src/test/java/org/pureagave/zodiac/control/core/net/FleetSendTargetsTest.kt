package org.pureagave.zodiac.control.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors `beacon/.../BeaconNetTest.kt` -- same math, independent implementation (decision 10). */
class FleetSendTargetsTest {
    @Test
    fun subnet_broadcast_is_the_dot_255_of_the_little_endian_address() {
        // Android reports ipAddress little-endian: 192.168.0.234 arrives as the
        // int built octet by octet below (a=192, b=168, c=0, d=234).
        val ip = 192 or (168 shl 8) or (0 shl 16) or (234 shl 24)
        assertEquals("192.168.0.255", FleetSendTargets.subnetBroadcastHost(ip))
    }

    @Test
    fun subnet_broadcast_handles_a_different_subnet() {
        val ip = 10 or (0 shl 8) or (7 shl 16) or (42 shl 24)
        assertEquals("10.0.7.255", FleetSendTargets.subnetBroadcastHost(ip))
    }

    @Test
    fun subnet_broadcast_falls_back_to_null_when_no_address_yet() {
        assertNull(FleetSendTargets.subnetBroadcastHost(0))
    }

    @Test
    fun targets_always_include_the_fixed_group_first() {
        val withLease = FleetSendTargets.broadcastTargets(GROUP, LEASE_192_168_0_234, LIMITED)
        val noLease = FleetSendTargets.broadcastTargets(GROUP, 0, LIMITED)
        assertEquals(GROUP, withLease[0].hostAddress)
        assertEquals(GROUP, noLease[0].hostAddress)
    }

    @Test
    fun a_dhcp_lease_yields_the_subnet_directed_broadcast_as_the_second_target() {
        val targets = FleetSendTargets.broadcastTargets(GROUP, LEASE_192_168_0_234, LIMITED)
        assertEquals(2, targets.size)
        assertEquals("192.168.0.255", targets[1].hostAddress)
    }

    @Test
    fun no_lease_yet_falls_back_to_the_limited_broadcast() {
        val targets = FleetSendTargets.broadcastTargets(GROUP, 0, LIMITED)
        assertEquals(LIMITED, targets[1].hostAddress)
    }

    @Test
    fun the_fallback_changes_once_a_lease_arrives() {
        val before = FleetSendTargets.broadcastTargets(GROUP, 0, LIMITED)
        val after = FleetSendTargets.broadcastTargets(GROUP, LEASE_192_168_0_234, LIMITED)
        assertNotEquals(before[1], after[1])
    }

    private companion object {
        const val GROUP = "239.7.7.30"
        const val LIMITED = "255.255.255.255"

        /** 192.168.0.234 as Android reports it: little-endian. */
        const val LEASE_192_168_0_234 = (234 shl 24) or (0 shl 16) or (168 shl 8) or 192
    }
}
