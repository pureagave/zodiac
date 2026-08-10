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
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle tests for the USB source — same bug shape and same reasoning as
 * [BleLocationSourceTest]; see its header for why everything shares one
 * [StandardTestDispatcher] and why `advanceUntilIdle()` must never be used here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsbLocationSourceTest {
    // Canonical valid GGA: 4807.038N 01131.000E → 48.1173, 11.5167 (checksum *47).
    private val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"

    private fun TestScope.source(
        handle: FakeUsbHandle,
        dispatcher: TestDispatcher,
    ) = UsbLocationSource(
        handle = handle,
        scope = backgroundScope,
        ioDispatcher = dispatcher,
        nowMs = { testScheduler.currentTime },
    )

    // --- start() must be idempotent ------------------------------------------

    @Test
    fun a_second_start_while_running_opens_no_second_port() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). NB the runCurrent() between the two starts is load
            // bearing — without it the first listener never gets to run, so
            // even the unguarded code would only ever attach once and this
            // would pass for the wrong reason.
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()
            source.start()
            runCurrent()

            assertEquals("a redundant start() must not attach a second port", 1, handle.links.size)
            assertEquals("the one port must still be open", 1, handle.links[0].openCalls)
            assertFalse("the live port must not have been abandoned open", handle.links[0].isClosed)
        }

    @Test
    fun a_redundant_start_does_not_reset_a_live_fix_to_searching() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `job?.isActive == true` guard at the top of
            // start(). The state half of the same bug: relaunching the listener
            // drops a working dongle back to Searching, which on the vehicle is
            // the cockpit losing its position for a beat on every launch.
            val handle = FakeUsbHandle()
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
    fun start_after_the_listener_died_reattaches() =
        runTestOnOneDispatcher { dispatcher ->
            // The idempotence guard must key off a *live* job, not a "started"
            // flag: a listener that ended (dongle unplugged, permission denied)
            // has to be retryable, or one bad attempt wedges the source until
            // the process restarts.
            val handle = FakeUsbHandle(unavailable = UsbAttach.Unavailable("no dongle", LocationSourceError.NO_DEVICE_FOUND))
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            assertTrue(source.state.value is LocationSourceState.Error)
            assertEquals(0, handle.links.size)

            handle.unavailable = null
            source.start()
            runCurrent()

            assertEquals("a dead listener must not block a retry", 1, handle.links.size)
        }

    @Test
    fun stop_then_start_attaches_a_fresh_port() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            source.stop()
            source.start()
            runCurrent()

            assertEquals(2, handle.links.size)
            assertTrue("the first port must have been closed by stop()", handle.links[0].isClosed)
            assertFalse("the fresh port must be open", handle.links[1].isClosed)
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    // --- stop() must join, not just cancel -----------------------------------

    @Test
    fun stop_waits_for_bytes_already_in_flight_so_disconnected_is_the_last_word() =
        runBlocking {
            // Mutation: drop the `listener?.join()` from stop() (i.e. cancel
            // only, as the code shipped).
            //
            // Cancellation is cooperative and a blocking bulk read is not a
            // cancellation point, so bytes the driver already handed over are
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
            val handle = FakeUsbHandle(readDelayMs = IN_FLIGHT_MS)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = UsbLocationSource(handle = handle, scope = scope, ioDispatcher = Dispatchers.IO)
            try {
                source.start()
                assertTrue(
                    "precondition: the listener is blocked in a read",
                    waitUntil(TIMEOUT_MS) { handle.links.size == 1 && handle.links[0].readInFlight },
                )
                // The dongle hands over a sentence, and the operator taps a
                // different source chip in the same breath.
                handle.links[0].deliver(validGga)
                source.stop()

                assertSame(
                    "stop() must not report Disconnected while bytes are still in flight",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
                Thread.sleep(IN_FLIGHT_MS * 3)
                assertSame(
                    "in-flight bytes resurrected a stopped source as Active",
                    LocationSourceState.Disconnected,
                    source.state.value,
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun stop_closes_the_port_before_joining_so_it_cannot_hang_on_a_quiet_dongle() =
        runTestOnOneDispatcher(timeoutSeconds = 10) { dispatcher ->
            // Mutation: delete the `link?.close()` from stop(), or move it
            // after the join. The read is a blocking bulk transfer, so nothing
            // else unblocks the listener — stop() then never returns, and it
            // holds RoutedLocationSource's select() mutex while it doesn't.
            // The failure mode is a hang, so this test carries its own timeout.
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()
            assertTrue("precondition: the listener is blocked in a read", handle.links[0].readInFlight)

            source.stop()

            assertTrue("stop() must close the port it opened", handle.links[0].isClosed)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    @Test
    fun stop_without_a_prior_start_is_safe() =
        runTestOnOneDispatcher { dispatcher ->
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)

            source.stop()

            assertEquals(0, handle.links.size)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    // --- the error path must release what it acquired ------------------------

    @Test
    fun a_failed_open_closes_the_port_it_attached() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block in
            // runConnection. A port left open is not reclaimed until the
            // process dies, and the driver will not open it twice — so one bad
            // cable moment made the dongle unusable for the rest of the burn.
            val handle = FakeUsbHandle(openFailure = IOException("Error setting baud rate"))
            val source = source(handle, dispatcher)

            source.start()
            runCurrent()

            val state = source.state.value
            assertTrue("a failed open must surface as an error; state=$state", state is LocationSourceState.Error)
            assertEquals(LocationSourceError.IO_ERROR, (state as LocationSourceState.Error).kind)
            assertTrue("a failed open must not leak the port", handle.links[0].isClosed)
        }

    @Test
    fun a_read_that_throws_closes_the_port() =
        runTestOnOneDispatcher { dispatcher ->
            // Mutation: delete the `finally { opened?.close() }` block. A
            // dongle yanked mid-drive throws out of the read loop; the old code
            // reported the error and returned with the port still held, so
            // plugging it back in could never reopen it.
            val handle = FakeUsbHandle()
            val source = source(handle, dispatcher)
            source.start()
            runCurrent()

            handle.links[0].failRead(IOException("Connection closed"))
            runCurrent()

            assertTrue("a read fault must release the port", handle.links[0].isClosed)
            assertTrue(source.state.value is LocationSourceState.Error)
        }

    @Test
    fun an_unavailable_dongle_reports_its_own_reason() =
        runTestOnOneDispatcher { dispatcher ->
            val handle =
                FakeUsbHandle(
                    unavailable =
                        UsbAttach.Unavailable("USB device permission not granted", LocationSourceError.PERMISSION_DENIED),
                )
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
     * watchdog. Sharing the *instance* matters: `stop()` closes the port inside
     * `withContext(ioDispatcher)`, and a second dispatcher would make that a
     * real dispatch, handing the in-flight listener a chance to run before
     * `stop()` reaches its join — which would hide the very bug
     * [stop_waits_for_bytes_already_in_flight_so_disconnected_is_the_last_word]
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
         * How long the fake holds bytes it has already been handed before the
         * source parses them. In production that window is microseconds wide —
         * the gap between a blocking read returning and the parse landing —
         * and widening it here is what makes the race observable rather than
         * lucky.
         */
        const val IN_FLIGHT_MS: Long = 150L
        const val TIMEOUT_MS: Long = 4_000L
        const val POLL_MS: Long = 10L
    }
}

/**
 * A dongle that behaves like a real serial port: [read] blocks until bytes
 * arrive or the port is closed, and — the part that matters — it suspends via
 * [suspendCoroutine], not `suspendCancellableCoroutine`, because a blocking
 * bulk read does *not* abort when the coroutine is cancelled. Modelling it as
 * cancellable would quietly test a bug shape the real source cannot have, and
 * every stop() test would pass for the wrong reason.
 */
private class FakeUsbLink(
    private val openFailure: Exception?,
    /**
     * Null: [read] suspends on the test scheduler, so virtual-time tests stay
     * deterministic. Set: [read] blocks a real thread and then holds the bytes
     * for this long before returning them — the only way to observe whether
     * `stop()` really waits for the listener, since a single-threaded test
     * scheduler runs the queued listener during `stop()`'s other joins
     * regardless.
     */
    private val readDelayMs: Long?,
) : UsbSerialLink {
    var openCalls: Int = 0
        private set

    private var waiting: Continuation<ByteArray>? = null

    @Volatile private var blocked = false

    @Volatile private var closed = false

    private val handoff = LinkedBlockingQueue<ByteArray>()

    /** A real close is idempotent, so "was it released" is the question, not how often. */
    val isClosed: Boolean get() = closed

    val readInFlight: Boolean get() = blocked || waiting != null

    override suspend fun open(baudRate: Int) {
        openCalls++
        openFailure?.let { throw it }
    }

    override suspend fun read(): ByteArray {
        if (readDelayMs != null) return blockingRead(readDelayMs)
        // Reading a closed port throws rather than returning nothing, which is
        // both what the driver does and what keeps a stray loop from spinning.
        if (closed) throw IOException("port closed")
        return suspendCoroutine { cont -> waiting = cont }
    }

    private fun blockingRead(delayMs: Long): ByteArray {
        blocked = true
        val chunk = handoff.take()
        blocked = false
        Thread.sleep(delayMs)
        return chunk
    }

    override fun close() {
        closed = true
        // Closing the port is what unblocks a blocked read.
        resumeWith(ByteArray(0))
    }

    /** Hand the blocked read a chunk, as the driver would. */
    fun deliver(text: String) = resumeWith(text.toByteArray(Charsets.US_ASCII))

    /** The dongle is yanked mid-read. */
    fun failRead(ex: Exception) {
        val cont = waiting ?: return
        waiting = null
        cont.resumeWithException(ex)
    }

    private fun resumeWith(chunk: ByteArray) {
        if (readDelayMs != null) {
            handoff.put(chunk)
            return
        }
        val cont = waiting ?: return
        waiting = null
        cont.resume(chunk)
    }
}

private class FakeUsbHandle(
    var unavailable: UsbAttach.Unavailable? = null,
    private val openFailure: Exception? = null,
    private val readDelayMs: Long? = null,
) : UsbSerialHandle {
    val links = mutableListOf<FakeUsbLink>()

    override fun attach(): UsbAttach = unavailable ?: UsbAttach.Attached(FakeUsbLink(openFailure, readDelayMs).also { links += it })
}
