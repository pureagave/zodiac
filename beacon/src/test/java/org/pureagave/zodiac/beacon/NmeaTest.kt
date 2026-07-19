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
}
