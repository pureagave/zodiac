package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.navigation.ClockTime
import java.util.Locale

/**
 * The intent carried by a `$ZNAV` message — semantic, not resolved coordinates
 * (spec R2): every device reconstructs the target through
 * `NavigationController`'s own entry points, so a received target is applied
 * exactly like a local one (single code path, no divergence). [Bath] in
 * particular stays dynamic: each device resolves nearest-toilet live from the
 * shared ego, so all devices agree without shipping a coordinate.
 */
sealed interface NavSharePayload {
    data class Preset(val target: NavTarget) : NavSharePayload

    data class Address(val clock: ClockTime, val ring: String) : NavSharePayload

    data object Bath : NavSharePayload

    data object Clear : NavSharePayload
}

/**
 * A full `$ZNAV` message: the Lamport [seq] + owning [src] that give the fleet
 * a deterministic last-write-wins order (see `NavShareArbiter`), plus the
 * [payload] itself.
 */
data class NavShareMessage(val seq: Int, val src: String, val payload: NavSharePayload)

/**
 * Builder + parser for the `$ZNAV` sentence — the app's own wire format
 * (`docs/PROTOCOLS.md` "ZNAV"), sharing no code with the beacon's `Nmea.kt`
 * (decision 10) but following its exact conventions: `$<BODY>*<CC>\r\n`,
 * US-ASCII, `Locale.US`, a two-hex-uppercase XOR checksum over `<BODY>`.
 *
 * ```
 * $ZNAV,<seq>,<src>,PRESET,<HOME|MAN|TEMPLE>*CC
 * $ZNAV,<seq>,<src>,ADDR,<H:MM>,<RING>*CC
 * $ZNAV,<seq>,<src>,BATH*CC
 * $ZNAV,<seq>,<src>,CLEAR*CC
 * ```
 *
 * [parse] never throws — any malformation (bad checksum, unknown type, wrong
 * field count, an out-of-range clock/ring) returns null, same discipline as
 * `NmeaParser` rejecting VTG/HDG. The field grammar is pinned with explicit
 * regexes (ZTHREAT discipline) rather than trusting a host-language parser:
 * `seq` is `[0-9]{1,9}` (≥ 1 on the wire, fits Int32), `src` is `[A-Z0-9]{1,8}`,
 * the clock field is `[0-9]{1,2}:[0-9]{2}` further range-checked to hours
 * 2..10 / minutes 0..59 (mirrors `ClockEntry`'s `isCityClock`), and the ring
 * field is `[A-Z]{1,9}` further checked against [StreetRingRadiiM]. Range
 * checks run *before* constructing a [ClockTime] — its `require` throws on an
 * out-of-range hour (1..12 is its own, wider, display range), so parse must
 * never hand it one.
 */
object NavShareProtocol {
    private const val SENTENCE_TYPE = "ZNAV"
    private const val PRESET_FIELD_COUNT = 5
    private const val ADDR_FIELD_COUNT = 6
    private const val NO_ARG_FIELD_COUNT = 4

    private const val MIN_CLOCK_HOUR = 2
    private const val MAX_CLOCK_HOUR = 10
    private const val MAX_CLOCK_MINUTE = 59

    private const val CHECKSUM_HEX_MIN_LEN = 1
    private const val CHECKSUM_HEX_MAX_LEN = 2
    private const val CHECKSUM_RADIX = 16

    private const val SRC_MAX_LEN = 6

    private val SEQ_REGEX = Regex("[0-9]{1,9}")
    private val SRC_REGEX = Regex("[A-Z0-9]{1,8}")
    private val CLOCK_REGEX = Regex("([0-9]{1,2}):([0-9]{2})")
    private val RING_REGEX = Regex("[A-Z]{1,9}")

    /** `$ZNAV,...*CC\r\n` for [msg]. Formats the clock field itself with `Locale.US` (decision 5) — it does not call [ClockTime.format], which is locale-unpinned. */
    fun build(msg: NavShareMessage): String {
        val payloadBody =
            when (val payload = msg.payload) {
                is NavSharePayload.Preset -> "PRESET,${payload.target.name}"
                is NavSharePayload.Address -> {
                    val clockStr = "%d:%02d".format(Locale.US, payload.clock.hours, payload.clock.minutes)
                    "ADDR,$clockStr,${payload.ring}"
                }
                NavSharePayload.Bath -> "BATH"
                NavSharePayload.Clear -> "CLEAR"
            }
        val body = "$SENTENCE_TYPE,${msg.seq},${msg.src},$payloadBody"
        return "\$$body*${checksumHex(body)}\r\n"
    }

