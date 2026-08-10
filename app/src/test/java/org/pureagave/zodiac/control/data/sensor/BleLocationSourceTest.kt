package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.sensor.LocationSourceError
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle tests for the BLE source: the same bug shape already found and
 * fixed in [NetworkLocationSource] (AUDIT-2026-08-09 C5) — an unguarded
 * `start()` that spawns a second listener, and a `stop()` that cancels without
 * joining, letting an in-flight read repopulate state *after* the teardown.
 *
 * Both matter on the vehicle: the source-selector chip is tappable as fast as
 * a finger moves, and `CockpitViewModel` init calls `select(saved)` — which
 * already starts the source — then `start()` again unconditionally.
 *
 * Everything but the join test runs on one [StandardTestDispatcher] shared by
 * the test body, the source's listener and its watchdog, so the orderings under
 * test are exact rather than raced. NB: never `advanceUntilIdle()` here — the
 * freshness watchdog is an infinite `while (isActive)` loop, so it never
 * returns (same reasoning as `SystemLocationSourceTest.settle`). The one
 * exception is
 * [stop_waits_for_a_line_already_in_flight_so_disconnected_is_the_last_word],
 * which needs real threads for the reason documented on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleLocationSourceTest {
    // Canonical valid GGA: 4807.038N 01131.000E → 48.1173, 11.5167 (checksum *47).
    private val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

    private fun TestScope.source(
        handle: FakeSppHandle,
        dispatcher: TestDispatcher,
    ) = BleLocationSource(
        handle = handle,
        scope = backgroundScope,
        ioDispatcher = dispatcher,
        nowMs = { testScheduler.currentTime },
    )

    // --- start() must be idempotent ------------------------------------------

    @Test
    fun a_second_start_while_running_opens_no_second_link() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). NB the runCurrent() between the two starts is load
            // bearing — without it the first listener never gets to run, so
            // even the unguarded code would only ever open one link and this
            // would pass for the wrong reason.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()
            source.start()
            runCurrent()

            assertEquals("a redundant start() must not open a second SPP socket", 1, handle.links.size)
            assertEquals("the one link must still be connected", 1, handle.links[0].connectCalls)
            assertFalse("the live link must not have been abandoned open", handle.links[0].isClosed)
        }

    @Test
    fun a_redundant_start_does_not_reset_a_live_fix_to_searching() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). The state half of the same bug: relaunching the listener
            // drops a working receiver back to Searching, which on the vehicle
            // is the cockpit losing its position for a beat on every launch.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            handle.links[0].deliver(validGga)
            runCurrent()
            assertTrue("precondition: a live fix", source.state.value is LocationSourceState.Active)

            source.start() // exactly what CockpitViewModel init does after select(saved)
            runCurrent()

            assertTrue(
                "a redundant start() must not discard a live fix; state=${source.state.value}",
                source.state.value is LocationSourceState.Active,
            )
        }

    @Test
    fun start_after_the_listener_died_reconnects() =
        runTestOnOneDispatcher { dispatcher ->
            // The idempotence guard must key off a *live* job, not a "started"
            // flag: a listener that ended (adapter off, receiver unplugged) has
            // to be retryable, or one bad attempt wedges the source until the
            // process restarts.
            val handle = FakeSppHandle(adapterOn = false)
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            assertTrue(source.state.value is LocationSourceState.Error)
            assertEquals(0, handle.links.size)

            handle.adapterOn = true
            source.start()
            runCurrent()

            assertEquals("a dead listener must not block a retry", 1, handle.links.size)
        }

    @Test
    fun stop_then_start_opens_a_fresh_link() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            source.stop()
            source.start()
            runCurrent()

            assertEquals(2, handle.links.size)
            assertTrue("the first link must have been closed by stop()", handle.links[0].isClosed)
            assertFalse("the fresh link must be open", handle.links[1].isClosed)
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    // --- stop() must join, not just cancel -----------------------------------

    @Test
    fun stop_waits_for_a_line_already_in_flight_so_disconnected_is_the_last_word() =
        runBlocking {
            // Mutation: drop the `listener?.join()` from stop() (i.e. cancel
            // only, as the code shipped).
            //
            // Cancellation is cooperative and a blocking socket read is not a
            // cancellation point, so a line the OS already handed over is
            // parsed and published *after* stop() cleared the state — leaving a
            // stopped source advertising a live fix that nothing will ever
            // refresh or demote (stop() killed the freshness watchdog too).
            //
            // Real threads on purpose, unlike every other test here: on a
            // single-threaded test scheduler `stop()`'s *other* suspension
            // point (joining the watchdog) drains the queued listener anyway,
            // so a missing listener join is invisible — verified, the mutation
            // above passes when this test is written in virtual time. The race
            // is only real when the listener is on its own thread.
            val handle = FakeSppHandle(readDelayMs = IN_FLIGHT_MS)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = BleLocationSource(handle = handle, scope = scope, ioDispatcher = Dispatchers.IO)
            try {
                source.start()
                assertTrue(
                    "precondition: the listener is blocked in a read",
                    waitUntil(TIMEOUT_MS) { handle.links.size == 1 && handle.links[0].readInFlight },
                )
                // The receiver hands over a line, and the operator taps a
                // different source chip in the same breath.
                handle.links[0].deliver(validGga)
                source.stop()

                assertSame(
                    "stop() must not report Disconnected while a line is still in flight",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
                Thread.sleep(IN_FLIGHT_MS * 3)
                assertSame(
                    "an in-flight line resurrected a stopped source as Active",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_closes_the_link_before_joining_so_it_cannot_hang_on_a_quiet_receiver() =
        runTestOnOneDispatcher(timeoutSeconds = 10) { dispatcher ->
            // Mutation: delete the `link?.close()` from stop(), or move it
            // after the join. An SPP read blocks with no timeout, so nothing
            // else ever unblocks the listener — stop() then never returns, and
            // it holds RoutedLocationSource's select() mutex while it doesn't.
            // The failure mode is a hang, so this test carries its own timeout.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            assertTrue("precondition: the listener is blocked in a read", handle.links[0].readInFlight)

            source.stop()

            assertTrue("stop() must close the socket it opened", handle.links[0].isClosed)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    @Test
    fun stop_without_a_prior_start_is_safe() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)

            source.stop()

            assertEquals(0, handle.links.size)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    // --- the error path must release what it acquired ------------------------

    @Test
    fun a_failed_connect_closes_the_link_it_opened() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block in
            // runConnection. An SPP socket left open after a failed connect is
            // not reclaimed until the process dies, and Android will not hand
            // out a second link to the same device — so one out-of-range
            // attempt made the receiver unusable for the rest of the burn.
            val handle = FakeSppHandle(connectFailure = IOException("read failed, socket might closed"))
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()

            val state = source.state.value
            assertTrue("a failed connect must surface as an error; state=$state", state is LocationSourceState.Error)
            assertEquals(LocationSourceError.IO_ERROR, (state as LocationSourceState.Error).kind)
            assertTrue("a failed connect must not leak its socket", handle.links[0].isClosed)
        }

    @Test
    fun an_ended_stream_closes_the_link() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block. A
            // receiver that ends the stream (powered off, out of range long
            // enough for the stack to give up) leaves readLine() returning
            // null — the one exit path that never threw, so the old code fell
            // out of the loop and returned with the socket still held.
            val handle = FakeSppHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()

            handle.links[0].endStream()
            runCurrent()

            assertTrue("end of stream must release the socket", handle.links[0].isClosed)
        }

    @Test
    fun start_without_permission_opens_nothing() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeSppHandle(permission = false)
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()

            assertEquals(0, handle.links.size)
            val state = source.state.value
            assertTrue(state is LocationSourceState.Error)
            assertEquals(LocationSourceError.PERMISSION_DENIED, (state as LocationSourceState.Error).kind)
        }

    /**
     * One [StandardTestDispatcher] for the test body, the listener and the
     * watchdog. Sharing the *instance* matters: `stop()` closes the link inside
     * `withContext(ioDispatcher)`, and a second dispatcher would make that a
     * real dispatch, handing the in-flight listener a chance to run before
     * `stop()` reaches its join — which would hide the very bug
     * [stop_waits_for_a_line_already_in_flight_so_disconnected_is_the_last_word]
     * exists to catch.
     */
    private fun runTestOnOneDispatcher(
        timeoutSeconds: Int = 30,
        body: suspend TestScope.(TestDispatcher) -> Unit,
    ) = StandardTestDispatcher().let { dispatcher ->
        runTest(dispatcher, timeout = timeoutSeconds.seconds) { body(dispatcher) }
    }

    private inline fun waitUntil(
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(POLL_MS)
        }
        return cond()
    }

    private companion object {
        /**
         * How long the fake holds a line it has already been handed before the
         * source publishes it. In production that window is microseconds wide
         * — the gap between a blocking read returning and the parse landing —
         * and widening it here is what makes the race observable rather than
         * lucky.
         */
        const val IN_FLIGHT_MS: Long = 150L
        const val TIMEOUT_MS: Long = 4_000L
        const val POLL_MS: Long = 10L
    }
}

