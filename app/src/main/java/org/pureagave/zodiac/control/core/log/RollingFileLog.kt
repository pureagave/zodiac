package org.pureagave.zodiac.control.core.log

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A size-capped, rotating text log on disk. Deliberately plain `java.io` and
 * free of Android types so it unit-tests on the JVM.
 *
 * The point of this file is the postmortem: a tablet misbehaves out on the
 * playa, comes back to camp, and the answer has to already be on it. That
 * shapes every decision here:
 *
 *  - **It never throws.** A cockpit that dies because its logger couldn't write
 *    is strictly worse than one with no logs. Every IO failure is swallowed and
 *    counted in [droppedLines] so the loss is at least visible.
 *  - **It never loses a line silently.** Not throwing is not the same as not
 *    telling. Both ways a line can disappear are counted: [droppedLines] for
 *    failure, [discardedLines] for lines that simply aged out of the window.
 *    On a Fire, `adb pull` of `Android/data` is blocked, so this file read back
 *    on-device is the only postmortem channel there is — a log that quietly
 *    loses lines lies during the one investigation it exists for.
 *  - **Total size is bounded** at `maxBytes × (keep + 1)`. Tablets fill up, and
 *    an unbounded log is a slow way to brick one.
 *  - **Oldest data is what gets dropped**, not newest — you want the frames
 *    around the failure you just saw, not the ones from boot three days ago.
 *
 * Rotation is a rename cascade: `zodiac.log` → `zodiac.1.log` → … → dropped.
 * Callers are expected to be off the main thread; [append] is synchronized so
 * concurrent writers interleave by line rather than by byte.
 */
