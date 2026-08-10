package org.pureagave.zodiac.control.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RollingFileLogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun log(
        maxBytes: Long = 200L,
        keep: Int = 2,
        dir: File = tempFolder.root,
    ) = RollingFileLog(dir = dir, baseName = "t", maxBytes = maxBytes, keep = keep)

    @Test
    fun appends_lines_in_order() {
        val log = log()

        log.append("one")
        log.append("two")

        assertEquals(listOf("one", "two"), log.currentFile.readLines())
    }

    @Test
    fun creates_the_directory_when_it_is_missing() {
        // getExternalFilesDir("logs") exists on a healthy device, but the very
        // first launch after a wipe is exactly when we most want the log.
        val nested = File(tempFolder.root, "a/b/logs")
        val log = log(dir = nested)

        log.append("hello")

        assertTrue(log.currentFile.isFile)
        assertEquals(listOf("hello"), log.currentFile.readLines())
    }

    @Test
    fun rotates_once_the_cap_would_be_exceeded() {
        val log = log(maxBytes = 40L, keep = 2)

        repeat(ROTATION_LINES) { log.append("line-$it-padding-padding") }

        val files = log.files()
        assertTrue("rotated segments exist: ${files.map { it.name }}", files.size > 1)
        // Cap is a promise: nothing may exceed it except a single oversized line.
        files.forEach { assertTrue("${it.name} is ${it.length()} B", it.length() <= OVERSIZE_ALLOWANCE) }
    }

    @Test
    fun total_size_stays_bounded_by_max_times_keep_plus_one() {
        val max = 60L
        val keep = 2
        val log = log(maxBytes = max, keep = keep)

        repeat(FLOOD_LINES) { log.append("0123456789012345678901234567890123456789 #$it") }

        val total = log.files().sumOf { it.length() }
        // The bound the class advertises; the last line may overshoot one
        // segment slightly, hence the allowance.
        assertTrue("total $total B", total <= max * (keep + 1) + OVERSIZE_ALLOWANCE)
        assertEquals("no more segments than promised", keep + 1, log.files().size)
    }

    @Test
    fun rotation_discards_oldest_and_keeps_newest() {
        // The whole reason to log: you want the lines around the failure you
        // just saw, not the ones from boot three days ago.
        val log = log(maxBytes = 30L, keep = 1)

        repeat(FLOOD_LINES) { log.append("entry-$it") }

        val all = log.files().flatMap { it.readLines() }
        assertTrue("newest line retained", all.any { it == "entry-${FLOOD_LINES - 1}" })
        assertTrue("oldest line discarded", all.none { it == "entry-0" })
    }

    @Test
    fun tail_reads_across_segments_oldest_first() {
        val log = log(maxBytes = 30L, keep = 3)

        repeat(TAIL_LINES) { log.append("n$it") }
        val tail = log.tail(3)

        assertEquals(listOf("n${TAIL_LINES - 3}", "n${TAIL_LINES - 2}", "n${TAIL_LINES - 1}"), tail)
    }

    @Test
    fun tail_of_more_lines_than_exist_returns_everything() {
        val log = log()
        log.append("a")
        log.append("b")

        assertEquals(listOf("a", "b"), log.tail(TAIL_LINES))
        assertEquals(emptyList<String>(), log.tail(0))
    }

    @Test
    fun a_line_longer_than_the_cap_is_written_whole() {
        // Truncating would corrupt the record. It gets its own segment instead.
        val log = log(maxBytes = 20L, keep = 2)
        val long = "x".repeat(LONG_LINE)

        log.append("short")
        log.append(long)

        assertTrue("oversized line survives intact", log.files().flatMap { it.readLines() }.contains(long))
    }

    @Test
    fun an_unwritable_directory_counts_drops_instead_of_throwing() {
        // A logger that can kill the cockpit is worse than no logger.
        val blocked = File(tempFolder.root, "blocked")
        blocked.writeText("I am a file, not a directory")
        val log = log(dir = blocked)

        log.append("never lands")

        assertEquals(1L, log.droppedLines)
    }

    @Test
    fun rotation_counts_the_lines_it_ages_out() {
        // Conservation, not arithmetic: every line handed to the log is either
        // still on disk or counted as aged out. Anything else means the log
        // lost lines and said nothing — the one failure mode that makes an
        // on-device postmortem worthless.
        val log = log(maxBytes = 40L, keep = 1)

        repeat(FLOOD_LINES) { log.append("entry-$it") }

        val retained = log.files().sumOf { it.readLines().size }.toLong()
        assertTrue("rotation actually happened", log.discardedLines > 0)
        assertEquals("nothing lost to failure", 0L, log.droppedLines)
        assertEquals("written = retained + aged out", FLOOD_LINES.toLong(), retained + log.discardedLines)
    }

    @Test
    fun nothing_is_counted_as_aged_out_before_the_first_rotation() {
        // The other direction: a count that over-reports is just as much a lie.
        val log = log(maxBytes = 10_000L)

        repeat(TAIL_LINES) { log.append("small-$it") }

        assertEquals(0L, log.discardedLines)
        assertEquals(TAIL_LINES, log.currentFile.readLines().size)
    }

    @Test
    fun the_aged_out_count_is_measured_from_disk_not_remembered() {
        // A tablet up for days rotates away lines written before the last
        // process restart. A tally kept only in memory would report those as
        // never having existed.
        val first = log(maxBytes = 40L, keep = 1)
        repeat(FLOOD_LINES) { first.append("entry-$it") }

        val second = log(maxBytes = 40L, keep = 1)
        assertEquals("a fresh instance starts from zero", 0L, second.discardedLines)
        repeat(FLOOD_LINES) { second.append("later-$it") }

        val retained = second.files().sumOf { it.readLines().size }.toLong()
        assertTrue("the second instance aged out the first's lines", second.discardedLines > 0)
        assertEquals(
            "every line either survives or is accounted for across the restart",
            2L * FLOOD_LINES,
            retained + first.discardedLines + second.discardedLines,
        )
    }

    @Test
    fun a_refused_rotation_is_surfaced_and_the_line_is_not_pretended_written() {
        val log = log(maxBytes = 40L, keep = 1)
        log.append(SHORT_LINE)
        val beforeFailure = log.currentFile.readLines()
        blockRotationSlotOne()

        repeat(BLOCKED_LINES) { log.append("cannot be rotated in $it") }

        // Behaviour first, counters second: the point is not that a field moved
        // but that the writer stopped instead of growing a file it can no
        // longer rotate.
        assertEquals("no line claims to have landed", beforeFailure, log.currentFile.readLines())
        assertTrue("the size cap still holds", log.currentFile.length() <= 40L)
        assertTrue("rename failure recorded", log.rotationFailures > 0)
        assertTrue("refused lines counted, not swallowed", log.droppedLines > 0)
        assertNotNull("and a reason a human can read", log.lastError)
    }

    @Test
    fun logging_resumes_by_itself_once_rotation_can_succeed_again() {
        // Refusing to write is only defensible if it is not permanent: a media
        // remount must bring the log back with no restart.
        val log = log(maxBytes = 40L, keep = 1)
        log.append(SHORT_LINE)
        blockRotationSlotOne()
        log.append("refused while blocked")
        val failuresWhileBlocked = log.rotationFailures
        assertTrue(failuresWhileBlocked > 0)

        File(tempFolder.root, "t.1.log").deleteRecursively()
        log.append("lands after recovery")

        assertEquals("no new failure once the path cleared", failuresWhileBlocked, log.rotationFailures)
        assertTrue("the line is really on disk", log.files().flatMap { it.readLines() }.contains("lands after recovery"))
    }

    /**
     * Stage a rotation failure the same way the filesystem would: POSIX rename
     * onto a non-empty directory is refused, and so is deleting one.
     */
    private fun blockRotationSlotOne() {
        val blocker = File(tempFolder.root, "t.1.log")
        assertTrue(blocker.mkdirs())
        File(blocker, "occupant").writeText("x")
    }

    @Test
    fun clear_removes_every_segment() {
        val log = log(maxBytes = 30L, keep = 2)
        repeat(FLOOD_LINES) { log.append("entry-$it") }
        assertTrue(log.files().isNotEmpty())

        log.clear()

        assertEquals(emptyList<File>(), log.files())
    }

    @Test
    fun concurrent_writers_interleave_by_line_not_by_byte() {
        // Sources, the gateway and the vision feed all log from their own
        // coroutines; a half-written line is an unreadable postmortem.
        val log = log(maxBytes = 1_000_000L, keep = 1)
        val pool = Executors.newFixedThreadPool(THREADS)
        val start = CountDownLatch(1)
        val done = CountDownLatch(THREADS)

        repeat(THREADS) { t ->
            pool.execute {
                start.await()
                repeat(PER_THREAD) { i -> log.append("thread-$t-line-$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        pool.shutdown()

        val lines = log.currentFile.readLines()
        assertEquals(THREADS * PER_THREAD, lines.size)
        assertTrue("every line intact", lines.all { it.matches(Regex("""thread-\d+-line-\d+""")) })
    }

    private companion object {
        const val ROTATION_LINES = 10
        const val FLOOD_LINES = 40
        const val TAIL_LINES = 12
        const val LONG_LINE = 100
        const val BLOCKED_LINES = 5
        const val SHORT_LINE = "first line that fits"
        const val OVERSIZE_ALLOWANCE = 200L
        const val THREADS = 8
        const val PER_THREAD = 200
        const val AWAIT_SECONDS = 10L
    }
}
