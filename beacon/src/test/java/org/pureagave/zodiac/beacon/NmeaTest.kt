package org.pureagave.zodiac.beacon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NmeaTest {
    private val original: Locale = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun hdt_uses_dot_decimals_even_in_a_comma_locale() {
        // A comma-decimal locale would emit "$GPHDT,12,3,T" and split the heading
        // into two fields — the tablet would misparse it. Guard against that.
        Locale.setDefault(Locale.GERMANY)
        assertTrue(Nmea.hdt(12.3).startsWith("\$GPHDT,12.3,T*"))
    }

    @Test
    fun ztlm_uses_dot_decimals_even_in_a_comma_locale() {
        Locale.setDefault(Locale.GERMANY)
        assertTrue(Nmea.ztlm(-2.5, 1.0, 8.4).startsWith("\$ZTLM,-2.5,1.0,8.4*"))
    }

    @Test
    fun hdt_normalizes_out_of_range_headings() {
        assertTrue(Nmea.hdt(365.0).startsWith("\$GPHDT,5.0,T*")) // 365 wraps to 5
        assertTrue(Nmea.hdt(-90.0).startsWith("\$GPHDT,270.0,T*"))
    }

    @Test
    fun checksum_matches_published_nmea_reference_vectors() {
        // The checksum was effectively UNTESTED: the only coverage validated
        // Nmea.checksum against itself, so replacing `xor` with `or` passed all
        // 35 beacon tests. Every sentence the fleet receives is gated on this
        // byte, and a receiver that rejects bad checksums would have dropped
        // the lot. These vectors come from the NMEA 0183 examples, not from our
        // own implementation -- that independence is the entire point.
        assertEquals(
            "47",
            Nmea.checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"),
        )
        assertEquals(
            "6A",
            Nmea.checksum("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"),
        )
        assertEquals("31", Nmea.checksum("GPGLL,4916.45,N,12311.12,W,225444,A"))
    }

    @Test
    fun checksum_is_order_dependent_and_not_a_bitwise_or() {
        // `or` is monotonic -- it can only set bits -- so it saturates toward FF
        // and is insensitive to repeats. XOR is neither. This kills the exact
        // mutant that survived the whole suite.
        assertEquals("00", Nmea.checksum("AA")) // xor of equal chars cancels
        assertNotEquals(Nmea.checksum("AB"), Nmea.checksum("ABAB"))
    }

    @Test
    fun checksum_is_two_uppercase_hex_digits() {
        val cs = Nmea.checksum("GPHDT,12.3,T")
        assertEquals(2, cs.length)
        assertEquals(cs.uppercase(), cs)
    }

    @Test
    fun zaud_uses_dot_decimals_even_in_a_comma_locale() {
        Locale.setDefault(Locale.GERMANY)
        assertTrue(Nmea.zaud(0.125, 0.8, beat = true).startsWith("\$ZAUD,0.125,0.800,1*"))
        assertTrue(Nmea.zaud(0.0, 0.0, beat = false).startsWith("\$ZAUD,0.000,0.000,0*"))
    }

    @Test
    fun zenv_uses_dot_decimals_even_in_a_comma_locale() {
        Locale.setDefault(Locale.GERMANY)
        assertTrue(Nmea.zenv(315.0).startsWith("\$ZENV,315.0*"))
    }

    @Test
    fun zshk_uses_dot_decimals_even_in_a_comma_locale() {
        Locale.setDefault(Locale.GERMANY)
        assertTrue(Nmea.zshk(2.35).startsWith("\$ZSHK,2.35*"))
    }

    @Test
    fun zbcn_formats_health_integers() {
        assertTrue(Nmea.zbcn(batteryPct = 87, fixQuality = 1, satellites = 9, uptimeSec = 3600L).startsWith("\$ZBCN,87,1,9,3600*"))
    }

    @Test
    fun zodo_uses_dot_decimals_even_in_a_comma_locale() {
        Locale.setDefault(Locale.GERMANY)
        assertTrue(Nmea.zodo(1234.5, 987654.0).startsWith("\$ZODO,1234.5,987654.0*"))
    }

    @Test
    fun new_sentences_carry_a_matching_checksum() {
        // The trailing checksum must equal an independent XOR of the body, or the
        // tablet rejects the sentence. Spot-check a new builder end to end.
        val s = Nmea.zodo(10.0, 20.0)
        val body = s.substringAfter('$').substringBefore('*')
        assertEquals(Nmea.checksum(body), s.substringAfter('*').trimEnd('\r', '\n'))
    }
}