class RollingFileLog(
    private val dir: File,
    private val baseName: String = DEFAULT_BASE_NAME,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keep: Int = DEFAULT_KEEP,
) {
    private val lock = Any()

    /** Lines lost to IO errors since process start — surfaced, not hidden. */
    @Volatile
    var droppedLines: Long = 0L
        private set

    /**
     * Lines that aged out of the rotation window since process start. Expected
     * loss, not failure — but the reader still has to know it happened, or the
     * oldest line in [tail] reads as "this is where the story starts" when it
     * is really "this is where the story was cut".
     *
     * Counted by scanning the segment about to be deleted, so it stays correct
     * across process restarts (files outlive the counter; a remembered
     * in-memory tally would not). The scan costs one sequential read of
     * [maxBytes] per `maxBytes × keep` bytes written — a rounding error against
     * the writes themselves.
     */
    @Volatile
    var discardedLines: Long = 0L
        private set

    /**
     * Rotations that could not complete because a rename was refused. Non-zero
     * means the size cap could not be honoured and [droppedLines] is climbing
     * for that reason; see the policy note on [rotate].
     */
    @Volatile
    var rotationFailures: Long = 0L
        private set

    /** The file currently being written. */
    val currentFile: File get() = File(dir, "$baseName.log")

    /**
     * Every log file, newest first. The rotated ones are `<base>.1.log` …
     * `<base>.<keep>.log`; a gap in the sequence is skipped rather than
     * treated as an error.
     */
    fun files(): List<File> =
        (listOf(currentFile) + (1..keep).map { File(dir, "$baseName.$it.log") })
            .filter { it.isFile }

    /** Append one line, rotating first if it would overflow the current file. */
    fun append(line: String) {
        synchronized(lock) {
            try {
                if (!dir.isDirectory && !dir.mkdirs()) {
                    droppedLines++
                    return
                }
                val file = currentFile
                val bytes = (line + "\n").toByteArray()
                // Rotate *before* writing, so a single line never pushes the
                // current file past the cap. A line longer than the cap still
                // gets written whole (truncating it would corrupt the record);
                // it simply lands alone in its own segment.
                if (file.exists() && file.length() + bytes.size > maxBytes && !rotate()) {
                    droppedLines++
                    return
                }
                file.appendBytes(bytes)
            } catch (e: IOException) {
                // External storage can vanish mid-write (media unmounted, quota).
                // Nothing useful to do about it here and nowhere to report it to.
                droppedLines++
                lastError = e
            } catch (e: SecurityException) {
                // Permission revoked underneath us — same reasoning.
                droppedLines++
                lastError = e
            }
        }
    }

    /**
     * The last [n] lines across the rotation set, oldest first — for a debug
     * screen, and for tests. Reads whole files, so keep [n] and [maxBytes]
     * modest; this is not a hot path.
     */
    fun tail(n: Int): List<String> {
        if (n <= 0) return emptyList()
        return synchronized(lock) {
            val oldestFirst = files().reversed()
            val out = ArrayList<String>()
            oldestFirst.forEach { f ->
                try {
                    out.addAll(f.readLines())
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (out.size <= n) out else out.subList(out.size - n, out.size).toList()
        }
    }

    /** Drop every segment — used by "clear logs" and by tests. */
    fun clear() {
        synchronized(lock) {
            files().forEach { it.delete() }
        }
    }

    /** Most recent IO/permission failure, for a debug screen to display. */
    @Volatile
    var lastError: Exception? = null
        private set

    /**
     * Shift `<base>.k.log` → `<base>.(k+1).log` from the oldest down, drop what
     * falls off the end, then move the current file into slot 1. Renames rather
     * than copies, so rotation costs nothing even at the size cap.
     *
     * Returns true when the current file is clear to be written into again.
     *
     * **Failure policy.** `renameTo` returning false used to be ignored, which
     * left the writer in an undefined state: the current file stayed put, at
     * the cap, and every later append re-entered rotation, re-deleted slot 1
     * and grew the file anyway — an unbounded log on a tablet that has to
     * survive a week unattended, with nothing anywhere saying so. So:
     *
     *  - A refused rename is never fatal and never throws. A logger that can
     *    kill the cockpit is worse than no logger.
     *  - But it is never ignored either. Each one bumps [rotationFailures] and
     *    sets [lastError]; if it cost a segment, those lines land in
     *    [discardedLines].
     *  - If the *final* rename fails we cannot honour the size cap, so the
     *    caller refuses the write and counts it in [droppedLines]. Refusing is
     *    the honest half of the trade: the bound holds, the loss is on the
     *    record, and because every subsequent append retries the rotation, a
     *    transient failure resumes logging by itself with no restart.
     */
    private fun rotate(): Boolean {
        val oldest = File(dir, "$baseName.$keep.log")
        discardedLines += countLines(oldest)
        oldest.delete()
        for (i in keep - 1 downTo 1) {
            val from = File(dir, "$baseName.$i.log")
            if (from.isFile) {
                val to = File(dir, "$baseName.${i + 1}.log")
                to.delete()
                // A stalled shift strands `from` in its old slot, where the
                // cascade below is about to overwrite it. Bounded, but lossy —
                // say so rather than let a segment evaporate.
                if (!from.renameTo(to)) rotationFailures++
            }
        }
        val first = File(dir, "$baseName.1.log")
        // Normally already renamed away and absent, so this counts zero. It is
        // non-zero exactly when a shift above stalled and this delete is what
        // finally destroys those lines.
        discardedLines += countLines(first)
        first.delete()
        if (currentFile.renameTo(first)) return true
        rotationFailures++
        lastError = IOException("rotate: ${currentFile.name} -> ${first.name} refused")
        return false
    }

    /**
     * Newline count of [file], or 0 if it is absent or unreadable. Streams
     * bytes rather than materialising lines: this runs on a segment at the size
     * cap, and the count is all that is wanted from it.
     */
    private fun countLines(file: File): Long {
        if (!file.isFile) return 0L
        return try {
            file.inputStream().buffered().use { it.countNewlines() }
        } catch (e: IOException) {
            // Unreadable segment: we know we are dropping it but not how much.
            // Better an undercount than a crash — lastError says why.
            lastError = e
            0L
        } catch (e: SecurityException) {
            lastError = e
            0L
        }
    }

    companion object {
        const val DEFAULT_BASE_NAME: String = "zodiac"

        /** 512 KB a segment — a few hours of lifecycle logging, cheap to pull. */
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024L

        /** Plus the live file: ~2.5 MB total on disk, worst case. */
        const val DEFAULT_KEEP: Int = 4
    }
}

private const val COUNT_BUFFER_BYTES = 8 * 1024
private val NEWLINE_BYTE = '\n'.code.toByte()

/** Newlines in the remainder of this stream. Consumes it; caller closes it. */
private fun InputStream.countNewlines(): Long {
    val buffer = ByteArray(COUNT_BUFFER_BYTES)
    var lines = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        lines += (0 until read).count { buffer[it] == NEWLINE_BYTE }
    }
    return lines
}
