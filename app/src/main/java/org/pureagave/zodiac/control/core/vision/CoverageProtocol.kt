package org.pureagave.zodiac.control.core.vision

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compact wire format for the low-rate camera-coverage channel — `ZCOVER` — the
 * mirror of the Jetson's `jetson/zvision/coverage_protocol.py` (RES-P2-1).
 *
 * One UDP datagram = the set of bearing arcs a *currently delivering* camera can
 * see, so the DRIVER surround ring can render a dead camera's arc as a **blind**
 * sector instead of a false all-clear. It rides the **same** threat bus as
 * `ZTHREAT` (`239.7.7.20:10120`), so the tablet needs no new socket or group
 * join:
 *
 *   `ZCOVER;start:end;start:end`  — degrees, 1 decimal, swept clockwise
 *
 * `start` is in `[-180, 180]`; `end` is greater than `start` and within one full
 * turn of it, so a seam-straddling arc may report `end > 180`. A **bare**
 * `ZCOVER` means *nothing is covered* — the whole ring is blind. Covered arcs
 * (not blind arcs) ride the wire so the degenerate empty message fails BLIND,
 * never clear.
 *
 * **This is one half of a cross-language contract.** The shared truth is the
 * checked-in corpus at `protocol/coverage-protocol-golden.json`, which both test
 * suites read. The numeric grammar, framing whitespace and 32-bit-float rounding
 * are identical to [ThreatProtocol] so the two channels cannot drift in how they
 * read a number. On the tablet only [parse] runs in production; [format] is the
 * mirror the golden test pins.
 */
object CoverageProtocol {
    const val HEADER = "ZCOVER"
    private const val ARC_SEP = ';'
    private const val FIELD_SEP = ':'
    private const val FIELDS_PER_ARC = 2
    private const val COVER_DECIMALS = 1

    // A start bearing off the nose; anything past ±180 is garbage, not a rear
    // arc. An arc must sweep forward (end > start) and be no longer than a full
    // turn. Cap the count so one hostile/buggy datagram can't flood the ring —
    // and every rejected arc degrades toward LESS coverage (more blind).
    private const val MAX_ABS_START_DEG = 180f
    private const val MAX_SPAN_DEG = 360f
    private const val MAX_ARCS = 16

    // Same explicit grammar and framing whitespace as ThreatProtocol — a wire
    // format must not inherit the host platform's numeric parser, and the two
    // channels must read a number identically. `[0-9]`, not `\d`, because
    // Python's `\d` matches Unicode digits and Java's does not.
    private val NUMBER_PATTERN = Regex("-?[0-9]{1,9}(\\.[0-9]{1,6})?")
    private const val FRAME_WHITESPACE = " \t\n\r"

    /**
     * Serialise covered arcs to one `ZCOVER` frame; an empty list yields the
     * bare header (nothing covered = whole ring blind). Fixed-precision ASCII
     * via [BigDecimal], locale-independent by construction — mirrors
     * `format_coverage` in the Python half.
     */
    fun format(arcs: List<ClosedFloatingPointRange<Float>>): String =
        buildString {
            append(HEADER)
            arcs.take(MAX_ARCS).forEach { arc ->
                if (!arc.start.isFinite() || !arc.endInclusive.isFinite()) return@forEach
                append(ARC_SEP)
                append(fixed(arc.start, COVER_DECIMALS)).append(FIELD_SEP)
                append(fixed(arc.endInclusive, COVER_DECIMALS))
            }
        }

    /**
     * Parse a `ZCOVER` frame into covered arcs, or null if the line isn't a
     * `ZCOVER` frame at all (so a `ZTHREAT` datagram on the shared port is
     * cleanly ignored). A bare header parses to an empty list — a valid
     * "nothing covered". Malformed arcs are skipped, never fatal, and every skip
     * means less coverage (more blind), which is the safe direction.
     */
    fun parse(line: String): List<ClosedFloatingPointRange<Float>>? {
        val parts = line.trim { it in FRAME_WHITESPACE }.split(ARC_SEP)
        if (parts.firstOrNull() != HEADER) return null
        return parts.drop(1).mapNotNull(::parseArc).take(MAX_ARCS)
    }

    /** One `start:end` arc, or null if malformed / out of range — dropped toward less coverage. */
    private fun parseArc(entry: String): ClosedFloatingPointRange<Float>? {
        val f = entry.split(FIELD_SEP)
        if (f.size != FIELDS_PER_ARC) return null
        if (!NUMBER_PATTERN.matches(f[0]) || !NUMBER_PATTERN.matches(f[1])) return null
        val start = f[0].toFloat()
        val end = f[1].toFloat()
        val inRange = start in -MAX_ABS_START_DEG..MAX_ABS_START_DEG && end > start && end - start <= MAX_SPAN_DEG
        return if (inRange) start..end else null
    }

    // Half-to-even, matching the producer's `f"{v:.1f}"`. Identical to
    // ThreatProtocol.fixed (duplicated rather than shared so ThreatProtocol
    // stays byte-untouched): BigDecimal(Float.toDouble()) takes the exact binary
    // value, and the raw-bits sign restore keeps `-0.0` spelled the producer's
    // way. Both parsers read either spelling.
    private fun fixed(
        value: Float,
        decimals: Int,
    ): String {
        val s = BigDecimal(value.toDouble()).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
        val negative = java.lang.Float.floatToRawIntBits(value) < 0
        return if (negative && !s.startsWith("-")) "-$s" else s
    }
}
