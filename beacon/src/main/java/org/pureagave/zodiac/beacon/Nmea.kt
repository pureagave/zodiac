package org.pureagave.zodiac.beacon

import java.util.Locale

/**
 * Minimal NMEA 0183 output helpers: the standard XOR checksum and a synthesized
 * true-heading (HDT) sentence for the phone's compass. The GNSS sentences the
 * phone already emits are forwarded verbatim; HDT is the one we generate.
 */
object Nmea {
    private const val FULL_CIRCLE = 360.0

    /** `$GPHDT,<deg>,T*cs` — true heading, 0..360, one decimal. */
    fun hdt(headingDeg: Double): String {
        val norm = ((headingDeg % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
        // Locale.US: a comma-decimal locale would emit "12,3" and split the
        // sentence into an extra field, silently corrupting the fleet's heading.
        val body = "GPHDT,%.1f,T".format(Locale.US, norm)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * Proprietary vehicle-telemetry sentence `$ZTLM,pitch,roll,speedKph*cs` —
     * the IMU tilt (from the rotation vector) plus ground speed the fleet wants
     * beyond position/heading. Pitch/roll in degrees, speed in kph.
     */
    fun ztlm(
        pitchDeg: Double,
        rollDeg: Double,
        speedKph: Double,
    ): String {
        val body = "ZTLM,%.1f,%.1f,%.1f".format(Locale.US, pitchDeg, rollDeg, speedKph)
        return "\$$body*${checksum(body)}\r\n"
    }

    /** Two-hex-digit XOR of the sentence body (the chars between `$` and `*`). */
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(Locale.ROOT, c)
    }
}
