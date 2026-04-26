package ai.openclaw.zodiaccontrol.data.sensor.nmea

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix

/**
 * Minimal NMEA 0183 reader for cockpit use. Handles the two sentences every
 * consumer GPS receiver emits — `$GPGGA` (fix + altitude + HDOP) and `$GPRMC`
 * (fix + speed + course). Other sentence types are ignored.
 *
 * Returns null when the sentence is invalid, the checksum doesn't match, or
 * the fix is "no fix" (status V or fix-quality 0). Trailing line endings are
 * stripped before parsing.
 *
 * The parser preserves whichever fields are present in the sentence — a GGA
 * fills lat/lon and approximates accuracy from HDOP; an RMC fills lat/lon,
 * speed (converted from knots → kph), and heading.
 */
object NmeaParser {
    fun parse(line: String): GpsFix? {
        val sentence = line.trim().trimEnd('\r', '\n')
        if (!sentence.startsWith("$") || !checksumValid(sentence)) return null
        val fields = sentence.substringBefore('*').drop(1).split(',')
        return when (fields.firstOrNull()?.takeLast(SENTENCE_TYPE_LEN)) {
            "GGA" -> parseGga(fields)
            "RMC" -> parseRmc(fields)
            else -> null
        }
    }

    private fun parseGga(fields: List<String>): GpsFix? {
        if (fields.size < GGA_MIN_FIELDS) return null
        val fixQuality = fields[GGA_FIX_QUALITY].toIntOrNull()?.takeIf { it != 0 }
        val lat = parseLatitude(fields[GGA_LAT], fields[GGA_LAT_HEMI])
        val lon = parseLongitude(fields[GGA_LON], fields[GGA_LON_HEMI])
        val hdop = fields[GGA_HDOP].toDoubleOrNull()
        return if (fixQuality == null || lat == null || lon == null) {
            null
        } else {
            GpsFix(
                location = LatLon(lon = lon, lat = lat),
                fixQualityM = hdop?.let { it * HDOP_TO_METERS },
            )
        }
    }

    private fun parseRmc(fields: List<String>): GpsFix? {
        if (fields.size < RMC_MIN_FIELDS || fields[RMC_STATUS] != "A") return null
        val lat = parseLatitude(fields[RMC_LAT], fields[RMC_LAT_HEMI])
        val lon = parseLongitude(fields[RMC_LON], fields[RMC_LON_HEMI])
        return if (lat == null || lon == null) {
            null
        } else {
            GpsFix(
                location = LatLon(lon = lon, lat = lat),
                speedKph = fields[RMC_SPEED_KNOTS].toDoubleOrNull()?.let { it * KNOTS_TO_KPH },
                headingDeg = fields[RMC_COURSE].toDoubleOrNull(),
            )
        }
    }

    private fun parseLatitude(
        value: String,
        hemi: String,
    ): Double? {
        val ddmm = value.toDoubleOrNull()
        if (ddmm == null || hemi !in LAT_HEMIS) return null
        val deg = (ddmm / NMEA_LAT_DEG_DIVISOR).toInt()
        val min = ddmm - deg * NMEA_LAT_DEG_DIVISOR
        val signed = deg + min / MINUTES_PER_DEGREE
        return if (hemi == "S") -signed else signed
    }

    private fun parseLongitude(
        value: String,
        hemi: String,
    ): Double? {
        val dddmm = value.toDoubleOrNull()
        if (dddmm == null || hemi !in LON_HEMIS) return null
        val deg = (dddmm / NMEA_LAT_DEG_DIVISOR).toInt()
        val min = dddmm - deg * NMEA_LAT_DEG_DIVISOR
        val signed = deg + min / MINUTES_PER_DEGREE
        return if (hemi == "W") -signed else signed
    }

    private fun checksumValid(sentence: String): Boolean {
        val starIdx = sentence.indexOf('*')
        if (starIdx < 0 || starIdx + CHECKSUM_HEX_LEN + 1 > sentence.length) return false
        val expected =
            sentence
                .substring(starIdx + 1, starIdx + 1 + CHECKSUM_HEX_LEN)
                .toIntOrNull(CHECKSUM_RADIX)
        var actual = 0
        for (i in 1 until starIdx) actual = actual xor sentence[i].code
        return expected != null && actual == expected
    }

    private const val GGA_MIN_FIELDS = 10
    private const val GGA_LAT = 2
    private const val GGA_LAT_HEMI = 3
    private const val GGA_LON = 4
    private const val GGA_LON_HEMI = 5
    private const val GGA_FIX_QUALITY = 6
    private const val GGA_HDOP = 8

    private const val RMC_MIN_FIELDS = 9
    private const val RMC_STATUS = 2
    private const val RMC_LAT = 3
    private const val RMC_LAT_HEMI = 4
    private const val RMC_LON = 5
    private const val RMC_LON_HEMI = 6
    private const val RMC_SPEED_KNOTS = 7
    private const val RMC_COURSE = 8

    private const val NMEA_LAT_DEG_DIVISOR = 100.0
    private const val MINUTES_PER_DEGREE = 60.0
    private const val KNOTS_TO_KPH = 1.852
    private const val HDOP_TO_METERS = 5.0
    private const val CHECKSUM_HEX_LEN = 2
    private const val CHECKSUM_RADIX = 16
    private const val SENTENCE_TYPE_LEN = 3

    private val LAT_HEMIS = setOf("N", "S")
    private val LON_HEMIS = setOf("E", "W")
}
