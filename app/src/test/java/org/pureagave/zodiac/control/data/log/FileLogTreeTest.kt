package org.pureagave.zodiac.control.data.log

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        /** `android.util.Log.ERROR`, inlined so the JVM test needs no Android. */
        const val ERROR_PRIORITY = 6
        const val TAIL = 10
    }
}
