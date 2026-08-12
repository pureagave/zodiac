package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.navigation.ClockTime
import java.util.Locale

/**
 * Production builds the artifact ([NavShareProtocol.build]), the test mutates
 * it, and every negative assertion sits beside a positive control — the same
 * discipline `ZTHREAT`'s golden-corpus tests hold to. The hand-computed
 * checksum vectors below were XOR'd independently (see the plan's build log),
 * not derived by calling [NavShareProtocol] against itself.
 */
class NavShareProtocolTest {
    @Test
    fun round_trips_all_four_payload_types_including_10_30_and_esplanade() {
        val cases =
            listOf(
                NavShareMessage(1, "ABC123", NavSharePayload.Preset(NavTarget.HOME)),
                NavShareMessage(2, "ABC123", NavSharePayload.Preset(NavTarget.MAN)),
                NavShareMessage(3, "ABC123", NavSharePayload.Preset(NavTarget.TEMPLE)),
                NavShareMessage(4, "ABC123", NavSharePayload.Address(ClockTime(2, 15), "H")),
                NavShareMessage(5, "ABC123", NavSharePayload.Address(ClockTime(10, 30), "ESPLANADE")),
                NavShareMessage(6, "ABC123", NavSharePayload.Bath),
                NavShareMessage(7, "ABC123", NavSharePayload.Clear),
            )
        cases.forEach { msg ->
            val wire = NavShareProtocol.build(msg)
            assertEquals("round-trip for $msg", msg, NavShareProtocol.parse(wire))
        }
    }

    @Test
    fun built_sentence_still_parses_under_a_comma_decimal_default_locale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val msg = NavShareMessage(8, "4A0C11", NavSharePayload.Address(ClockTime(2, 15), "H"))
            val wire = NavShareProtocol.build(msg)
            // A comma-decimal locale unpinned would corrupt "2:15" or the
            // integer fields with locale-specific grouping/decimal marks.
            assertTrue("clock field must stay '2:15' under Locale.GERMANY: $wire", wire.contains(",2:15,"))
            assertEquals(msg, NavShareProtocol.parse(wire))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun checksum_matches_an_independently_hand_computed_vector() {
        // Body "ZNAV,7,4A0C11,PRESET,MAN" XOR'd by hand (not via
        // NavShareProtocol) comes to 0x75 -- see the plan build log.
        val msg = NavShareProtocol.parse("\$ZNAV,7,4A0C11,PRESET,MAN*75\r\n")
        assertNotNull(msg)
        assertEquals(7, msg!!.seq)
        assertEquals("4A0C11", msg.src)
        assertEquals(NavSharePayload.Preset(NavTarget.MAN), msg.payload)
    }

    @Test
    fun mutation_suite_positive_control_then_each_corruption_fails() {
        // Body "ZNAV,7,4A0C11,PRESET,MAN" -> checksum 75 (same hand-computed vector).
        val wire = "\$ZNAV,7,4A0C11,PRESET,MAN*75\r\n"

        // Positive control: the unmutated sentence parses.
        assertNotNull(NavShareProtocol.parse(wire))

        assertNull("a corrupted payload char must fail (checksum no longer matches)", NavShareProtocol.parse(wire.replace("MAN", "MAX")))
        assertNull("a flipped checksum digit must fail", NavShareProtocol.parse(wire.replace("*75", "*76")))
        assertNull("a stripped checksum must fail", NavShareProtocol.parse(wire.substringBefore("*") + "\r\n"))
    }

    @Test
    fun unknown_sentence_type_is_rejected() {
        assertNull(NavShareProtocol.parse(sentence("ZNAV,1,A,FOO")))
    }

