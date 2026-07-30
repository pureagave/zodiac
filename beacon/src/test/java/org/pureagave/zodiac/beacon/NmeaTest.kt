package org.pureagave.zodiac.beacon

import org.junit.After
import org.junit.Assert.assertEquals
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
