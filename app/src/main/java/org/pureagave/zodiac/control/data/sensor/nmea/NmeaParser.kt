package org.pureagave.zodiac.control.data.sensor.nmea

import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.telemetry.AmbientLight
import org.pureagave.zodiac.control.core.telemetry.AudioLevel
import org.pureagave.zodiac.control.core.telemetry.BeaconHealth
import org.pureagave.zodiac.control.core.telemetry.Odometer
import org.pureagave.zodiac.control.core.telemetry.ShockEvent
import org.pureagave.zodiac.control.core.telemetry.VehicleTelemetry

/**
 * Minimal NMEA 0183 reader for cockpit use. Handles the GGA (fix + altitude
 * + HDOP) and RMC (fix + speed + course) sentence types from any talker —
 * `$GPxxx` (GPS), `$GLxxx` (GLONASS), `$GAxxx` (Galileo), `$GBxxx` (BeiDou),
 * and `$GNxxx` (multi-constellation) all match. Other sentence types are
 * ignored.
 *
 * Returns null when the sentence is invalid, the checksum doesn't match, or
 * the fix is "no fix" (status V or fix-quality 0). Trailing line endings are
 * stripped before parsing. Accepts both standard 2-digit checksums and the
 * non-conforming 1-digit form some receivers emit when the value is < 0x10.
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

    /**
     * True compass heading in degrees from the Zodiac Beacon's `$..HDT`
     * sentence, normalized to [0, 360). The network source merges it into the
     * fix so heading stays live even when the vehicle is stopped (where GPS
     * course is meaningless). Null for any other sentence type.
     *
     * Deliberately **not** matching VTG or HDG/HDM (AUDIT-2026-08-09 C6):
     * - VTG is GPS *course*, not compass — it is not a heading sentence at
     *   all. Android GNSS chips emit `$GxVTG` every epoch and the beacon
     *   forwards raw GNSS verbatim, so on the shipped fleet path VTG and the
     *   synthesized HDT interleave and last-writer-wins — a stopped vehicle's
     *   heading flip-flopped between real compass and course noise, and VTG
     *   kept the heading timestamp fresh, defeating the dead-compass watchdog
     *   ([org.pureagave.zodiac.control.data.sensor.NetworkLocationSource]'s
     *   `headingRxMs`) that exists to fall back to course when HDT dies.
     *   RMC's own course field is the correct course source; see
     *   `NetworkLocationSource.ingest`.
     * - HDG/HDM are *magnetic* heading (~13°E variation at BRC). Applying
     *   them as true heading without correcting for variation would be
     *   silently wrong by that amount, so — rather than parse the variation
     *   field just to get this one call site right — they're rejected here
     *   like any other non-HDT sentence. Revisit if a receiver that reports
     *   magnetic-only heading (no HDT) shows up on the fleet.
     */
    fun parseHeadingDeg(line: String): Double? {
        val sentence = line.trim().trimEnd('\r', '\n')
        if (!sentence.startsWith("$") || !checksumValid(sentence)) return null
        val fields = sentence.substringBefore('*').drop(1).split(',')
        return when (fields.firstOrNull()?.takeLast(SENTENCE_TYPE_LEN)) {
            "HDT" -> parseCourseDeg(fields.getOrElse(HEADING_FIELD) { "" })
            else -> null
        }
    }

    /**
     * Parse the proprietary vehicle-telemetry sentence
     * `$ZTLM,pitch,roll,speedKph*cs` from the Sensor Hub into a
     * [VehicleTelemetry], or null for any other/invalid sentence.
     */
    fun parseVehicleTelemetry(line: String): VehicleTelemetry? {
        val fields = fieldsForType(line, "TLM") ?: return null
        val pitch = fields.finiteAt(TLM_PITCH)
        val roll = fields.finiteAt(TLM_ROLL)
        val speed = fields.finiteAt(TLM_SPEED)
        return if (pitch != null && roll != null && speed != null) {
            VehicleTelemetry(pitchDeg = pitch, rollDeg = roll, speedKph = speed)
        } else {
            null
        }
    }

    /**
     * Parse `$ZAUD,rms,peak,beat*cs` (ambient sound for reactive lighting) into an
     * [AudioLevel], or null for any other/invalid sentence. Levels must be finite
     * and non-negative; `beat` is any int (0 = no onset).
     */
    fun parseAudioLevel(line: String): AudioLevel? {
        val fields = fieldsForType(line, "AUD") ?: return null
        val rms = fields.finiteAt(AUD_RMS)?.takeIf { it >= 0.0 }
        val peak = fields.finiteAt(AUD_PEAK)?.takeIf { it >= 0.0 }
        val beat = fields.getOrNull(AUD_BEAT)?.toIntOrNull()
        return if (rms != null && peak != null && beat != null) {
            AudioLevel(rms = rms, peak = peak, beat = beat != 0)
        } else {
            null
        }
    }

    /**
     * Parse `$ZENV,lux*cs` (ambient light) into an [AmbientLight], or null. Lux
     * must be finite and non-negative.
     */
    fun parseAmbientLight(line: String): AmbientLight? {
        val fields = fieldsForType(line, "ENV") ?: return null
        val lux = fields.finiteAt(ENV_LUX)?.takeIf { it >= 0.0 } ?: return null
        return AmbientLight(lux = lux)
    }

    /**
     * Parse `$ZSHK,peakG*cs` (a shock/impact event) into a [ShockEvent], or null.
     * Peak-g must be finite and non-negative.
     */
    fun parseShockEvent(line: String): ShockEvent? {
        val fields = fieldsForType(line, "SHK") ?: return null
        val peakG = fields.finiteAt(SHK_PEAK_G)?.takeIf { it >= 0.0 } ?: return null
        return ShockEvent(peakG = peakG)
    }

    /**
     * Parse `$ZBCN,batt,fixQ,sats,uptimeS*cs` (beacon health) into a
     * [BeaconHealth], or null. All four are integers; a missing/garbage field
     * rejects the whole sentence.
     */
    fun parseBeaconHealth(line: String): BeaconHealth? {
        val fields = fieldsForType(line, "BCN") ?: return null
        val batt = fields.getOrNull(BCN_BATTERY)?.toIntOrNull()
        val fixQ = fields.getOrNull(BCN_FIX_QUALITY)?.toIntOrNull()
        val sats = fields.getOrNull(BCN_SATELLITES)?.toIntOrNull()
        val uptime = fields.getOrNull(BCN_UPTIME)?.toLongOrNull()
        // Split into two checks so no single condition exceeds detekt's complexity limit.
        if (batt == null || fixQ == null || sats == null) return null
        return if (uptime == null) null else BeaconHealth(batteryPct = batt, fixQuality = fixQ, satellites = sats, uptimeSec = uptime)
    }

    /**
     * Parse `$ZODO,tripM,totalM*cs` (odometer) into an [Odometer], or null.
     * Distances must be finite and non-negative (a negative odometer is nonsense).
     */
    fun parseOdometer(line: String): Odometer? {
        val fields = fieldsForType(line, "ODO") ?: return null
        val trip = fields.finiteAt(ODO_TRIP)?.takeIf { it >= 0.0 }
        val total = fields.finiteAt(ODO_TOTAL)?.takeIf { it >= 0.0 }
        return if (trip != null && total != null) {
            Odometer(tripMeters = trip, totalMeters = total)
        } else {
            null
        }
    }

    private fun parseGga(fields: List<String>): GpsFix? {
        if (fields.size < GGA_MIN_FIELDS) return null
        val fixQuality = fields[GGA_FIX_QUALITY].toIntOrNull()?.takeIf { it > 0 }
        val lat = parseLatitude(fields[GGA_LAT], fields[GGA_LAT_HEMI])
        val lon = parseLongitude(fields[GGA_LON], fields[GGA_LON_HEMI])
        val hdop = fields[GGA_HDOP].toDoubleOrNull()?.takeIf { it.isFinite() }
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
                speedKph = parseSpeedKph(fields[RMC_SPEED_KNOTS]),
                headingDeg = parseCourseDeg(fields[RMC_COURSE]),
            )
        }
    }

    private fun parseLatitude(
        value: String,
        hemi: String,
    ): Double? {
        val ddmm = value.toDoubleOrNull()
        if (ddmm == null || !ddmm.isFinite() || hemi !in LAT_HEMIS) return null
        val deg = (ddmm / NMEA_LAT_DEG_DIVISOR).toInt()
        val min = ddmm - deg * NMEA_LAT_DEG_DIVISOR
        if (min < 0.0 || min >= MINUTES_PER_DEGREE) return null
        val signed = deg + min / MINUTES_PER_DEGREE
        val result = if (hemi == "S") -signed else signed
        return result.takeIf { it.isFinite() && it in LAT_MIN_DEG..LAT_MAX_DEG }
    }

    private fun parseLongitude(
        value: String,
        hemi: String,
    ): Double? {
        val dddmm = value.toDoubleOrNull()
        if (dddmm == null || !dddmm.isFinite() || hemi !in LON_HEMIS) return null
        val deg = (dddmm / NMEA_LAT_DEG_DIVISOR).toInt()
        val min = dddmm - deg * NMEA_LAT_DEG_DIVISOR
        if (min < 0.0 || min >= MINUTES_PER_DEGREE) return null
        val signed = deg + min / MINUTES_PER_DEGREE
        val result = if (hemi == "W") -signed else signed
        return result.takeIf { it.isFinite() && it in LON_MIN_DEG..LON_MAX_DEG }
    }

    /**
     * Ground speed in kph from a knots field. Returns null if the field is
     * absent, non-finite, or negative (an optional field, so bad input is
     * simply omitted rather than clamped).
     */
    private fun parseSpeedKph(value: String): Double? {
        val knots = value.toDoubleOrNull() ?: return null
        if (!knots.isFinite() || knots < 0.0) return null
        return knots * KNOTS_TO_KPH
    }

    /**
     * Course-over-ground normalized into [0.0, 360.0) via floored modulo so
     * 360.0 wraps to 0.0 and negatives wrap correctly. Returns null if the
     * field is absent or non-finite.
     */
    private fun parseCourseDeg(value: String): Double? {
        val course = value.toDoubleOrNull() ?: return null
        if (!course.isFinite()) return null
        return ((course % DEGREES_PER_CIRCLE) + DEGREES_PER_CIRCLE) % DEGREES_PER_CIRCLE
    }

    /**
     * Shared front-end for the single-type proprietary sentences: strip line
     * endings, validate the checksum, split fields, and confirm the 3-char type.
     * Returns the field list (type in `[0]`) or null if malformed or a different
     * type. The multi-type dispatchers ([parse], [parseHeadingDeg]) inline this
     * since they match several types at once.
     */
    private fun fieldsForType(
        line: String,
        type: String,
    ): List<String>? {
        val sentence = line.trim().trimEnd('\r', '\n')
        if (!sentence.startsWith("$") || !checksumValid(sentence)) return null
        val fields = sentence.substringBefore('*').drop(1).split(',')
        return if (fields.firstOrNull()?.takeLast(SENTENCE_TYPE_LEN) == type) fields else null
    }

    /** Field at [index] as a finite Double, or null if absent/garbage/non-finite. */
    private fun List<String>.finiteAt(index: Int): Double? = getOrNull(index)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun checksumValid(sentence: String): Boolean {
        val starIdx = sentence.indexOf('*')
        val checksumStr = if (starIdx < 0) "" else sentence.substring(starIdx + 1)
        val expected =
            checksumStr
                .takeIf { it.length in CHECKSUM_HEX_MIN_LEN..CHECKSUM_HEX_MAX_LEN }
                ?.toIntOrNull(CHECKSUM_RADIX)
                ?: return false
        var actual = 0
        for (i in 1 until starIdx) actual = actual xor sentence[i].code
        return actual == expected
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

    // HDT carries the heading in its first field.
    private const val HEADING_FIELD = 1

    // ZTLM proprietary telemetry: pitch, roll, speedKph.
    private const val TLM_PITCH = 1
    private const val TLM_ROLL = 2
    private const val TLM_SPEED = 3

    // ZAUD ambient sound: rms, peak, beat.
    private const val AUD_RMS = 1
    private const val AUD_PEAK = 2
    private const val AUD_BEAT = 3

    // ZENV ambient light: lux.
    private const val ENV_LUX = 1

    // ZSHK shock event: peak g.
    private const val SHK_PEAK_G = 1

    // ZBCN beacon health: battery%, fix-quality, satellites, uptime-seconds.
    private const val BCN_BATTERY = 1
    private const val BCN_FIX_QUALITY = 2
    private const val BCN_SATELLITES = 3
    private const val BCN_UPTIME = 4

    // ZODO odometer: trip metres, total metres.
    private const val ODO_TRIP = 1
    private const val ODO_TOTAL = 2

    private const val NMEA_LAT_DEG_DIVISOR = 100.0
    private const val MINUTES_PER_DEGREE = 60.0
    private const val DEGREES_PER_CIRCLE = 360.0
    private const val LAT_MIN_DEG = -90.0
    private const val LAT_MAX_DEG = 90.0
    private const val LON_MIN_DEG = -180.0
    private const val LON_MAX_DEG = 180.0
    private const val KNOTS_TO_KPH = 1.852
    private const val HDOP_TO_METERS = 5.0
    private const val CHECKSUM_HEX_MIN_LEN = 1
    private const val CHECKSUM_HEX_MAX_LEN = 2
    private const val CHECKSUM_RADIX = 16
    private const val SENTENCE_TYPE_LEN = 3

    private val LAT_HEMIS = setOf("N", "S")
    private val LON_HEMIS = setOf("E", "W")
}