    /** Parse a `$ZNAV` sentence, or null on any malformation. Never throws. */
    fun parse(line: String): NavShareMessage? = extractValidatedBody(line)?.split(',')?.let(::parseFields)

    /** Strip framing and confirm the checksum; returns the body (between `$` and `*`) only once it's verified. */
    private fun extractValidatedBody(line: String): String? {
        val sentence = line.trim().trimEnd('\r', '\n')
        val starIdx = sentence.indexOf('*')
        if (!sentence.startsWith("$") || starIdx < 0) return null
        val body = sentence.substring(1, starIdx)
        return body.takeIf { checksumMatches(it, sentence.substring(starIdx + 1)) }
    }

    private fun parseFields(fields: List<String>): NavShareMessage? {
        val seq = fields.getOrNull(1)?.let(::parseSeqField)
        val src = fields.getOrNull(2)?.takeIf { SRC_REGEX.matches(it) }
        val payload = parsePayload(fields)
        val valid = fields.getOrNull(0) == SENTENCE_TYPE && seq != null && src != null && payload != null
        return if (valid) NavShareMessage(seq = seq!!, src = src!!, payload = payload!!) else null
    }

    private fun parseSeqField(raw: String): Int? = raw.takeIf { SEQ_REGEX.matches(it) }?.toIntOrNull()?.takeIf { it >= 1 }

    private fun parsePayload(fields: List<String>): NavSharePayload? =
        when (fields.getOrNull(3)) {
            "PRESET" -> parsePreset(fields)
            "ADDR" -> parseAddress(fields)
            "BATH" -> NavSharePayload.Bath.takeIf { fields.size == NO_ARG_FIELD_COUNT }
            "CLEAR" -> NavSharePayload.Clear.takeIf { fields.size == NO_ARG_FIELD_COUNT }
            else -> null
        }

    private fun parsePreset(fields: List<String>): NavSharePayload.Preset? {
        if (fields.size != PRESET_FIELD_COUNT) return null
        val target = fields.getOrNull(4)?.let { name -> runCatching { NavTarget.valueOf(name) }.getOrNull() }
        return target?.let { NavSharePayload.Preset(it) }
    }

    private fun parseAddress(fields: List<String>): NavSharePayload.Address? {
        if (fields.size != ADDR_FIELD_COUNT) return null
        val clock = fields.getOrNull(4)?.let(::parseClockField)
        val ring = fields.getOrNull(5)?.takeIf { RING_REGEX.matches(it) && StreetRingRadiiM.containsKey(it) }
        return if (clock != null && ring != null) NavSharePayload.Address(clock, ring) else null
    }

    /** `[0-9]{1,2}:[0-9]{2}` further range-checked to hours 2..10 / minutes 0..59 -- checked BEFORE constructing [ClockTime], whose own `require` allows the wider 1..12 display range and throws outside it. */
    private fun parseClockField(raw: String): ClockTime? {
        val match = CLOCK_REGEX.matchEntire(raw) ?: return null
        // .toInt() is safe: the regex groups matched only [0-9]{1,2}, so this can't throw.
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in MIN_CLOCK_HOUR..MAX_CLOCK_HOUR && minute in 0..MAX_CLOCK_MINUTE) {
            ClockTime(hours = hour, minutes = minute)
        } else {
            null
        }
    }

    /** Uppercase, `[A-Z0-9]` only, kept to the last [SRC_MAX_LEN] characters; "0" when nothing survives. */
    fun sanitizeSrc(raw: String?): String {
        val cleaned = raw?.uppercase(Locale.US)?.filter { it in 'A'..'Z' || it in '0'..'9' }.orEmpty()
        return cleaned.takeLast(SRC_MAX_LEN).ifEmpty { "0" }
    }

    private fun checksumMatches(
        body: String,
        checksumStr: String,
    ): Boolean {
        val expected =
            checksumStr
                .takeIf { it.length in CHECKSUM_HEX_MIN_LEN..CHECKSUM_HEX_MAX_LEN }
                ?.toIntOrNull(CHECKSUM_RADIX)
                ?: return false
        return xorChecksum(body) == expected
    }

    private fun xorChecksum(body: String): Int {
        var c = 0
        for (ch in body) c = c xor ch.code
        return c
    }

    private fun checksumHex(body: String): String = "%02X".format(Locale.US, xorChecksum(body))
}
