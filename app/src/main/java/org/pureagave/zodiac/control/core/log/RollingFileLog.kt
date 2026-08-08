package org.pureagave.zodiac.control.core.log

import java.io.File
import java.io.IOException

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
                if (file.exists() && file.length() + bytes.size > maxBytes) rotate()
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
     */
    private fun rotate() {
        File(dir, "$baseName.$keep.log").delete()
        for (i in keep - 1 downTo 1) {
            val from = File(dir, "$baseName.$i.log")
            if (from.isFile) {
                val to = File(dir, "$baseName.${i + 1}.log")
                to.delete()
                from.renameTo(to)
            }
        }
        val first = File(dir, "$baseName.1.log")
        first.delete()
        currentFile.renameTo(first)
    }

    companion object {
        const val DEFAULT_BASE_NAME: String = "zodiac"

        /** 512 KB a segment — a few hours of lifecycle logging, cheap to pull. */
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024L

        /** Plus the live file: ~2.5 MB total on disk, worst case. */
        const val DEFAULT_KEEP: Int = 4
    }
}
