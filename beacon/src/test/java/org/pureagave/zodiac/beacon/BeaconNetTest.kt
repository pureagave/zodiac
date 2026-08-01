package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
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
    fun subnet_broadcast_is_the_dot_255_of_the_little_endian_address() {
        // Android reports ipAddress little-endian: 192.168.0.234 arrives as the
        // int 0x...EA00A8C0 (a=192, b=168, c=0). Build it octet by octet.
        val ip = 192 or (168 shl 8) or (0 shl 16) or (234 shl 24)
        assertEquals("192.168.0.255", BeaconNet.subnetBroadcastHost(ip))
    }

    @Test
    fun subnet_broadcast_handles_a_different_subnet() {
        val ip = 10 or (0 shl 8) or (7 shl 16) or (42 shl 24)
        assertEquals("10.0.7.255", BeaconNet.subnetBroadcastHost(ip))
    }

    @Test
    fun subnet_broadcast_falls_back_to_null_when_no_address_yet() {
        // ip == 0 means WiFi has no DHCP lease; caller uses the limited broadcast.
        assertNull(BeaconNet.subnetBroadcastHost(0))
    }
}