/**
 * A paired receiver that behaves like a real SPP link: [readLine] blocks until
 * a line arrives or the socket is closed, and — the part that matters — it
 * suspends via [suspendCoroutine], not `suspendCancellableCoroutine`, because a
 * blocking socket read does *not* abort when the coroutine is cancelled.
 * Modelling it as cancellable would quietly test a bug shape the real source
 * cannot have, and every stop() test would pass for the wrong reason.
 */
private class FakeSppLink(
    private val connectFailure: Exception?,
    /**
     * Null: [readLine] suspends on the test scheduler, so virtual-time tests
     * stay deterministic. Set: [readLine] blocks a real thread and then holds
     * the line for this long before returning it — the only way to observe
     * whether `stop()` really waits for the listener, since a single-threaded
     * test scheduler runs the queued listener during `stop()`'s other joins
     * regardless.
     */
    private val readDelayMs: Long?,
) : BluetoothSppLink {
    var connectCalls: Int = 0
        private set

    private var waiting: Continuation<String?>? = null

    @Volatile private var blocked = false

    @Volatile private var closed = false

    private val handoff = LinkedBlockingQueue<String>()

    /** A real close is idempotent, so "was it released" is the question, not how often. */
    val isClosed: Boolean get() = closed

    val readInFlight: Boolean get() = blocked || waiting != null

    override suspend fun connect() {
        connectCalls++
        connectFailure?.let { throw it }
    }

    override suspend fun readLine(): String? =
        if (readDelayMs == null) {
            if (closed) null else suspendCoroutine { cont -> waiting = cont }
        } else {
            blockingRead(readDelayMs)
        }

    private fun blockingRead(delayMs: Long): String? {
        blocked = true
        val item = handoff.take()
        blocked = false
        Thread.sleep(delayMs)
        return item.takeIf { it != EOF }
    }

    override fun close() {
        closed = true
        // Closing the socket is what unblocks a blocked read — the real one
        // returns end-of-stream or throws; either ends the loop.
        resumeWith(null)
    }

    /** Hand the blocked read a line, as the receiver would. */
    fun deliver(line: String) = resumeWith(line)

    /** The receiver ends the stream without an error (powered off, out of range). */
    fun endStream() = resumeWith(null)

    private fun resumeWith(line: String?) {
        if (readDelayMs != null) {
            handoff.put(line ?: EOF)
            return
        }
        val cont = waiting ?: return
        waiting = null
        cont.resume(line)
    }

    private companion object {
        /** Sentinel for end-of-stream on the blocking hand-off queue. */
        const val EOF: String = " "
    }
}

private class FakeSppHandle(
    private val permission: Boolean = true,
    var adapterOn: Boolean = true,
    private val paired: List<String> = listOf("Garmin GLO 1234"),
    private val connectFailure: Exception? = null,
    private val readDelayMs: Long? = null,
) : BluetoothSppHandle {
    val links = mutableListOf<FakeSppLink>()

    override fun hasConnectPermission(): Boolean = permission

    override fun isAdapterEnabled(): Boolean = adapterOn

    override fun pairedDeviceNames(): List<String> = paired

    override fun createLink(deviceName: String): BluetoothSppLink = FakeSppLink(connectFailure, readDelayMs).also { links += it }
}
