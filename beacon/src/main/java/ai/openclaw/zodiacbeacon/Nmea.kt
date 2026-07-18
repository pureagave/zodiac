package ai.openclaw.zodiacbeacon

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
        val body = "GPHDT,%.1f,T".format(norm)
        return "\$$body*${checksum(body)}\r\n"
    }

    /** Two-hex-digit XOR of the sentence body (the chars between `$` and `*`). */
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(c)
    }
}
