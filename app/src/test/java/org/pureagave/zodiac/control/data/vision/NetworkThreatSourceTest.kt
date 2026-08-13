package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.net.FleetBus
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.ThreatProtocol
import org.pureagave.zodiac.control.data.sensor.MulticastLockHandle
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/** Exercises the threat UDP receive path over real loopback sockets. */
class NetworkThreatSourceTest {
    @Test
    fun receives_a_threat_frame_over_udp() =
        runBlocking {
            val port = 10188
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkThreatSource(scope = scope, port = port, staleMs = 5_000)
            try {
                source.start()
                val frame = ThreatProtocol.format(listOf(DriverThreat(relAzDeg = 7f, size = 0.8f, collision = true, id = 9)))
                val ok =
                    waitUntil(4_000) {
                        sendUdp(frame, port)
                        source.threats.value.any { it.id == 9 && it.collision }
                    }
                assertTrue("a ZTHREAT frame over UDP should populate threats", ok)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun clears_to_all_clear_when_the_feed_goes_stale() =
        runBlocking {
            val port = 10189
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkThreatSource(scope = scope, port = port, staleMs = 300)
            try {
                source.start()
                val frame = ThreatProtocol.format(listOf(DriverThreat(relAzDeg = 0f, size = 0.5f, id = 1)))
                assertTrue(
                    waitUntil(3_000) {
                        sendUdp(frame, port)
                        source.threats.value.isNotEmpty()
                    },
                )
                // Stop sending; the watchdog must clear the stale contacts.
                assertTrue("a stale feed must clear to all-clear", waitUntil(3_000) { source.threats.value.isEmpty() })
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun a_bare_all_clear_frame_still_marks_the_feed_alive() =
        runBlocking {
            // The distinction this pins is safety-relevant: "the edge box says
            // the road is clear" must not read as "the feed is dead". If it
            // did, RoutedThreatSource would fall back to the demo source and
            // paint fabricated collision contacts on the driver's HUD while the
            // vehicle is moving. A bare "ZTHREAT" header is a valid all-clear.
            val port = 10222
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkThreatSource(scope = scope, port = port, staleMs = 4_000)
            try {
                source.start()
                val ok =
                    waitUntil(4_000) {
                        sendUdp("ZTHREAT", port)
                        source.feedAlive.value
                    }
                assertTrue("an all-clear frame is a live feed, not a dead one", ok)
                assertTrue("and it carries no contacts", source.threats.value.isEmpty())
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun the_feed_is_marked_dead_once_frames_stop() =
        runBlocking {
            // Only the contact-clearing half of the watchdog was covered; this
            // pins the flag the routed source actually switches on.
            val port = 10223
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkThreatSource(scope = scope, port = port, staleMs = 400)
            try {
                source.start()
                assertTrue(
                    "precondition: the feed came alive",
                    waitUntil(4_000) {
                        sendUdp("ZTHREAT;1:10.0:0.5:0", port)
                        source.feedAlive.value
                    },
                )
                assertTrue(
                    "silence must mark the feed dead so the fallback can take over",
                    waitUntil(4_000) { !source.feedAlive.value },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    // --- The listener used to be one-shot: a socket-open failure `return`ed
    // out of runListener for good, a mid-loop exception ended the loop
    // permanently, and there was no rejoin-on-silence at all — so a router
    // power-cycle left the DRIVER night-vision HUD threat-deaf for the rest
    // of the drive, recoverable only by restarting the kiosk-locked app.
    // These pin the ported retry/backoff/rejoin loop from
    // `NetworkLocationSource`, adapted for a source with no state channel of
    // its own. ---

    /** Opens real threat sockets, but fails the first [failFirst] attempts and counts every one. */
    private class CountingSocketFactory(
        private val port: Int,
        private val failFirst: Int = 0,
    ) : () -> MulticastSocket {
        val opened = CopyOnWriteArrayList<MulticastSocket>()
        val attempts = AtomicInteger()

        override fun invoke(): MulticastSocket {
            if (attempts.incrementAndGet() <= failFirst) {
                throw SocketException("simulated: no route to host (router rebooting)")
            }
            return openThreatSocket(port, FleetBus.THREAT_GROUP).also { opened += it }
        }
    }

    @Test(timeout = 60_000)
    fun a_transient_socket_open_failure_self_heals_instead_of_going_threat_deaf() =
        runBlocking {
            // Mutation: restore the old terminal path — on an open failure,
            // `return` out of the listener instead of backing off and retrying.
            val port = 10225
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port, failFirst = 2)
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    retryBaseMs = 300,
                    retryMaxMs = 1_000,
                    openSocket = factory,
                )
            try {
                source.start()
                val frame = ThreatProtocol.format(listOf(DriverThreat(relAzDeg = 5f, size = 0.4f, id = 41)))
                assertTrue(
                    "a socket that fails to open at first must self-heal, not go deaf for good",
                    waitUntil(15_000) {
                        sendUdp(frame, port)
                        source.threats.value.any { it.id == 41 }
                    },
                )
                assertTrue(
                    "the listener must have re-attempted the open; attempts=${factory.attempts.get()}",
                    factory.attempts.get() >= 3,
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test(timeout = 60_000)
    fun a_broken_read_re_binds_instead_of_dying() =
        runBlocking {
            // The other terminal path: the open succeeded, then the socket
            // died under a live read. Mutation: on a mid-loop exception, exit
            // the listener for good instead of tearing down and retrying.
            val port = 10226
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port)
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    retryBaseMs = 200,
                    retryMaxMs = 500,
                    openSocket = factory,
                )
            try {
                source.start()
                val firstFrame = ThreatProtocol.format(listOf(DriverThreat(relAzDeg = -3f, size = 0.2f, id = 51)))
                assertTrue(
                    "precondition: the feed is alive on the first socket",
                    waitUntil(8_000) {
                        sendUdp(firstFrame, port)
                        source.feedAlive.value
                    },
                )
                // Kill the live socket the way a vanishing interface does: the
                // blocked receive throws rather than timing out.
                factory.opened.last().close()
                assertTrue(
                    "a broken read must re-bind, not die; attempts=${factory.attempts.get()}",
                    waitUntil(15_000) { factory.attempts.get() >= 2 },
                )
                val secondFrame = ThreatProtocol.format(listOf(DriverThreat(relAzDeg = 3f, size = 0.6f, id = 52)))
                assertTrue(
                    "the re-bound socket must go on to receive real frames",
                    waitUntil(8_000) {
                        sendUdp(secondFrame, port)
                        source.threats.value.any { it.id == 52 }
                    },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test(timeout = 60_000)
    fun a_persistent_open_failure_backs_off_instead_of_busy_looping() =
        runBlocking {
            // Mutation: drop the backoffWait() call from the failure path.
            // Over 2 s the capped-exponential policy (100/200/400/400…) makes
            // ~7 attempts; no wait at all makes thousands and pins a core.
            val port = 10227
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port, failFirst = Int.MAX_VALUE)
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    retryBaseMs = 100,
                    retryMaxMs = 400,
                    openSocket = factory,
                )
            try {
                source.start()
                Thread.sleep(2_000)
                val attempts = factory.attempts.get()
                assertTrue("the retry must actually happen; attempts=$attempts", attempts >= 3)
                assertTrue("the retry must back off, not spin; attempts=$attempts in 2 s", attempts <= 12)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test(timeout = 60_000)
    fun the_retry_backoff_doubles_and_is_capped() =
        runBlocking {
            // Mutation: make the backoff constant (return retryBaseMs), or
            // uncap it. Asserted through the injected scheduler seam rather
            // than wall-clock timing, so it is exact and not load-sensitive.
            val port = 10228
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val waits = CopyOnWriteArrayList<Long>()
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    retryBaseMs = 100,
                    retryMaxMs = 400,
                    openSocket = { throw SocketException("simulated: interface down") },
                    backoffWait = { ms ->
                        waits += ms
                        // Park once we have the sequence, so the loop doesn't
                        // spin the CPU for the rest of the test.
                        if (waits.size >= 6) awaitCancellation()
                    },
                )
            try {
                source.start()
                assertTrue("the retry loop should have requested six waits", waitUntil(8_000) { waits.size >= 6 })
                assertEquals(
                    "backoff must double from the base and clamp at the ceiling",
                    listOf(100L, 200L, 400L, 400L, 400L, 400L),
                    waits.take(6),
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test(timeout = 60_000)
    fun stop_during_a_retry_backoff_returns_promptly() =
        runBlocking {
            // NetworkThreatSource.stop() now calls job.cancelAndJoin() (mirrors
            // NetworkLocationSource.stop(), so a datagram already inside
            // ingestFrame() can't finish after stop()'s clears and repopulate
            // them) — so stop() itself is what this test measures directly,
            // valid now that stop() genuinely joins the listener.
            //
            // Mutation: swap the default backoffWait for a non-cancellable
            // wait (Thread.sleep(it)) — cancelAndJoin then can't interrupt the
            // parked backoff, the listener coroutine keeps sleeping for the
            // full retry interval regardless, and stop() itself blocks for it.
            val port = 10229
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port, failFirst = Int.MAX_VALUE)
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    retryBaseMs = 60_000,
                    retryMaxMs = 60_000,
                    openSocket = factory,
                )
            try {
                source.start()
                assertTrue(
                    "precondition: parked in the backoff after a failed open",
                    waitUntil(8_000) { factory.attempts.get() >= 1 },
                )
                val elapsed = measureTimeMillis { source.stop() }
                assertTrue(
                    "a cancelled backoff must unwind promptly, not sleep out the full retry interval; took ${elapsed}ms",
                    elapsed < 3_000,
                )
            } finally {
                scope.cancel()
            }
        }

    @Test(timeout = 60_000)
    fun a_silent_socket_is_rebuilt_so_the_multicast_group_is_rejoined() =
        runBlocking {
            // Mutation: delete the silence check in pump() — the failure with
            // no exception to catch. A WiFi re-association drops the IGMP
            // membership silently; every read then times out politely forever
            // and the tablet listens to a group it is no longer in.
            val port = 10230
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port)
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    rejoinSilentMs = 300,
                    openSocket = factory,
                )
            try {
                source.start()
                // Send NO traffic at all — a socket that hears nothing must
                // still be periodically rebuilt.
                val rebuilt = waitUntil(15_000) { factory.opened.size >= 3 }
                assertTrue(
                    "a socket that has heard nothing must be rebuilt, not listened to forever; opened=${factory.opened.size}",
                    rebuilt,
                )
                assertTrue(
                    "every superseded socket must be closed — a night of rejoins must not leak descriptors",
                    factory.opened.dropLast(1).all { it.isClosed },
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    // --- start() must be genuinely idempotent (mirrors AUDIT-2026-08-09 C5,
    // the GPS twin's bug): a redundant start() used to cancel+relaunch the
    // live listener and re-acquire the multicast lock out from under it,
    // leaking a held, non-refcounted MulticastLock. ---

    /** Counts acquire()/release() unconditionally, copied from the GPS twin's test. */
    private class FakeMulticastLockHandle : MulticastLockHandle {
        var acquireCalls = 0
            private set
        var releaseCalls = 0
            private set
        override var isHeld: Boolean = false
            private set

        override fun acquire() {
            acquireCalls++
            isHeld = true
        }

        override fun release() {
            releaseCalls++
            isHeld = false
        }
    }

    @Test
    fun double_start_acquires_the_multicast_lock_exactly_once() =
        runBlocking {
            // Mutation: remove the `job?.isActive == true` guard at the top
            // of start().
            val port = 10231
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val lock = FakeMulticastLockHandle()
            val source = NetworkThreatSource(scope = scope, port = port, multicastLockHandle = lock)
            try {
                source.start()
                source.start()
                assertEquals("a redundant start() must not re-acquire the lock", 1, lock.acquireCalls)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun stop_releases_the_multicast_lock_acquired_by_start() =
        runBlocking {
            // Mutation: delete the multicastLockHandle.release() call in stop().
            val port = 10232
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val lock = FakeMulticastLockHandle()
            val source = NetworkThreatSource(scope = scope, port = port, multicastLockHandle = lock)
            source.start()
            source.stop()
            scope.cancel()
            assertEquals(1, lock.acquireCalls)
            assertEquals(1, lock.releaseCalls)
        }

    @Test(timeout = 60_000)
    fun retrying_the_open_does_not_re_acquire_the_multicast_lock() =
        runBlocking {
            // Mutation: acquire the lock per attempt inside the retry loop —
            // the C5 leak shape, one leaked lock per retry, all night.
            val port = 10233
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val lock = FakeMulticastLockHandle()
            val factory = CountingSocketFactory(port, failFirst = Int.MAX_VALUE)
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    retryBaseMs = 50,
                    retryMaxMs = 100,
                    multicastLockHandle = lock,
                    openSocket = factory,
                )
            try {
                assertEquals(0, lock.acquireCalls)
                source.start()
                assertTrue("precondition: several retries happened", waitUntil(8_000) { factory.attempts.get() >= 4 })
                assertEquals("the retry loop must not re-acquire the multicast lock", 1, lock.acquireCalls)
                source.stop()
                assertEquals("stop() releases the one lock start() took", 1, lock.releaseCalls)
            } finally {
                scope.cancel()
            }
        }

    // --- Watchdog check-then-clear TOCTOU: a recovery frame landing between
    // the staleness check and the clear used to be wiped, flashing a real
    // contact to ABSENT for one frame. Fixed by re-checking lastRxMs inside a
    // StateFlow.update{} CAS rather than writing unconditionally. Defended by
    // review plus the ordering test below (a genuinely racy reproduction over
    // loopback would need a watchdog hook not worth adding to production for
    // this); see the finding's HONEST NOTE. ---

    @Test
    fun liveness_is_stamped_before_contacts_are_published() =
        runBlocking {
            // Deterministic clock-snoop, not eventual-consistency polling: the
            // old version of this test only polled threats/feedAlive after the
            // fact, so it would still pass even with the two writes in the
            // wrong order. This one observes the *exact* moment nowMs() is
            // read for the stamp and checks the published state at that instant.
            //
            // No start() here, so the only nowMs() call anywhere in this path
            // is ingestFrame's own stamp — nothing else on the clock seam to
            // confuse the snoop.
            val port = 10234
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            lateinit var sourceRef: NetworkThreatSource
            var snapshotAtStamp: List<DriverThreat>? = null
            val source =
                NetworkThreatSource(
                    scope = scope,
                    port = port,
                    nowMs = {
                        snapshotAtStamp = sourceRef.threats.value
                        0L
                    },
                )
            sourceRef = source
            try {
                val frame = ThreatProtocol.format(listOf(DriverThreat(relAzDeg = 7f, size = 0.6f, id = 7)))
                source.ingestFrame(frame)
                assertEquals(1, source.threats.value.size)
                assertTrue(source.feedAlive.value)
                assertEquals(
                    "lastRxMs must be stamped before contacts are published: the snapshot taken at " +
                        "the stamp's own clock read must not yet see the parsed contact",
                    emptyList<DriverThreat>(),
                    snapshotAtStamp,
                )
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    private fun sendUdp(
        msg: String,
        port: Int,
    ) {
        DatagramSocket().use {
            val bytes = msg.toByteArray(Charsets.US_ASCII)
            it.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
        }
    }

    private inline fun waitUntil(
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }
}
