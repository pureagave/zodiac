package org.pureagave.zodiac.control.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LogFormatTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun formats_time_priority_tag_and_message() {
        val line = formatLogLine(EPOCH_MILLIS, PRIORITY_INFO, "Gps", "NET selected", zone = utc)

        assertEquals("2026-08-08 21:03:21.123Z I/Gps: NET selected", line)
    }

    @Test
    fun maps_every_android_priority_to_its_letter() {
        val letters =
            (PRIORITY_VERBOSE..PRIORITY_ASSERT).map { p ->
                formatLogLine(EPOCH_MILLIS, p, "T", "m", zone = utc).split(" ")[2].first()
            }

        assertEquals(listOf('V', 'D', 'I', 'W', 'E', 'A'), letters)
    }

    @Test
    fun an_unknown_priority_degrades_rather_than_throwing() {
        val line = formatLogLine(EPOCH_MILLIS, 99, "T", "m", zone = utc)

        assertTrue(line, line.contains(" ?/T: m"))
    }

    @Test
    fun a_null_tag_still_produces_a_parseable_line() {
        val line = formatLogLine(EPOCH_MILLIS, PRIORITY_INFO, null, "no tag", zone = utc)

        assertEquals("2026-08-08 21:03:21.123Z I/-: no tag", line)
    }

    @Test
    fun continuation_lines_are_indented_so_one_entry_reads_as_one_entry() {
        // Otherwise a stack trace in the file is indistinguishable from a
        // burst of separate log entries when you're scanning at camp.
        val line = formatLogLine(EPOCH_MILLIS, PRIORITY_ERROR, "T", "first\nsecond", zone = utc)

        assertEquals("2026-08-08 21:03:21.123Z E/T: first\n  second", line)
    }

    @Test
    fun a_stack_trace_follows_the_message_indented() {
        val line =
            formatLogLine(
                EPOCH_MILLIS,
                PRIORITY_ERROR,
                "Boom",
                "died",
                stackTrace = "java.lang.IllegalStateException: nope\n\tat Foo.bar(Foo.kt:1)\n",
                zone = utc,
            )

        assertEquals(
            "2026-08-08 21:03:21.123Z E/Boom: died\n" +
                "  java.lang.IllegalStateException: nope\n" +
                "  \tat Foo.bar(Foo.kt:1)",
            line,
        )
    }

    @Test
    fun a_blank_stack_trace_adds_nothing() {
        val line = formatLogLine(EPOCH_MILLIS, PRIORITY_INFO, "T", "m", stackTrace = "   ", zone = utc)

        assertEquals("2026-08-08 21:03:21.123Z I/T: m", line)
    }

    @Test
    fun severity_is_read_from_the_priority_field_not_the_message() {
        // A message quoting " E/" must not repaint itself as an error.
        val info = formatLogLine(EPOCH_MILLIS, PRIORITY_INFO, "T", "saw E/Foo in the logs", zone = utc)

        assertEquals(LogSeverity.NORMAL, logLineSeverity(info))
    }

    @Test
    fun warnings_and_errors_are_recoverable_for_the_viewer() {
        assertEquals(LogSeverity.WARN, logLineSeverity(formatLogLine(EPOCH_MILLIS, PRIORITY_WARN, "T", "m", zone = utc)))
        assertEquals(
            LogSeverity.ERROR,
            logLineSeverity(formatLogLine(EPOCH_MILLIS, PRIORITY_ERROR, "T", "m", zone = utc)),
        )
        assertEquals(
            LogSeverity.ERROR,
            logLineSeverity(formatLogLine(EPOCH_MILLIS, PRIORITY_ASSERT, "T", "m", zone = utc)),
        )
    }

    @Test
    fun continuation_and_junk_lines_do_not_claim_a_severity() {
        // Stack-trace continuations are indented; the entry above them already
        // carries the colour, so they must stay neutral rather than guess.
        assertEquals(LogSeverity.NORMAL, logLineSeverity("  \tat Foo.bar(Foo.kt:1)"))
        assertEquals(LogSeverity.NORMAL, logLineSeverity(""))
        assertEquals(LogSeverity.NORMAL, logLineSeverity("not a log line at all"))
    }

    @Test
    fun the_line_carries_its_utc_offset_so_a_misconfigured_device_is_visible() {
        // 2026-08-11: the beacon phone came back from a flat battery set to
        // Asia/Dubai and wrote ten hours of plausible-looking timestamps that
        // correlated with nothing. An offset in the margin makes that unmissable
        // and keeps a fleet-wide log comparison possible.
        val dubai = formatLogLine(EPOCH_MILLIS, PRIORITY_INFO, "T", "m", zone = ZoneId.of("Asia/Dubai"))
        val denver = formatLogLine(EPOCH_MILLIS, PRIORITY_INFO, "T", "m", zone = ZoneId.of("America/Denver"))

        assertTrue(dubai, dubai.startsWith("2026-08-09 01:03:21.123+04:00 "))
        assertTrue(denver, denver.startsWith("2026-08-08 15:03:21.123-06:00 "))
        // Same instant, different wall clocks -- which is exactly why the offset
        // has to be on the line for the two to be comparable at all.
        assertNotEquals(dubai.substringBefore(' '), denver.substringBefore(' '))
    }

    @Test
    fun the_offset_does_not_disturb_severity_parsing() {
        // logLineSeverity() splits on spaces and reads field 2; gluing the offset
        // to the time keeps that field where it was. Mutation target: put a space
        // before the offset.
        val warn = formatLogLine(EPOCH_MILLIS, PRIORITY_WARN, "T", "m", zone = ZoneId.of("Asia/Dubai"))
        assertEquals(LogSeverity.WARN, logLineSeverity(warn))
    }

    private companion object {
        /** 2026-08-08T21:03:21.123Z — a fixed instant, so the format is deterministic. */
        const val EPOCH_MILLIS = 1_786_223_001_123L
        const val PRIORITY_VERBOSE = 2
        const val PRIORITY_INFO = 4
        const val PRIORITY_WARN = 5
        const val PRIORITY_ERROR = 6
        const val PRIORITY_ASSERT = 7
    }
}
