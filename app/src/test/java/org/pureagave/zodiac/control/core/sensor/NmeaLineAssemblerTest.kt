package org.pureagave.zodiac.control.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A serial GPS hands over whatever was in the UART buffer at read time, so
 * sentences arrive split across reads and several to a read. This is where
 * fixes get silently dropped if the reassembly is wrong.
 */
class NmeaLineAssemblerTest {
    private fun NmeaLineAssembler.feed(s: String): List<String> {
        val b = s.toByteArray(Charsets.US_ASCII)
        return append(b, b.size)
    }

    @Test
    fun a_whole_line_in_one_chunk() {
        val a = NmeaLineAssembler()
        assertEquals(listOf("\$GPGGA,123519,4807.038,N"), a.feed("\$GPGGA,123519,4807.038,N\n"))
    }

    @Test
    fun a_line_split_across_two_reads_is_rejoined() {
        // The normal case for a serial GPS, and the one worth getting right.
        val a = NmeaLineAssembler()
        assertEquals(emptyList<String>(), a.feed("\$GPGGA,1235"))
        assertEquals(listOf("\$GPGGA,123519,4807"), a.feed("19,4807\n"))
    }

    @Test
    fun a_line_split_across_many_reads_is_rejoined() {
        val a = NmeaLineAssembler()
        "\$GPRMC,081836,A,3751.65,S".forEach { assertEquals(emptyList<String>(), a.feed(it.toString())) }
        assertEquals(listOf("\$GPRMC,081836,A,3751.65,S"), a.feed("\n"))
    }

    @Test
    fun several_lines_in_one_read_all_come_out_in_order() {
        val a = NmeaLineAssembler()
        assertEquals(listOf("\$ONE", "\$TWO", "\$THREE"), a.feed("\$ONE\n\$TWO\n\$THREE\n"))
    }

    @Test
    fun crlf_and_bare_lf_both_work() {
        assertEquals(listOf("\$A", "\$B"), NmeaLineAssembler().feed("\$A\r\n\$B\n"))
    }

    @Test
    fun a_stray_cr_inside_a_line_is_ignored_not_a_terminator() {
        assertEquals(listOf("\$AB"), NmeaLineAssembler().feed("\$A\rB\n"))
    }

    @Test
    fun blank_lines_are_not_emitted() {
        // A blank line carries no sentence; emitting it only makes the parser
        // reject it downstream.
        assertEquals(listOf("\$A"), NmeaLineAssembler().feed("\n\n\$A\n\n"))
    }

    @Test
    fun a_trailing_partial_line_is_held_not_emitted() {
        val a = NmeaLineAssembler()
        assertEquals(listOf("\$FULL"), a.feed("\$FULL\n\$PART"))
        assertEquals(listOf("\$PARTIAL"), a.feed("IAL\n"))
    }

    // -- the bounded-growth guarantee ---------------------------------------

    @Test
    fun an_endless_run_with_no_newline_does_not_accumulate() {
        // The real failure this guards: a wrong baud rate, cable noise, or a
        // half-connected receiver produces bytes with no line structure. An
        // unbounded accumulator would grow all night in a process that must
        // not die.
        val a = NmeaLineAssembler(maxLineLength = 64)
        repeat(500) { a.feed("X".repeat(100)) }
        assertTrue("over-long runs should have been dropped", a.droppedOverlong > 0)
        // And it still recovers on the next real sentence.
        assertEquals(listOf("\$GOOD"), a.feed("\n\$GOOD\n"))
    }

    @Test
    fun an_overlong_line_is_discarded_rather_than_truncated() {
        // A truncated sentence would fail its checksum anyway, but worse, it
        // could parse as something plausible. Drop it entirely.
        val a = NmeaLineAssembler(maxLineLength = 16)
        assertEquals(emptyList<String>(), a.feed("\$" + "A".repeat(100) + "\n"))
        assertEquals(1, a.droppedOverlong)
    }

    @Test
    fun the_line_after_an_overlong_one_is_clean() {
        val a = NmeaLineAssembler(maxLineLength = 16)
        val out = a.feed("\$" + "A".repeat(100) + "\n\$GPGGA,123\n")
        assertEquals(listOf("\$GPGGA,123"), out)
    }

    @Test
    fun a_line_exactly_at_the_limit_still_comes_through() {
        val a = NmeaLineAssembler(maxLineLength = 10)
        assertEquals(listOf("0123456789"), a.feed("0123456789\n"))
        assertEquals(0, a.droppedOverlong)
    }

    @Test
    fun default_limit_admits_a_full_length_nmea_sentence() {
        // NMEA 0183 allows 82 chars including delimiters; the default must not
        // clip a legal sentence.
        val a = NmeaLineAssembler()
        val sentence = "\$" + "G".repeat(80)
        assertEquals(listOf(sentence), a.feed("$sentence\n"))
    }

    // -- housekeeping --------------------------------------------------------

    @Test
    fun reset_forgets_a_partial_line() {
        // On a reconnect the half-sentence from before the drop must not be
        // glued onto the first sentence after it.
        val a = NmeaLineAssembler()
        a.feed("\$GPGGA,partial")
        a.reset()
        assertEquals(listOf("\$GPRMC,fresh"), a.feed("\$GPRMC,fresh\n"))
    }

    @Test
    fun count_beyond_the_array_is_clamped() {
        // Defensive: a driver reporting more bytes than it delivered must not
        // read past the buffer.
        val a = NmeaLineAssembler()
        val b = "\$A\n".toByteArray(Charsets.US_ASCII)
        assertEquals(listOf("\$A"), a.append(b, 9999))
    }

    @Test
    fun a_zero_length_read_yields_nothing() {
        assertEquals(emptyList<String>(), NmeaLineAssembler().append(ByteArray(8), 0))
    }

    @Test
    fun only_the_first_count_bytes_are_read() {
        // The buffer is reused across reads, so stale bytes past `count` are
        // still present and must be ignored.
        val a = NmeaLineAssembler()
        val buf = "\$A\nSTALEJUNK".toByteArray(Charsets.US_ASCII)
        assertEquals(listOf("\$A"), a.append(buf, 3))
    }
}
