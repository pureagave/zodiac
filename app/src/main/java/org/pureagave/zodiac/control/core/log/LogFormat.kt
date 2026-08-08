package org.pureagave.zodiac.control.core.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Priority letters matching `android.util.Log`'s int constants (VERBOSE = 2 …
 * ASSERT = 7). Mapped here rather than importing `android.util.Log` so the
 * formatter stays a pure JVM function and unit-tests without a device.
 */
private const val PRIORITY_VERBOSE = 2
private val PRIORITY_LETTERS = charArrayOf('V', 'D', 'I', 'W', 'E', 'A')

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/**
 * One log line: `2026-08-08 14:03:21.123 I/Gps: NET selected`.
 *
 * Local time, not UTC — these get read next to a laptop clock at camp, and a
 * timezone conversion between the screen and the log is one more thing to get
 * wrong at 3am. [zone] is injectable so the format is testable deterministically.
 *
 * A [stackTrace], when present, follows on its own lines. Line breaks inside
 * [message] are indented so a multi-line entry can't be mistaken for several
 * entries when scanning the file.
 */
fun formatLogLine(
    epochMillis: Long,
    priority: Int,
    tag: String?,
    message: String,
    stackTrace: String? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val time = TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
    val letter = PRIORITY_LETTERS.getOrElse(priority - PRIORITY_VERBOSE) { '?' }
    val head = "$time $letter/${tag ?: "-"}: ${message.indentContinuations()}"
    // The whole trace is indented, its first line included — an unindented
    // "java.lang.Foo: ..." at column 0 reads as a new entry when scanning.
    return if (stackTrace.isNullOrBlank()) head else head + "\n  " + stackTrace.trimEnd().indentContinuations()
}

/** Continuation lines get two spaces so one entry still reads as one entry. */
private fun String.indentContinuations(): String = replace("\n", "\n  ")

/** How a log line should read at a glance in the on-device viewer. */
enum class LogSeverity { NORMAL, WARN, ERROR }

/**
 * Recover the severity of a line written by [formatLogLine], for colouring the
 * viewer. Reads the priority letter out of the fixed `<date> <time> L/Tag:`
 * prefix rather than searching the whole line, so a message that happens to
 * contain " E/" can't recolour itself. Continuation lines (indented stack
 * traces) and anything unparseable come back [LogSeverity.NORMAL] — the entry
 * they belong to already carries the colour.
 */
fun logLineSeverity(line: String): LogSeverity {
    val parts = line.split(' ', limit = 4)
    val priority = parts.getOrNull(2)?.takeIf { it.length >= 2 && it[1] == '/' } ?: return LogSeverity.NORMAL
    return when (priority[0]) {
        'W' -> LogSeverity.WARN
        'E', 'A' -> LogSeverity.ERROR
        else -> LogSeverity.NORMAL
    }
}
