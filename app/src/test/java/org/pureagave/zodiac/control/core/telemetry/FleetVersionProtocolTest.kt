package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `$ZVER` wire tests. The pinned-string cases use checksums computed **outside**
 * this code (by hand, XOR of the body bytes) so the format assertion cannot
 * agree with a broken checksum — the ZTHREAT trap. The reject cases are the
 * negative controls that keep `parse` from degenerating into "accept anything".
 */
class FleetVersionProtocolTest {
    private val hero =
        FleetVersion(
            node = "9C1977",
            name = "SM-X810",
            identity = BuildIdentity(base = "0.1.0", sha = "8f531e18a", dirty = false, commitEpochSeconds = 1_691_900_000L),
        )

    // Checksum *58 computed independently: XOR of "ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000".
    private val heroWire = "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000*58\r\n"

    @Test
    fun build_produces_the_pinned_wire_string() {
        assertEquals(heroWire, FleetVersionProtocol.build(hero))
    }

    @Test
    fun parse_accepts_the_pinned_wire_string() {
        assertEquals(hero, FleetVersionProtocol.parse(heroWire))
    }

    @Test
    fun build_then_parse_round_trips() {
        val cases =
            listOf(
                hero,
                FleetVersion("4A0C11", "KFTUWI", BuildIdentity("0.1.0", "abc1234de", dirty = true, 1_690_000_000L)),
                FleetVersion("Z", "zvision", BuildIdentity("2.0", BuildIdentity.UNKNOWN_SHA, dirty = true, 0L)),
            )
        cases.forEach { v -> assertEquals(v, FleetVersionProtocol.parse(FleetVersionProtocol.build(v))) }
    }

    @Test
    fun unknown_sha_parses_as_not_known() {
        // *57 computed independently over "ZVER,4A0C11,KFTUWI,0.1.0,unknown,1,0".
        val parsed = FleetVersionProtocol.parse("\$ZVER,4A0C11,KFTUWI,0.1.0,unknown,1,0*57\r\n")
        assertEquals("KFTUWI", parsed?.name)
        assertFalse("unknown sha must not read as a known build", parsed!!.identity.known)
        assertTrue(parsed.identity.dirty)
        assertEquals(0L, parsed.identity.commitEpochSeconds)
    }

    @Test
    fun parse_rejects_a_flipped_checksum() {
        assertNull(FleetVersionProtocol.parse("\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000*59\r\n"))
    }

    @Test
    fun parse_rejects_the_wrong_sentence_type() {
        assertNull(FleetVersionProtocol.parse("\$ZNAV,9C1977,SM-X810,0.1.0,8f531e18a,0,1691900000*58\r\n"))
    }

    @Test
    fun parse_rejects_the_wrong_field_count() {
        // Drop the epoch field; recomputing a valid checksum for it must still fail on shape.
        val short = "ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0"
        assertNull(FleetVersionProtocol.parse("\$$short*${xor(short)}\r\n"))
    }

    @Test
    fun parse_rejects_a_non_binary_dirty_flag() {
        val body = "ZVER,9C1977,SM-X810,0.1.0,8f531e18a,2,1691900000"
        assertNull(FleetVersionProtocol.parse("\$$body*${xor(body)}\r\n"))
    }

    @Test
    fun parse_rejects_a_sha_that_is_neither_hex_nor_unknown() {
        val body = "ZVER,9C1977,SM-X810,0.1.0,ZZZ,0,1691900000"
        assertNull(FleetVersionProtocol.parse("\$$body*${xor(body)}\r\n"))
    }

    @Test
    fun parse_rejects_a_non_numeric_epoch() {
        val body = "ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,17x"
        assertNull(FleetVersionProtocol.parse("\$$body*${xor(body)}\r\n"))
    }

    @Test
    fun build_sanitizes_a_name_carrying_delimiter_characters() {
        // Build.MODEL is trusted but not guaranteed comma-free; a stray comma
        // must never split the sentence into extra fields.
        val messy = hero.copy(name = "SM,X 810")
        val wire = FleetVersionProtocol.build(messy)
        val parsed = FleetVersionProtocol.parse(wire)
        assertEquals("the built sentence must round-trip", "SMX810", parsed?.name)
    }

