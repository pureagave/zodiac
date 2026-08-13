package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconNetTest {
    @Test
    fun gga_yields_fix_quality_and_satellite_count() {
        // $GPGGA,time,lat,N,lon,W,fixQuality,sats,...
        val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        val h = BeaconNet.parseFixHealth(gga)
        assertEquals(1, h.fixQuality)
        assertEquals(8, h.satellites)
    }

    @Test
    fun a_talker_prefixed_gga_still_matches() {
        // Any 5-char sentence id ending in GGA (GN/GL/GA talkers) is accepted.
        val h = BeaconNet.parseFixHealth("\$GNGGA,000000,,,,,2,11,,,,,,,*00")
        assertEquals(2, h.fixQuality)
        assertEquals(11, h.satellites)
    }

    @Test
    fun a_non_gga_sentence_leaves_both_null() {
        val h = BeaconNet.parseFixHealth("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,,*6A")
        assertNull(h.fixQuality)
        assertNull(h.satellites)
    }

    @Test
    fun a_line_without_a_dollar_prefix_is_ignored() {
        val h = BeaconNet.parseFixHealth("GPGGA,123519,,,,,1,08")
        assertNull(h.fixQuality)
        assertNull(h.satellites)
    }

    @Test
    fun a_truncated_gga_does_not_crash_and_returns_nulls_for_missing_fields() {
        // Only three fields — the fix-quality (field 6) and sats (field 7) aren't there.
        val h = BeaconNet.parseFixHealth("\$GPGGA,123519,4807.038")
        assertNull(h.fixQuality)
        assertNull(h.satellites)
    }

    @Test
    fun a_gga_with_blank_fix_fields_returns_nulls_not_zeros() {
        // Empty strings aren't integers -> null, so the caller keeps its last value.
        val h = BeaconNet.parseFixHealth("\$GPGGA,123519,,,,,,,,,,,,,*00")
        assertNull(h.fixQuality)
        assertNull(h.satellites)
    }

    @Test
    fun subnet_broadcast_is_the_dot_255_of_the_little_endian_address_on_a_24() {
        // Android reports ipAddress little-endian: 192.168.0.234 arrives as the
        // int 0x...EA00A8C0 (a=192, b=168, c=0). Build it octet by octet.
        val ip = 192 or (168 shl 8) or (0 shl 16) or (234 shl 24)
        assertEquals("192.168.0.255", BeaconNet.directedBroadcastHost(ip, MASK_24))
    }

    @Test
    fun subnet_broadcast_handles_a_different_24_subnet() {
        val ip = 10 or (0 shl 8) or (7 shl 16) or (42 shl 24)
        assertEquals("10.0.7.255", BeaconNet.directedBroadcastHost(ip, MASK_24))
    }

    @Test
    fun subnet_broadcast_falls_back_to_null_when_no_address_yet() {
        // ip == 0 means WiFi has no DHCP lease; caller uses the limited broadcast.
        assertNull(BeaconNet.directedBroadcastHost(0, MASK_24))
    }

    @Test
    fun a_22_netmask_yields_the_correct_wider_broadcast() {
        // ip 192.168.5.30 / 255.255.252.0 (LE) -> network 192.168.4.0,
        // broadcast 192.168.7.255. Hand-verified: 252 = 0b11111100, so the /22
        // network floor is 192.168.4.0 and the last address in the block is
        // 192.168.7.255. This is the case the hardcoded-/24 mutation gets wrong
        // (it would answer 192.168.5.255 instead).
        val ip = 192 or (168 shl 8) or (5 shl 16) or (30 shl 24)
        val mask22 = 255 or (255 shl 8) or (252 shl 16) or (0 shl 24)
        assertEquals("192.168.7.255", BeaconNet.directedBroadcastHost(ip, mask22))
    }

    @Test
    fun a_zero_netmask_falls_back_to_24() {
        // netmask == 0 is the common DhcpInfo failure mode -- fall back to the
        // historical /24 assumption rather than deriving a bogus /0 broadcast.
        val ip = 192 or (168 shl 8) or (5 shl 16) or (30 shl 24)
        assertEquals("192.168.5.255", BeaconNet.directedBroadcastHost(ip, 0))
    }

    @Test
    fun a_garbage_non_contiguous_netmask_falls_back_to_24() {
        // 255.0.255.0 (LE) is not a real netmask -- host bits aren't a
        // contiguous suffix. Falling back to /24 rather than deriving a
        // meaningless broadcast from it.
        val ip = 192 or (168 shl 8) or (5 shl 16) or (30 shl 24)
        val garbageMask = 255 or (0 shl 8) or (255 shl 16) or (0 shl 24)
        assertEquals("192.168.5.255", BeaconNet.directedBroadcastHost(ip, garbageMask))
    }

    // --- broadcastTargets: the vehicle power-up race -------------------------

    @Test
    fun targets_always_include_the_fixed_fleet_multicast_group() {
        // The group is a constant, so it is correct even with no DHCP lease --
        // which is what keeps a beacon that booted before its router reachable
        // at all on the multicast path.
        val withLease = BeaconNet.broadcastTargets(GROUP, LEASE_192_168_0_234, MASK_24, LIMITED)
        val noLease = BeaconNet.broadcastTargets(GROUP, 0, MASK_24, LIMITED)
        assertEquals(GROUP, withLease[0].hostAddress)
        assertEquals(GROUP, noLease[0].hostAddress)
    }

    @Test
    fun a_dhcp_lease_yields_the_subnet_directed_broadcast() {
        val targets = BeaconNet.broadcastTargets(GROUP, LEASE_192_168_0_234, MASK_24, LIMITED)
        assertEquals("192.168.0.255", targets[1].hostAddress)
    }

    @Test
    fun no_lease_yet_falls_back_to_the_limited_broadcast() {
        // ipAddress == 0 is the normal state for the first seconds after the
        // vehicle powers up: the phone boots faster than the travel router.
        val targets = BeaconNet.broadcastTargets(GROUP, 0, MASK_24, LIMITED)
        assertEquals(LIMITED, targets[1].hostAddress)
    }

    @Test
    fun a_lease_with_no_usable_netmask_still_falls_back_to_24() {
        val targets = BeaconNet.broadcastTargets(GROUP, LEASE_192_168_0_234, 0, LIMITED)
        assertEquals("192.168.0.255", targets[1].hostAddress)
    }

    @Test
    fun the_fallback_changes_once_a_lease_arrives() {
        // The reason the caller re-resolves on a timer instead of trusting the
        // value it got at start-up: these two are not the same address, and a
        // beacon stuck on the first one is broadcasting into the void on any AP
        // that does not forward the limited broadcast.
        val before = BeaconNet.broadcastTargets(GROUP, 0, MASK_24, LIMITED)
        val after = BeaconNet.broadcastTargets(GROUP, LEASE_192_168_0_234, MASK_24, LIMITED)
        assertNotEquals(before[1], after[1])
    }

    // --- reportedFixHealth: GGA staleness -------------------------------------

    @Test
    fun a_fresh_gga_reports_the_live_values_with_no_staleness() {
        val h =
            BeaconNet.reportedFixHealth(
                nowMs = LAST_GGA + 9_999,
                lastGgaAtMs = LAST_GGA,
                fixQuality = 1,
                satellites = 8,
                staleAfterMs = STALE_AFTER,
            )
        assertEquals(1, h.fixQuality)
        assertEquals(8, h.satellites)
        assertNull(h.staleMs)
    }

    @Test
    fun exactly_at_the_staleness_boundary_is_still_fresh() {
        // Boundary is strictly greater-than, mirroring tickHealthLine's dead-loop
        // check. Mutation target: flip `>` to `>=` in reportedFixHealth.
        val h =
            BeaconNet.reportedFixHealth(
                nowMs = LAST_GGA + STALE_AFTER,
                lastGgaAtMs = LAST_GGA,
                fixQuality = 1,
                satellites = 8,
                staleAfterMs = STALE_AFTER,
            )
        assertEquals(1, h.fixQuality)
        assertEquals(8, h.satellites)
        assertNull(h.staleMs)
    }

    @Test
    fun one_ms_past_the_boundary_zeroes_out_and_reports_staleness() {
        val h =
            BeaconNet.reportedFixHealth(
                nowMs = LAST_GGA + STALE_AFTER + 1,
                lastGgaAtMs = LAST_GGA,
                fixQuality = 1,
                satellites = 8,
                staleAfterMs = STALE_AFTER,
            )
        assertEquals(0, h.fixQuality)
        assertEquals(0, h.satellites)
        assertEquals(STALE_AFTER + 1, h.staleMs)
    }

    @Test
    fun a_gga_that_never_arrived_passes_the_seeded_zeros_through_unflagged() {
        // lastGgaAtMs <= 0 means no GGA has ever arrived (fresh boot) -- report
        // the caller's (already 0/0) values without an alarming stale tag, since
        // nothing has gone stale: there was just never anything there. Mutation
        // target: drop the `lastGgaAtMs <= 0` guard -- a large `nowMs` (real
        // device uptime) would then read as a huge, spurious staleness.
        val h = BeaconNet.reportedFixHealth(nowMs = 50_000L, lastGgaAtMs = 0L, fixQuality = 0, satellites = 0, staleAfterMs = STALE_AFTER)
        assertEquals(0, h.fixQuality)
        assertEquals(0, h.satellites)
        assertNull(h.staleMs)
    }

    private companion object {
        const val GROUP = "239.7.7.10"
        const val LIMITED = "255.255.255.255"

        /** 192.168.0.234 as Android reports it: little-endian. */
        const val LEASE_192_168_0_234 = (234 shl 24) or (0 shl 16) or (168 shl 8) or 192

        /** 255.255.255.0 as Android reports it: little-endian. */
        const val MASK_24 = (0 shl 24) or (255 shl 16) or (255 shl 8) or 255

        const val LAST_GGA = 100_000L
        const val STALE_AFTER = 10_000L
    }
}
