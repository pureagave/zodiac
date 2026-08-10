package org.pureagave.zodiac.control.core.vision

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compact wire format for the thermal-threat channel. One UDP datagram = one
 * frame: the literal `ZTHREAT` header, then a `;`-separated contact per entry
 * `id:relAzDeg:size:collision` (collision 0/1). e.g.
 *
 *   `ZTHREAT;1:-12.0:0.300:0;2:4.5:0.900:1`
 *
 * The edge box (Jetson) emits these, the tablets parse them. A frame with no
 * contacts is just `ZTHREAT` (→ empty list), which is how "all clear" is
 * signalled.
 *
 * **This is one half of a cross-language contract.** The other half is
 * `jetson/zvision/threat_protocol.py`. Neither file is authoritative: the shared
 * truth is the checked-in corpus at `protocol/threat-protocol-golden.json`,
 * which both test suites read. Change the format here and the corpus test fails
 * until the corpus and the Python side agree — which is the point. Before that
 * corpus existed the two implementations had silently drifted apart in ten
 * measured ways (see SYNC.md 2026-08-10).
 */
object ThreatProtocol {
    const val HEADER = "ZTHREAT"
    private const val FRAME_SEP = ';'
    private const val FIELD_SEP = ':'
    private const val FIELDS_PER_CONTACT = 4

    // Bearings are full-circle: the edge box fuses a ring of cameras into
    // contacts all the way around the vehicle, so ±180 is the real limit and
    // anything past it is garbage rather than a rear contact. Clamp size to its
    // 0..1 range; cap the count so one hostile/buggy frame can't flood the HUD.
    private const val MAX_ABS_AZ_DEG = 180f
    private const val MAX_CONTACTS = 32
    private const val AZ_DECIMALS = 1
    private const val SIZE_DECIMALS = 3

    // The numeric grammar is stated explicitly rather than delegated to
    // `toIntOrNull`/`toFloatOrNull`, because a wire format must not inherit the
    // host platform's parser quirks. Kotlin's float parser accepts Java source
    // syntax — `5.0f`, `5.0d`, and hex floats like `0x1p3` — so before this was
    // pinned, `0x1p3` in an azimuth field became a live contact bearing 8° on
    // the driver's HUD, while the Python mirror rejected it. Python's `int()`
    // meanwhile accepts underscores, surrounding whitespace and unbounded
    // magnitudes (a 4000-digit track id parsed fine there and was dropped here).
    // These bounds are what the producer actually emits: 9 integer digits keeps
    // an id inside Int32 and keeps every value far below Float's range, so no
    // parsed number here can be NaN or infinite.
    private val ID_PATTERN = Regex("-?[0-9]{1,9}")
    private val NUMBER_PATTERN = Regex("-?[0-9]{1,9}(\\.[0-9]{1,6})?")

    // Framing whitespace only — a UDP payload routinely carries a trailing CRLF.
    // Spelled out because Kotlin's `trim()` and Python's `strip()` disagree about
    // which Unicode code points count, and the wire contract cannot depend on that.
    private const val FRAME_WHITESPACE = " \t\n\r\u000B\u000C"

    /**
     * Serialise contacts to one wire frame; an empty list yields the bare header
     * ("all clear"). Fixed-precision ASCII via [BigDecimal], which is
     * locale-independent by construction — `String.format` would follow the
     * device locale and a comma decimal separator emits `0,300`, taking the
     * whole fleet's threat channel down. Mirrors `format_frame` in the Python half,
     * including dropping non-finite contacts at the source and capping the
     * count: the producer keeps its own frames well-formed rather than leaning
     * on the consumer's guard being there.
     */
    fun format(threats: List<DriverThreat>): String {
        val capped =
            if (threats.size > MAX_CONTACTS) {
                // Keep the most important contacts so the frame stays under one
                // MTU: collisions first, then nearest.
                threats
                    .sortedWith(
                        compareByDescending<DriverThreat> { it.collision }.thenByDescending { it.size },
                    ).take(MAX_CONTACTS)
            } else {
                threats
            }
        return buildString {
            append(HEADER)
            capped.forEach { t ->
                if (!t.relAzDeg.isFinite() || !t.size.isFinite()) return@forEach
                append(FRAME_SEP)
                append(t.id).append(FIELD_SEP)
                append(fixed(t.relAzDeg, AZ_DECIMALS)).append(FIELD_SEP)
                append(fixed(t.size, SIZE_DECIMALS)).append(FIELD_SEP)
                append(if (t.collision) 1 else 0)
            }
        }
    }

    // Half-to-even, matching the producer. `String.format("%.1f")` would round
    // half-UP and spell 0.25 as "0.3" where the Jetson writes "0.2" — a silent
    // one-digit disagreement between the two halves of the wire contract.
    // BigDecimal(Double) takes the exact binary value, so the only frames the
    // two sides can still spell differently are those where a 32-bit float and
    // a 64-bit float are genuinely different numbers; the producer's 64-bit
    // value is authoritative there, and both parsers accept either spelling.
    private fun fixed(
        value: Float,
        decimals: Int,
    ): String {
        val s = BigDecimal(value.toDouble()).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
        // BigDecimal has no negative zero, so it spells -0.0 as "0.0" while the
        // producer writes "-0.0" — and a contact a hair to the left of the nose
        // is exactly where that shows up. Restore the sign from the raw bits so
        // the two halves agree; both parsers read either spelling as zero.
        val negative = java.lang.Float.floatToRawIntBits(value) < 0
        return if (negative && !s.startsWith("-")) "-$s" else s
    }

    /** Parse a frame into contacts, or null if the line isn't a ZTHREAT frame. */
    fun parse(line: String): List<DriverThreat>? {
        val parts = line.trim { it in FRAME_WHITESPACE }.split(FRAME_SEP)
        if (parts.firstOrNull() != HEADER) return null
        return parts
            .drop(1)
            .mapNotNull { entry ->
                val f = entry.split(FIELD_SEP)
                if (f.size < FIELDS_PER_CONTACT) return@mapNotNull null
                if (!ID_PATTERN.matches(f[0])) return@mapNotNull null
                if (!NUMBER_PATTERN.matches(f[1]) || !NUMBER_PATTERN.matches(f[2])) return@mapNotNull null
                val az = f[1].toFloat()
                if (az < -MAX_ABS_AZ_DEG || az > MAX_ABS_AZ_DEG) return@mapNotNull null
                DriverThreat(
                    relAzDeg = az,
                    size = f[2].toFloat().coerceIn(0f, 1f),
                    // A malformed flag must never cost the driver a contact: the
                    // body still gets drawn, just not in collision red. Anything
                    // that is not exactly "1" is false.
                    collision = f[3] == "1",
                    id = f[0].toInt(),
                )
            }.take(MAX_CONTACTS)
    }
}
