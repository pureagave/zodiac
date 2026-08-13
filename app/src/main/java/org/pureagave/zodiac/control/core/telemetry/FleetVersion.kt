package org.pureagave.zodiac.control.core.telemetry

import java.util.Locale

/**
 * One node's build announcement (FLEET-1): who it is and what it is running.
 * [node] is the stable unique key (last 6 of `Settings.Secure.ANDROID_ID` on a
 * tablet, a stable id on the beacon/Jetson); [name] is a human label for the
 * roster (device model or hostname); [identity] is the FLEET-2 build identity.
 */
data class FleetVersion(
    val node: String,
    val name: String,
    val identity: BuildIdentity,
)

/**
 * Builder + parser for the `$ZVER` sentence (`docs/PROTOCOLS.md`, FLEET-1 spec at
 * `design/FLEET-1-version-monitor-spec.md`) — a node's periodic build
 * announcement on `FleetBus.VERSION_GROUP`. Same conventions as every other
 * `$Z*` sentence and sharing no code with `NavShareProtocol` or the beacon's
 * `Nmea.kt` (decision 10): `$<BODY>*<CC>\r\n`, US-ASCII, `Locale.US`, a
 * two-hex-uppercase XOR checksum over `<BODY>`.
 *
 * ```
 * $ZVER,<node>,<name>,<base>,<sha>,<dirty>,<epoch>*CC
 * e.g. $ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000*CC
 * ```
 *
 * [parse] never throws — any malformation (bad checksum, wrong field count, a
 * field that violates its grammar) returns null, same discipline as
 * `NavShareProtocol`. Every field is pinned with an explicit regex (ZTHREAT
 * discipline) rather than trusting a host-language split: this is one of three
 * hand-written implementations of the wire (this, the beacon's, the Jetson's),
 * so the grammar is the contract, not the parser.
 *
 * The wire is deliberately cross-language-safe: only ASCII digits, lowercase hex
 * for the sha (or the literal `unknown`), and `0`/`1` for dirty — no
 * locale-formatted numbers, no floats.
 */
object FleetVersionProtocol {
    private const val SENTENCE_TYPE = "ZVER"
    private const val FIELD_COUNT = 7

    // Indices into the body fields *after* the type (i.e. `fields.drop(1)`).
    private const val NODE_IDX = 0
    private const val NAME_IDX = 1
    private const val BASE_IDX = 2
    private const val SHA_IDX = 3
    private const val DIRTY_IDX = 4
    private const val EPOCH_IDX = 5

    private const val CHECKSUM_HEX_MIN_LEN = 1
    private const val CHECKSUM_HEX_MAX_LEN = 2
    private const val CHECKSUM_RADIX = 16

    private val NODE_REGEX = Regex("[A-Z0-9]{1,8}")
    private val NAME_REGEX = Regex("[A-Za-z0-9._-]{1,16}")
    private val BASE_REGEX = Regex("[0-9A-Za-z.+~-]{1,16}")
    private val SHA_REGEX = Regex("[0-9a-f]{7,40}|${BuildIdentity.UNKNOWN_SHA}")
    private val DIRTY_REGEX = Regex("[01]")
    private val EPOCH_REGEX = Regex("[0-9]{1,10}")

    // The six body fields after the type, in order: node, name, base, sha,
    // dirty, epoch. Validated against these in one pass so the conversions in
    // [parseFields] cannot fail.
    private val FIELD_REGEXES = listOf(NODE_REGEX, NAME_REGEX, BASE_REGEX, SHA_REGEX, DIRTY_REGEX, EPOCH_REGEX)

    /** `$ZVER,...*CC\r\n` for [version]. Fields are sanitized so the output always parses back. */
    fun build(version: FleetVersion): String {
        val dirty = if (version.identity.dirty) "1" else "0"
        val body =
            listOf(
                SENTENCE_TYPE,
                sanitizeNode(version.node),
                sanitizeName(version.name),
                sanitizeBase(version.identity.base),
                version.identity.sha,
                dirty,
                version.identity.commitEpochSeconds.coerceAtLeast(0).toString(),
            ).joinToString(",")
        return "\$$body*${checksumHex(body)}\r\n"
    }

    /** Parse a `$ZVER` sentence, or null on any malformation. Never throws. */
    fun parse(line: String): FleetVersion? = extractValidatedBody(line)?.split(',')?.let(::parseFields)

    /** Strip framing and confirm the checksum; returns the body (between `$` and `*`) only once verified. */
    private fun extractValidatedBody(line: String): String? {
        val sentence = line.trim().trimEnd('\r', '\n')
        val starIdx = sentence.indexOf('*')
        if (!sentence.startsWith("$") || starIdx < 0) return null
        val body = sentence.substring(1, starIdx)
        return body.takeIf { checksumMatches(it, sentence.substring(starIdx + 1)) }
    }

    private fun parseFields(fields: List<String>): FleetVersion? {
        if (fields.size != FIELD_COUNT || fields[0] != SENTENCE_TYPE) return null
        val values = fields.drop(1)
        if (values.zip(FIELD_REGEXES).any { (value, regex) -> !regex.matches(value) }) return null
        // Every field now matches its grammar, so these conversions cannot
        // fail: dirty is "0"/"1", epoch is [0-9]{1,10} (well below Long.MAX).
        return FleetVersion(
            node = values[NODE_IDX],
            name = values[NAME_IDX],
            identity =
                BuildIdentity(
                    base = values[BASE_IDX],
                    sha = values[SHA_IDX],
                    dirty = values[DIRTY_IDX] == "1",
                    commitEpochSeconds = values[EPOCH_IDX].toLong(),
                ),
        )
    }

    /** Uppercase, `[A-Z0-9]` only, last 8 chars; "0" when nothing survives. */
    private fun sanitizeNode(raw: String): String {
        val cleaned = raw.uppercase(Locale.US).filter { it in 'A'..'Z' || it in '0'..'9' }
        return cleaned.takeLast(NODE_MAX_LEN).ifEmpty { "0" }
    }

    /** Keep only grammar-legal chars, first 16; "node" when nothing survives (a name is display-only). */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-" }
        return cleaned.take(LABEL_MAX_LEN).ifEmpty { "node" }
    }

    /** Keep only grammar-legal chars, first 16; the FLEET-2 default `0.0.0` when nothing survives. */
    private fun sanitizeBase(raw: String): String {
        val cleaned = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".+~-" }
        return cleaned.take(LABEL_MAX_LEN).ifEmpty { "0.0.0" }
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

    private const val NODE_MAX_LEN = 8
    private const val LABEL_MAX_LEN = 16
}
