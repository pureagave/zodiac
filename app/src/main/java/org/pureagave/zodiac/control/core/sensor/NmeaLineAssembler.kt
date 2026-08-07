package org.pureagave.zodiac.control.core.sensor

/**
 * Reassembles NMEA sentences from a byte stream that arrives in arbitrary
 * chunks.
 *
 * A serial GPS hands you whatever happened to be in the UART buffer when you
 * read it — a sentence is routinely split across two reads, and a single read
 * routinely contains several. So line assembly needs its own state, and getting
 * it wrong silently drops or corrupts fixes.
 *
 * **Bounded on purpose.** A naive accumulator grows forever if newlines stop
 * arriving, which is not hypothetical on a vehicle: a wrong baud rate, noise on
 * a long cable, or a half-connected receiver all produce bytes with no line
 * structure at all. Over a night's run that is unbounded memory growth in a
 * process that must not die. NMEA 0183 caps a sentence at 82 characters
 * including delimiters, so anything longer is definitionally not a sentence —
 * it is discarded up to the next newline rather than emitted as garbage or
 * accumulated.
 *
 * Pure and framework-free, so both the USB and BLE sources can share it and it
 * is testable without any hardware.
 */
class NmeaLineAssembler(
    private val maxLineLength: Int = MAX_LINE_LENGTH,
) {
    private val buffer = StringBuilder(PREALLOC)

    /** True while discarding an over-long run of bytes up to the next newline. */
    private var discarding = false

    /** Over-long runs dropped since construction — a wrong baud rate shows here. */
    var droppedOverlong: Int = 0
        private set

    /**
     * Feed [count] bytes from [bytes] and return whatever complete lines that
     * produced — usually none or one, occasionally several.
     *
     * `\r` is ignored rather than treated as a terminator, so CRLF and bare LF
     * both work. Empty lines are not emitted; a blank line carries no sentence
     * and the parser would only reject it.
     */
    fun append(
        bytes: ByteArray,
        count: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until minOf(count, bytes.size)) {
            val ch = bytes[i].toInt().toChar()
            when (ch) {
                '\r' -> Unit
                '\n' -> takeCompleteLine()?.let(out::add)
                else -> accumulate(ch)
            }
        }
        return out
    }

    /**
     * End the current line: return it if it was a real one, or null if it was
     * blank or the tail of a discarded over-long run. Either way the buffer is
     * cleared and normal accumulation resumes.
     */
    private fun takeCompleteLine(): String? {
        val line = if (discarding || buffer.isEmpty()) null else buffer.toString()
        buffer.setLength(0)
        discarding = false
        return line
    }

    private fun accumulate(ch: Char) {
        if (discarding) return
        if (buffer.length >= maxLineLength) {
            // Longer than any legal sentence — drop the whole run rather than
            // grow without bound or emit a truncated line that might parse as
            // something plausible.
            buffer.setLength(0)
            discarding = true
            droppedOverlong++
            return
        }
        buffer.append(ch)
    }

    /** Forget any partial line — call when a connection drops and reconnects. */
    fun reset() {
        buffer.setLength(0)
        discarding = false
    }

    companion object {
        /**
         * NMEA 0183 allows 82 characters including the leading `$` and the
         * trailing CRLF. The limit here is deliberately generous — some
         * receivers emit longer proprietary sentences, and our own `$Z*`
         * channels are ours to extend — while still bounding growth.
         */
        const val MAX_LINE_LENGTH: Int = 256
        private const val PREALLOC: Int = 96
    }
}