    @Test
    fun wrong_field_counts_are_rejected_per_type() {
        // Positive controls beside each negative.
        assertNotNull("control: correct PRESET field count", NavShareProtocol.parse(sentence("ZNAV,1,A,PRESET,HOME")))
        assertNull("PRESET with an extra field", NavShareProtocol.parse(sentence("ZNAV,1,A,PRESET,HOME,EXTRA")))
        assertNull("PRESET missing its argument", NavShareProtocol.parse(sentence("ZNAV,1,A,PRESET")))

        assertNotNull("control: correct ADDR field count", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,2:15,H")))
        assertNull("ADDR missing the ring", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,2:15")))
        assertNull("ADDR with an extra field", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,2:15,H,EXTRA")))

        assertNotNull("control: correct BATH field count", NavShareProtocol.parse(sentence("ZNAV,1,A,BATH")))
        assertNull("BATH with an extra field", NavShareProtocol.parse(sentence("ZNAV,1,A,BATH,EXTRA")))

        assertNotNull("control: correct CLEAR field count", NavShareProtocol.parse(sentence("ZNAV,1,A,CLEAR")))
        assertNull("CLEAR with an extra field", NavShareProtocol.parse(sentence("ZNAV,1,A,CLEAR,EXTRA")))
    }

    @Test
    fun out_of_range_clocks_are_rejected() {
        assertNotNull("control: 2:15 is in range", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,2:15,H")))
        assertNull("hour 1 is below the BRC city clock's minimum (2)", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,1:30,H")))
        assertNull("hour 11 is above the BRC city clock's maximum (10)", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,11:00,H")))
        assertNull("minute 60 is out of range", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,2:60,H")))
    }

    @Test
    fun unknown_or_lowercase_rings_are_rejected() {
        assertNotNull("control: H is a known ring", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,4:00,H")))
        assertNull("Z is not a key of StreetRingRadiiM", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,4:00,Z")))
        assertNull("lowercase h fails the [A-Z]+ ring grammar", NavShareProtocol.parse(sentence("ZNAV,1,A,ADDR,4:00,h")))
    }

    @Test
    fun pinned_seq_grammar_rejects_every_hostile_form() {
        assertNotNull("control: a plain decimal seq", NavShareProtocol.parse(sentence("ZNAV,7,A,BATH")))
        assertNull("negative seq", NavShareProtocol.parse(sentence("ZNAV,-1,A,BATH")))
        assertNull("leading-space seq", NavShareProtocol.parse(sentence("ZNAV, 7,A,BATH")))
        assertNull("hex-float seq", NavShareProtocol.parse(sentence("ZNAV,0x1p3,A,BATH")))
        assertNull("10-digit seq (grammar caps at 9)", NavShareProtocol.parse(sentence("ZNAV,1234567890,A,BATH")))
        assertNull("underscore-grouped seq", NavShareProtocol.parse(sentence("ZNAV,1_000,A,BATH")))
        assertNull("seq of zero is below the wire minimum of 1", NavShareProtocol.parse(sentence("ZNAV,0,A,BATH")))
    }

    @Test
    fun pinned_src_grammar_rejects_every_hostile_form() {
        assertNotNull("control: an uppercase alnum src", NavShareProtocol.parse(sentence("ZNAV,1,ABC123,BATH")))
        assertNull("lowercase src", NavShareProtocol.parse(sentence("ZNAV,1,abc,BATH")))
        assertNull("9-char src (grammar caps at 8)", NavShareProtocol.parse(sentence("ZNAV,1,ABCDEFGHI,BATH")))
        assertNull("empty src", NavShareProtocol.parse(sentence("ZNAV,1,,BATH")))
    }

    @Test
    fun a_one_digit_checksum_is_accepted() {
        // Body "ZNAV,1,A,CLEAR" XOR's to 0x06 -- a single hex digit is the
        // non-conforming form some receivers/senders emit for values < 0x10;
        // NmeaParser accepts the same shape.
        val msg = NavShareProtocol.parse("\$ZNAV,1,A,CLEAR*6\r\n")
        assertNotNull(msg)
        assertEquals(NavSharePayload.Clear, msg!!.payload)
    }

    @Test
    fun trailing_line_ending_is_tolerated_in_either_form() {
        val bare = "\$ZNAV,1,A,CLEAR*06"
        val lfOnly = "\$ZNAV,1,A,CLEAR*06\n"
        val crlf = "\$ZNAV,1,A,CLEAR*06\r\n"
        assertNotNull("no line ending", NavShareProtocol.parse(bare))
        assertNotNull("LF only", NavShareProtocol.parse(lfOnly))
        assertNotNull("CRLF", NavShareProtocol.parse(crlf))
    }

    @Test
    fun parse_never_throws_on_hostile_strings() {
        val hostile =
            listOf(
                "",
                "$",
                "*",
                "\$*",
                "\$ZNAV",
                "\$ZNAV*",
                "\$ZNAV,,,,,,,,,,,,,,,,*FF",
                "\$ZNAV,1,A,ADDR,,*00",
                " ",
                "\$ZNAV,999999999999999999999999,A,BATH*00",
                "not nmea at all",
                "\$ZNAV,1,A,ADDR,2:15,H,EXTRA,EXTRA,EXTRA*00",
            )
        hostile.forEach { line ->
            // The only assertion is "does not throw" -- a thrown exception
            // fails the test on its own; null is the only acceptable return.
            val result = NavShareProtocol.parse(line)
            assertNull("hostile input must parse to null, not throw or succeed: $line", result)
        }
    }

    @Test
    fun sanitize_src_uppercases_filters_and_truncates() {
        assertEquals("0", NavShareProtocol.sanitizeSrc(null))
        assertEquals("0", NavShareProtocol.sanitizeSrc(""))
        assertEquals("0", NavShareProtocol.sanitizeSrc("!!!"))
        assertEquals("ABC123", NavShareProtocol.sanitizeSrc("abc123"))
        // Non-alnum characters are dropped, not replaced.
        assertEquals("ABCD99", NavShareProtocol.sanitizeSrc("ab-cd:99!!"))
        // Kept to the LAST six survivors.
        assertEquals("GH1234", NavShareProtocol.sanitizeSrc("abcdefgh1234"))
    }

    /** Wrap [body] in valid `$<BODY>*<CC>\r\n` framing with a correctly computed checksum, for grammar tests that must isolate the field under test from checksum failure. */
    private fun sentence(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "\$$body*%02X\r\n".format(Locale.US, c)
    }
}
