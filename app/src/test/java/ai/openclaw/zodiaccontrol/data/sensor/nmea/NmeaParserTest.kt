package ai.openclaw.zodiaccontrol.data.sensor.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaParserTest {
    @Test
    fun parses_gga_with_active_fix() {
        // Real-world GGA from a u-blox receiver, near London.
        val line = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(48.1173, fix!!.location.lat, COORD_TOLERANCE)
        assertEquals(11.516667, fix.location.lon, COORD_TOLERANCE)
        assertEquals(0.9 * HDOP_TO_M, fix.fixQualityM ?: 0.0, COORD_TOLERANCE)
    }

    @Test
    fun parses_rmc_with_speed_and_heading() {
        val line = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(48.1173, fix!!.location.lat, COORD_TOLERANCE)
        assertEquals(11.516667, fix.location.lon, COORD_TOLERANCE)
        assertEquals(22.4 * KNOTS_TO_KPH, fix.speedKph ?: 0.0, SPEED_TOLERANCE)
        assertEquals(84.4, fix.headingDeg ?: 0.0, COORD_TOLERANCE)
    }

    @Test
    fun applies_southern_and_western_hemispheres() {
        val line = "\$GPRMC,123519,A,4807.038,S,01131.000,W,000.0,000.0,230394,,*45"

        // Recompute the checksum so the test sentence is valid.
        val signed = "\$GPRMC,123519,A,4807.038,S,01131.000,W,000.0,000.0,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val csHex = "%02X".format(cs)
        val withChecksum = "$signed*$csHex"

        val fix = NmeaParser.parse(withChecksum)

        assertNotNull(fix)
        assertEquals(-48.1173, fix!!.location.lat, COORD_TOLERANCE)
        assertEquals(-11.516667, fix.location.lon, COORD_TOLERANCE)
    }

    @Test
    fun rejects_no_fix_status() {
        // GGA with fixQuality=0
        val line = "\$GPGGA,123519,0000.000,N,00000.000,E,0,00,99.9,0.0,M,0.0,M,,*5C"

        val signed = "\$GPGGA,123519,0000.000,N,00000.000,E,0,00,99.9,0.0,M,0.0,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val csHex = "%02X".format(cs)
        val withChecksum = "$signed*$csHex"

        assertNull(NmeaParser.parse(line))
        assertNull(NmeaParser.parse(withChecksum))
    }

    @Test
    fun rejects_rmc_void_status() {
        val signed = "\$GPRMC,123519,V,4807.038,N,01131.000,E,000.0,000.0,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val csHex = "%02X".format(cs)
        val line = "$signed*$csHex"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun rejects_invalid_checksum() {
        val line = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun ignores_unknown_sentence_types() {
        val signed = "\$GPVTG,084.4,T,086.1,M,022.4,N,041.5,K,A"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val csHex = "%02X".format(cs)
        val line = "$signed*$csHex"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun ignores_non_dollar_lines() {
        assertNull(NmeaParser.parse(""))
        assertNull(NmeaParser.parse("garbage"))
        assertNull(NmeaParser.parse("GPGGA,..."))
    }

    @Test
    fun strips_trailing_line_endings() {
        val line = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"

        assertNotNull(NmeaParser.parse(line))
    }

    private companion object {
        const val COORD_TOLERANCE = 1e-4
        const val SPEED_TOLERANCE = 1e-3
        const val HDOP_TO_M = 5.0
        const val KNOTS_TO_KPH = 1.852
    }
}