    // --- Table-driven vectors. Every frame below carries a checksum computed
    // OUTSIDE this codebase (a Python one-liner, XOR of the body bytes), so the
    // suite is an in-tree stand-in for the cross-language golden corpus and can
    // never agree with a broken XOR. See the FLEET-1 spec §"golden corpus". ---

    private val acceptVectors =
        listOf(
            "\$ZVER,Z,zvision,2.0,unknown,1,0*7A" to
                FleetVersion("Z", "zvision", BuildIdentity("2.0", "unknown", dirty = true, 0L)),
            "\$ZVER,ABCDEFGH,SM-X810,0.1.0,8f531e18a,0,1691900000*22" to
                FleetVersion("ABCDEFGH", "SM-X810", BuildIdentity("0.1.0", "8f531e18a", dirty = false, 1_691_900_000L)),
            "\$ZVER,9C1977,SM-X810,0.1.0,${"a".repeat(40)},0,1700000000*3C" to
                FleetVersion("9C1977", "SM-X810", BuildIdentity("0.1.0", "a".repeat(40), dirty = false, 1_700_000_000L)),
            "\$ZVER,9C1977,SM-X810,0.1.0,abcdef1,0,1700000000*0A" to
                FleetVersion("9C1977", "SM-X810", BuildIdentity("0.1.0", "abcdef1", dirty = false, 1_700_000_000L)),
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,9999999999*5E" to
                FleetVersion("9C1977", "SM-X810", BuildIdentity("0.1.0", "8f531e18a", dirty = false, 9_999_999_999L)),
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,7*69" to
                FleetVersion("9C1977", "SM-X810", BuildIdentity("0.1.0", "8f531e18a", dirty = false, 7L)),
            "\$ZVER,9C1977,ABCDEFGHIJ012345,0.1.0,8f531e18a,0,1700000000*00" to
                FleetVersion("9C1977", "ABCDEFGHIJ012345", BuildIdentity("0.1.0", "8f531e18a", dirty = false, 1_700_000_000L)),
            "\$ZVER,9C1977,SM-X810,1.0~rc.1+x,8f531e18a,0,1700000000*65" to
                FleetVersion("9C1977", "SM-X810", BuildIdentity("1.0~rc.1+x", "8f531e18a", dirty = false, 1_700_000_000L)),
            // Leading zeros in epoch are tolerated and collapse to the integer value.
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,0017*58" to
                FleetVersion("9C1977", "SM-X810", BuildIdentity("0.1.0", "8f531e18a", dirty = false, 17L)),
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,1,1700000000*59" to
                FleetVersion("9C1977", "SM-X810", BuildIdentity("0.1.0", "8f531e18a", dirty = true, 1_700_000_000L)),
        )

    // Frames whose checksum is VALID (independently computed) but whose body
    // violates the grammar or shape — the negative controls that prove rejection
    // is on the grammar, not on the checksum. If the field regexes loosened, one
    // of these would start parsing.
    // Each frame's checksum is valid; the labelled defect is in the body. Order:
    // node 9ch(>8), name 17ch(>16), base 18ch(>16), sha 41hex(>40), sha 6hex(<7),
    // sha uppercase, epoch 11-digit(>10), signed epoch, empty node, empty epoch,
    // extra comma (8 fields), lowercase node.
    private val rejectVectors =
        listOf(
            "\$ZVER,ABCDEFGHI,SM-X810,0.1.0,8f531e18a,0,1700000000*6B",
            "\$ZVER,9C1977,ABCDEFGHIJ0123456,0.1.0,8f531e18a,0,1700000000*36",
            "\$ZVER,9C1977,SM-X810,ABCDEFGHIJ0123456X,8f531e18a,0,1700000000*0D",
            "\$ZVER,9C1977,SM-X810,0.1.0,${"f".repeat(41)},0,1700000000*5A",
            "\$ZVER,9C1977,SM-X810,0.1.0,abcdef,0,1700000000*3B",
            "\$ZVER,9C1977,SM-X810,0.1.0,ABCDEF1,0,1700000000*0A",
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,10000000000*6F",
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,+17*73",
            "\$ZVER,,SM-X810,0.1.0,8f531e18a,0,1700000000*2A",
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,*5E",
            "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1700000000,*74",
            "\$ZVER,9c1977,SM-X810,0.1.0,8f531e18a,0,1700000000*78",
        )

