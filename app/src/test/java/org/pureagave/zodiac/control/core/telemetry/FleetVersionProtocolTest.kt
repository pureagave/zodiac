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

    /** Valid two-hex checksum for a body, for building deliberately-malformed-but-checksummed sentences. */
    private fun xor(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(c)
    }
}
