package org.pureagave.zodiac.beacon

import java.util.Locale

/**
 * Minimal NMEA 0183 output helpers: the standard XOR checksum and a synthesized
 * true-heading (HDT) sentence for the phone's compass. The GNSS sentences the
 * phone already emits are forwarded verbatim; HDT is the one we generate.
 */
object Nmea {
    private const val FULL_CIRCLE = 360.0

    // $ZVER field bounds — mirror the tablet's FleetVersionProtocol grammar.
    private const val NODE_MAX_LEN = 8
    private const val LABEL_MAX_LEN = 16

    // Largest value the epoch grammar ([0-9]{1,10}) can carry: ten nines.
    private const val MAX_EPOCH = 9_999_999_999L

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

    /**
     * `$ZAUD,rms,peak,beat*cs` — ambient sound for reactive lighting. [rms] and
     * [peak] are normalized 0..1 loudness; [beat] flags a detected onset this
     * frame. Three decimals on the levels so a quiet room still reads non-zero.
     */
    fun zaud(
        rms: Double,
        peak: Double,
        beat: Boolean,
    ): String {
        val body = "ZAUD,%.3f,%.3f,%d".format(Locale.US, rms, peak, if (beat) 1 else 0)
        return "\$$body*${checksum(body)}\r\n"
    }

    /** `$ZENV,lux*cs` — ambient light in lux, for the fleet's auto day/night switch. */
    fun zenv(lux: Double): String {
        val body = "ZENV,%.1f".format(Locale.US, lux)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$ZSHK,peakG*cs` — a shock/impact event: the peak linear-acceleration
     * magnitude (gravity removed) in g since the last quiet frame. Event-driven:
     * emitted only when a spike crosses the detector's threshold.
     */
    fun zshk(peakG: Double): String {
        val body = "ZSHK,%.2f".format(Locale.US, peakG)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$ZBCN,batt,fixQ,sats,uptimeS*cs` — beacon health heartbeat so the fleet
     * knows the sensor phone is alive and when it's about to die: battery percent,
     * GNSS fix quality + satellite count (from the GGA passthrough), and uptime.
     */
    fun zbcn(
        batteryPct: Int,
        fixQuality: Int,
        satellites: Int,
        uptimeSec: Long,
    ): String {
        val body = "ZBCN,%d,%d,%d,%d".format(Locale.US, batteryPct, fixQuality, satellites, uptimeSec)
        return "\$$body*${checksum(body)}\r\n"
    }

    /** `$ZODO,tripM,totalM*cs` — trip (this session) + lifetime odometer, in metres. */
    fun zodo(
        tripM: Double,
        totalM: Double,
    ): String {
        val body = "ZODO,%.1f,%.1f".format(Locale.US, tripM, totalM)
        return "\$$body*${checksum(body)}\r\n"
    }

    /**
     * `$ZVER,node,name,base,sha,dirty,epoch*cs` — the beacon's build announcement
     * for the FLEET-1 version monitor. One of three hand-written implementations
     * of this wire (with the tablet's `FleetVersionProtocol.kt` and the Jetson's
     * `version_protocol.py`), pinned together by `protocol/version-protocol-golden.json`;
     * the beacon only *emits*, so it builds but never parses. Fields are sanitised
     * to their grammar so the output always parses on the tablet: [node] uppercased
     * `[A-Z0-9]` last 8, [name]/[base] filtered to their legal sets, [epoch] clamped
     * into `[0, 10^10)`. [sha] and [dirty] come straight from `BuildConfig` (already
     * `9-hex`-or-`unknown` and a bool).
     */
    fun zver(
        node: String,
        name: String,
        base: String,
        sha: String,
        dirty: Boolean,
        epoch: Long,
    ): String {
        val body =
            "ZVER,${sanitizeNode(node)},${sanitizeLabel(name, "._-", "node")}," +
                "${sanitizeLabel(base, ".+~-", "0.0.0")},$sha,${if (dirty) "1" else "0"}," +
                "${epoch.coerceIn(0L, MAX_EPOCH)}"
        return "\$$body*${checksum(body)}\r\n"
    }

    /** Uppercase, `[A-Z0-9]` only, last 8 chars; "0" when nothing survives. */
    private fun sanitizeNode(raw: String): String {
        val cleaned = raw.uppercase(Locale.US).filter { it in 'A'..'Z' || it in '0'..'9' }
        return cleaned.takeLast(NODE_MAX_LEN).ifEmpty { "0" }
    }

    /** Keep only [extra]-plus-alphanumeric chars, first 16; [fallback] when nothing survives. */
    private fun sanitizeLabel(
        raw: String,
        extra: String,
        fallback: String,
    ): String {
        val cleaned = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in extra }
        return cleaned.take(LABEL_MAX_LEN).ifEmpty { fallback }
    }

    /** Two-hex-digit XOR of the sentence body (the chars between `$` and `*`). */
    fun checksum(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(Locale.ROOT, c)
    }
}