    @Test
    fun parse_accepts_every_golden_vector_with_independently_computed_checksums() {
        acceptVectors.forEach { (frame, expected) ->
            assertEquals("frame should parse: $frame", expected, FleetVersionProtocol.parse(frame))
        }
    }

    @Test
    fun parse_rejects_every_grammar_violation_despite_a_valid_checksum() {
        rejectVectors.forEach { frame ->
            assertNull("grammar-violating frame must reject: $frame", FleetVersionProtocol.parse(frame))
        }
    }

    @Test
    fun parse_rejects_an_embedded_control_char_in_a_field() {
        // A tab inside the node is invisible on the wire but must fail the grammar,
        // even though the checksum over the body with the tab is valid.
        val body = "ZVER,9C\t977,SM-X810,0.1.0,8f531e18a,0,1700000000"
        assertNull(FleetVersionProtocol.parse("\$$body*${xor(body)}\r\n"))
    }

    @Test
    fun parse_rejects_a_signed_checksum() {
        // The body "…ABCDEFGHIJ012345…" has XOR 0x00; a bare toIntOrNull(16) would
        // accept "+0"/"-0" as 0 and pass. The checksum must be strict hex.
        val zeroBody = "ZVER,9C1977,ABCDEFGHIJ012345,0.1.0,8f531e18a,0,1700000000"
        assertEquals("canonical checksum must still parse", 0, xor(zeroBody).toInt(16))
        assertNull("a '+0' checksum must be rejected", FleetVersionProtocol.parse("\$$zeroBody*+0\r\n"))
        assertNull("a '-0' checksum must be rejected", FleetVersionProtocol.parse("\$$zeroBody*-0\r\n"))
    }

    @Test
    fun parse_accepts_a_lowercase_checksum_for_cross_language_tolerance() {
        // build emits uppercase ("%02X"), but a peer emitting lowercase hex must
        // still be understood — parse is deliberately case-tolerant on the checksum.
        val body = "ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,9999999999" // XOR 0x5E
        val parsed = FleetVersionProtocol.parse("\$$body*5e\r\n")
        assertEquals(9_999_999_999L, parsed?.identity?.commitEpochSeconds)
        assertEquals("SM-X810", parsed?.name)
    }

    @Test
    fun build_clamps_an_out_of_range_epoch_so_the_output_still_round_trips() {
        // An epoch past 10 digits cannot be represented by the grammar; build must
        // clamp it (not emit an 11-digit field the parser would then reject).
        val tooBig = hero.copy(identity = hero.identity.copy(commitEpochSeconds = 10_000_000_000L))
        val parsed = FleetVersionProtocol.parse(FleetVersionProtocol.build(tooBig))
        assertEquals(9_999_999_999L, parsed?.identity?.commitEpochSeconds)
    }

    @Test
    fun build_clamps_a_negative_epoch_to_zero() {
        val negative = hero.copy(identity = hero.identity.copy(commitEpochSeconds = -1L))
        val parsed = FleetVersionProtocol.parse(FleetVersionProtocol.build(negative))
        assertEquals(0L, parsed?.identity?.commitEpochSeconds)
    }

    @Test
    fun build_falls_back_when_node_name_and_base_sanitize_to_empty() {
        // Nothing survives sanitization (all delimiter/illegal chars): the builder
        // must still emit a parseable sentence using the documented fallbacks.
        val empty = FleetVersion(",,,", "!!!", BuildIdentity("///", "8f531e18a", dirty = false, 1_700_000_000L))
        val parsed = FleetVersionProtocol.parse(FleetVersionProtocol.build(empty))
        assertEquals("0", parsed?.node)
        assertEquals("node", parsed?.name)
        assertEquals("0.0.0", parsed?.identity?.base)
    }

    @Test
    fun build_uppercases_a_lowercase_node() {
        val lower = hero.copy(node = "9c1977")
        assertEquals("9C1977", FleetVersionProtocol.parse(FleetVersionProtocol.build(lower))?.node)
    }

    /** Valid two-hex checksum for a body, for building deliberately-malformed-but-checksummed sentences. */
    private fun xor(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(c)
    }
}
