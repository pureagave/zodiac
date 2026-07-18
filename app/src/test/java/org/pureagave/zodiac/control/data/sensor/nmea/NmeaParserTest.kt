package org.pureagave.zodiac.control.data.sensor.nmea

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
    fun parses_ztlm_vehicle_telemetry() {
        val telem = NmeaParser.parseVehicleTelemetry(nmea("ZTLM,3.5,-2.0,18.4"))
        assertNotNull(telem)
        assertEquals(3.5, telem!!.pitchDeg, COORD_TOLERANCE)
        assertEquals(-2.0, telem.rollDeg, COORD_TOLERANCE)
        assertEquals(18.4, telem.speedKph, COORD_TOLERANCE)
    }

    @Test
    fun ztlm_rejects_other_sentences_and_bad_checksums() {
        assertNull(NmeaParser.parseVehicleTelemetry(nmea("GPHDT,90.0,T"))) // valid, not TLM
        assertNull(NmeaParser.parseVehicleTelemetry("\$ZTLM,1,2,3*00")) // bad checksum
    }

    @Test
    fun parses_hdt_heading() {
        assertEquals(123.4, NmeaParser.parseHeadingDeg(nmea("GPHDT,123.4,T")) ?: -1.0, COORD_TOLERANCE)
    }

    /** Frame a body with a valid NMEA checksum. */
    private fun nmea(body: String): String {
        val cs = body.fold(0) { acc, c -> acc xor c.code }
        return "\$$body*%02X".format(cs)
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
    fun rejects_empty_hemisphere_field() {
        // Degraded GPS fix can emit empty hemi fields. Treating "" as North/East
        // would produce a 5,000+ km lat/lon error, so the parser must return null.
        val signed = "\$GPRMC,123519,A,4807.038,,01131.000,E,000.0,000.0,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun rejects_invalid_hemisphere_letter() {
        val signed = "\$GPRMC,123519,A,4807.038,N,01131.000,X,000.0,000.0,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun rejects_gga_with_empty_hemisphere() {
        val signed = "\$GPGGA,123519,4807.038,N,01131.000,,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

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

    @Test
    fun accepts_non_gp_talker_ids() {
        // GLONASS, Galileo, BeiDou, and multi-constellation receivers all use
        // their own talker prefix on the same GGA / RMC payload shape.
        for (talker in listOf("GL", "GA", "GB", "GN")) {
            val signed = "\$${talker}GGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
            val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
            val line = "$signed*${"%02X".format(cs)}"

            val fix = NmeaParser.parse(line)

            assertNotNull("expected $talker talker to parse", fix)
            assertEquals(48.1173, fix!!.location.lat, COORD_TOLERANCE)
        }
    }

    @Test
    fun accepts_one_digit_checksum() {
        // Some receivers omit the leading zero on checksums < 0x10. The NMEA
        // spec requires 2 hex digits, but tolerating the short form costs
        // nothing and matches what real hardware emits.
        // Synthesize a payload whose XOR-checksum is < 0x10 by varying a tail
        // character through printable ASCII until one lands.
        val base = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,"
        val (payload, cs) =
            (0x20..0x7E).asSequence()
                .map { c -> "$base${c.toChar()}" to "$base${c.toChar()}".fold(0) { a, ch -> a xor ch.code } }
                .first { (_, x) -> x < 0x10 }
        val line = "\$$payload*${"%X".format(cs)}"

        assertNotNull(NmeaParser.parse(line))
    }

    @Test
    fun rejects_latitude_out_of_range() {
        // ddmm 9130.000 decodes to 91 deg 30 min = 91.5 deg — minutes are
        // valid (< 60) but the latitude exceeds 90, so the fix is rejected.
        val signed = "\$GPGGA,123519,9130.000,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun rejects_minutes_ge_sixty_in_latitude() {
        // ddmm 4875.000 decodes to 48 deg 75 min — minutes >= 60 is impossible
        // in a real fix, so the corrupt sentence is rejected.
        val signed = "\$GPGGA,123519,4875.000,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun rejects_longitude_out_of_range() {
        // dddmm 18130.000 decodes to 181 deg 30 min = 181.5 deg — exceeds 180,
        // so the fix is rejected despite valid minutes.
        val signed = "\$GPGGA,123519,4807.038,N,18130.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun normalizes_rmc_course_of_360_to_zero() {
        val signed = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,360.0,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(0.0, fix!!.headingDeg ?: -1.0, COORD_TOLERANCE)
    }

    @Test
    fun normalizes_rmc_course_of_540_to_180() {
        val signed = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,540.0,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(180.0, fix!!.headingDeg ?: -1.0, COORD_TOLERANCE)
    }

    @Test
    fun wraps_negative_rmc_course_into_range() {
        // -90.0 wraps to 270.0 via floored modulo into [0, 360).
        val signed = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,-90.0,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(270.0, fix!!.headingDeg ?: -1.0, COORD_TOLERANCE)
    }

    @Test
    fun rejects_garbage_latitude_field() {
        // A non-numeric lat field must yield null, never a NaN coordinate.
        val signed = "\$GPGGA,123519,abc,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun rejects_nan_latitude_field() {
        // "NaN" parses to Double.NaN; isFinite() rejects it before it can
        // become a NaN coordinate.
        val signed = "\$GPGGA,123519,NaN,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun round_trips_gga_position_from_known_coordinates() {
        // 40 deg 47.000' N -> ddmm "4047.000"; 111 deg 54.000' W -> dddmm "11154.000".
        // Decode: deg = int(ddmm/100), min = ddmm - deg*100, result = deg + min/60.
        val expectedLat = 40.0 + 47.0 / 60.0
        val expectedLon = -(111.0 + 54.0 / 60.0)
        val signed = "\$GPGGA,123519,4047.000,N,11154.000,W,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(expectedLat, fix!!.location.lat, COORD_TOLERANCE)
        assertEquals(expectedLon, fix.location.lon, COORD_TOLERANCE)
    }

    @Test
    fun round_trips_rmc_position_from_known_coordinates() {
        // 51 deg 30.000' N -> "5130.000"; 0 deg 7.500' W -> "00007.500".
        val expectedLat = 51.0 + 30.0 / 60.0
        val expectedLon = -(0.0 + 7.5 / 60.0)
        val signed = "\$GPRMC,123519,A,5130.000,N,00007.500,W,022.4,084.4,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(expectedLat, fix!!.location.lat, COORD_TOLERANCE)
        assertEquals(expectedLon, fix.location.lon, COORD_TOLERANCE)
    }

    @Test
    fun parses_rmc_position_with_empty_speed_and_course() {
        // Empty optional speed/course fields must null those values, not crash,
        // and the position must still decode.
        val signed = "\$GPRMC,123519,A,4807.038,N,01131.000,E,,,230394,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(48.1173, fix!!.location.lat, COORD_TOLERANCE)
        assertEquals(11.516667, fix.location.lon, COORD_TOLERANCE)
        assertNull(fix.speedKph)
        assertNull(fix.headingDeg)
    }

    @Test
    fun parses_gga_position_with_empty_hdop_altitude_fields() {
        // Empty HDOP/altitude optionals must leave fixQualityM null without
        // disturbing the decoded position.
        val signed = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,,,M,,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(48.1173, fix!!.location.lat, COORD_TOLERANCE)
        assertNull(fix.fixQualityM)
    }

    @Test
    fun returns_null_for_truncated_gga_below_required_field_count() {
        // GGA stops short before the HDOP index the parser reads; the size
        // guard must return null rather than throwing IndexOutOfBounds.
        val signed = "\$GPGGA,123519,4807.038,N,01131.000,E,1"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun returns_null_for_truncated_rmc_below_required_field_count() {
        // RMC stops short before the course index the parser reads.
        val signed = "\$GPRMC,123519,A,4807.038,N,01131.000,E"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun ignores_gsa_sentence_type() {
        val signed = "\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun ignores_gsv_sentence_type() {
        val signed = "\$GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        assertNull(NmeaParser.parse(line))
    }

    @Test
    fun applies_southern_and_western_hemispheres_on_gga() {
        // GGA hemisphere sign handling, complementing the existing RMC case.
        val signed = "\$GPGGA,123519,4807.038,S,01131.000,W,1,08,0.9,545.4,M,46.9,M,,"
        val cs = signed.substring(1).fold(0) { acc, c -> acc xor c.code }
        val line = "$signed*${"%02X".format(cs)}"

        val fix = NmeaParser.parse(line)

        assertNotNull(fix)
        assertEquals(-48.1173, fix!!.location.lat, COORD_TOLERANCE)
        assertEquals(-11.516667, fix.location.lon, COORD_TOLERANCE)
    }

    private companion object {
        const val COORD_TOLERANCE = 1e-4
        const val SPEED_TOLERANCE = 1e-3
        const val HDOP_TO_M = 5.0
        const val KNOTS_TO_KPH = 1.852
    }
}
