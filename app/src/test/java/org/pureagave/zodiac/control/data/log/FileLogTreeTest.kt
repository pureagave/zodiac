package org.pureagave.zodiac.control.data.log

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.pureagave.zodiac.control.core.log.RollingFileLog
import java.io.File

/**
 * The pre-file buffer's overflow has to be *counted*, not silently swallowed —
 * it is the one loss the [RollingFileLog] counters can never see (see the
 * [FileLogTree] header for why a hand-rolled deque replaced a `DROP_OLDEST`
 * channel that reported success on every drop).
 *
 * Determinism comes from injecting the drain's dispatcher and, in the overflow
 * case, never advancing it: with nothing draining, the buffer is *guaranteed*
 * full, so overflow is forced rather than raced. The buffer depth is shrunk to
 * 4 so the burst is a handful of lines, not 256.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileLogTreeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun rollingLog(): RollingFileLog = RollingFileLog(dir = File(tempFolder.root, "logs"), baseName = "t")

    private fun TestScope.tree(
        log: RollingFileLog,
        dispatcher: TestDispatcher,
        bufferLines: Int,
    ) = FileLogTree(
        log = log,
        scope = backgroundScope,
        bufferLines = bufferLines,
        ioDispatcher = dispatcher,
        clock = { 0L },
    )

    @Test
    fun overflow_past_the_buffer_cap_is_counted() =
        runOnOneDispatcher { dispatcher ->
            val tree = tree(rollingLog(), dispatcher, bufferLines = 4)

            // The drain is never advanced, so nothing leaves the buffer: the
            // 5th and 6th lines shed the two oldest to stay under the cap.
            // Named args bind to the tree's own log(), not Timber's vararg one.
            repeat(6) { i -> tree.log(priority = ERROR_PRIORITY, tag = "t", message = "line $i", t = null) }

            // A DROP_OLDEST channel would have dropped exactly these two and
            // reported success on every send — the loss this counter exists to
            // stop being invisible.
            assertEquals(2L, tree.droppedBeforeWrite)
        }

    @Test
    fun overflow_sheds_the_oldest_lines_and_keeps_the_newest() =
        runOnOneDispatcher { dispatcher ->
            val log = rollingLog()
            val tree = tree(log, dispatcher, bufferLines = 4)

            // Drain held still: the six pushes cap the buffer synchronously and
            // shed the two OLDEST (line 0, line 1). Only then let it drain, so
            // what reaches the file is exactly the survivors.
            repeat(6) { i -> tree.log(priority = ERROR_PRIORITY, tag = "t", message = "line $i", t = null) }
            runCurrent()

            // The whole documented drop policy: the lines nearest the problem
            // (newest) are the ones worth keeping. A mutation to removeLast()
            // would shed 4 and 5 and strand 0..3 here, with the count unchanged
            // — which is why the count-only overflow test can't see it.
            val kept = log.tail(TAIL).map { it.substringAfter(": ") }
            assertEquals(listOf("line 2", "line 3", "line 4", "line 5"), kept)
            assertEquals(2L, tree.droppedBeforeWrite)
        }

    @Test
    fun log_blocking_writes_straight_through_bypassing_the_drain() =
        runOnOneDispatcher { dispatcher ->
            val log = rollingLog()
            val tree = tree(log, dispatcher, bufferLines = 4)

            // No runCurrent, so the drain never runs. logBlocking is the
            // uncaught-exception path: the process is about to die, so the entry
            // that matters must hit the file inline rather than wait in a buffer
            // no one will ever drain.
            tree.logBlocking(priority = ERROR_PRIORITY, tag = "t", message = "dying breath", t = null)

            val tail = log.tail(TAIL)
            assertEquals(1, tail.size)
            assertTrue("tail=$tail", tail.single().contains("dying breath"))
            assertEquals(0L, tree.droppedBeforeWrite)
        }

    @Test
    fun is_loggable_gates_on_min_priority() =
        runOnOneDispatcher { dispatcher ->
            // Default floor is Log.INFO: VERBOSE/DEBUG are kept off the flash so
            // a week of playa lifecycle logging does not fill the tablet.
            val atInfo = tree(rollingLog(), dispatcher, bufferLines = 4)
            assertTrue("INFO meets the default floor", atInfo.isLoggable("t", INFO_PRIORITY))
            assertTrue("ERROR is above it", atInfo.isLoggable("t", ERROR_PRIORITY))
            assertFalse("DEBUG is below it", atInfo.isLoggable("t", DEBUG_PRIORITY))

            // And the floor is honoured, not hard-coded: raising it drops INFO.
            val atWarn =
                FileLogTree(
                    log = rollingLog(),
                    scope = backgroundScope,
                    minPriority = WARN_PRIORITY,
                    bufferLines = 4,
                    ioDispatcher = dispatcher,
                    clock = { 0L },
                )
            assertFalse("a raised floor drops INFO", atWarn.isLoggable("t", INFO_PRIORITY))
            assertTrue("but keeps WARN", atWarn.isLoggable("t", WARN_PRIORITY))
        }

    @Test
    fun the_drain_re_arms_after_going_idle_for_a_later_burst() =
        runOnOneDispatcher { dispatcher ->
            val log = rollingLog()
            val tree = tree(log, dispatcher, bufferLines = 64)

            repeat(3) { i -> tree.log(priority = ERROR_PRIORITY, tag = "t", message = "first $i", t = null) }
            runCurrent()
            assertEquals(3, log.tail(TAIL).size)

            // A burst that arrives after the drain has gone back to waiting must
            // wake it again — the conflated doorbell re-arms on the next
            // receive(). A drain that only ever ran once would strand these.
            repeat(3) { i -> tree.log(priority = ERROR_PRIORITY, tag = "t", message = "second $i", t = null) }
            runCurrent()
            assertEquals(6, log.tail(TAIL).size)
        }

    @Test
    fun lines_within_the_cap_all_reach_the_file_and_none_count_as_dropped() =
        runOnOneDispatcher { dispatcher ->
            val log = rollingLog()
            val tree = tree(log, dispatcher, bufferLines = 4)

            repeat(3) { i -> tree.log(priority = ERROR_PRIORITY, tag = "t", message = "line $i", t = null) }
            runCurrent() // let the drain run to completion

            // Positive control: the happy path must neither lose lines nor
            // over-count. Guards against a counter that fires on every push.
            assertEquals(0L, tree.droppedBeforeWrite)
            val tail = log.tail(TAIL)
            assertEquals(3, tail.size)
            assertTrue("tail=$tail", tail.first().contains("line 0"))
            assertTrue("tail=$tail", tail.last().contains("line 2"))
        }

    // Same one-dispatcher shape as the location-source tests: the drain lives in
    // backgroundScope so runTest cancels it rather than hanging on its endless
    // receive loop, and ioDispatcher is the very dispatcher runTest steps.
    private fun runOnOneDispatcher(body: suspend TestScope.(TestDispatcher) -> Unit) =
        StandardTestDispatcher().let { dispatcher -> runTest(dispatcher) { body(dispatcher) } }

    private companion object {
        // `android.util.Log` int constants, inlined so the JVM test needs no Android.
        const val DEBUG_PRIORITY = 3
        const val INFO_PRIORITY = 4
        const val WARN_PRIORITY = 5
        const val ERROR_PRIORITY = 6
        const val TAIL = 10
    }
}
