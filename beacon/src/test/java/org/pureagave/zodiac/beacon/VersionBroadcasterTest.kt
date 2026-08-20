package org.pureagave.zodiac.beacon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [VersionBroadcaster] runs its loop on real `Dispatchers.IO`, so these tests
 * poll real wall-clock time (the app's `FleetVersionSenderTest` style). It sends
 * on the version group `239.7.7.40`; with no DHCP lease (`DhcpLease(0, 0)`) the
 * second target is the limited broadcast, via [BeaconNet].
 */
class VersionBroadcasterTest {
    @Test
    fun start_sends_immediately_then_at_the_cadence_with_identical_bytes() =
        runBlocking {
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val vb = VersionBroadcaster(scope = scope, rebroadcastMs = 60, openSocket = { socket })
            try {
                vb.start("\$ZVER,SENT-A*00\r\n")
                assertTrue("must send immediately", waitUntil(2_000) { socket.sent.size >= 1 })
                assertTrue("must keep re-sending on a timer", waitUntil(2_000) { socket.sent.size >= 6 })
                assertTrue("every send carries identical bytes", socket.sent.all { it.second == "\$ZVER,SENT-A*00\r\n" })
            } finally {
                vb.stop()
                scope.cancel()
            }
        }

    @Test
    fun each_tick_sends_to_both_targets_on_the_version_group() =
        runBlocking {
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            // Default dhcp = no lease -> version group + limited-broadcast fallback.
            val vb = VersionBroadcaster(scope = scope, rebroadcastMs = 5_000, openSocket = { socket })
            try {
                vb.start("SENT-B")
                assertTrue(waitUntil(2_000) { socket.sent.size >= 2 })
                assertEquals(setOf("239.7.7.40", "255.255.255.255"), socket.sent.take(2).map { it.first }.toSet())
            } finally {
                vb.stop()
                scope.cancel()
            }
        }

    @Test
    fun one_target_throwing_does_not_block_the_other() =
        runBlocking {
            val socket = FakeDatagramSocket(failFor = setOf("239.7.7.40"))
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val vb = VersionBroadcaster(scope = scope, rebroadcastMs = 5_000, openSocket = { socket })
            try {
                vb.start("SENT-C")
                assertTrue(
                    "the broadcast leg must still be sent despite the multicast leg failing",
                    waitUntil(2_000) { socket.sent.any { it.first == "255.255.255.255" } },
                )
            } finally {
                vb.stop()
                scope.cancel()
            }
        }

    @Test
    fun stop_ceases_sends_and_closes_the_socket() =
        runBlocking {
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val vb = VersionBroadcaster(scope = scope, rebroadcastMs = 30, openSocket = { socket })
            try {
                vb.start("SENT-D")
                assertTrue(waitUntil(2_000) { socket.sent.isNotEmpty() })
                vb.stop()
                assertTrue("stop() must close the socket", socket.closed)
                val countAtStop = socket.sent.size
                Thread.sleep(150)
                assertEquals("no sends after stop()", countAtStop, socket.sent.size)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun a_failed_socket_open_retries_on_the_next_tick() =
        runBlocking {
            val attempts = AtomicInteger()
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val vb =
                VersionBroadcaster(
                    scope = scope,
                    rebroadcastMs = 30,
                    openSocket = {
                        if (attempts.incrementAndGet() <= 2) throw IOException("router not up yet") else socket
                    },
                )
            try {
                vb.start("SENT-E")
                assertTrue("a failed open must be retried", waitUntil(2_000) { socket.sent.isNotEmpty() })
                assertTrue("the two failures plus a success were attempted", attempts.get() >= 3)
            } finally {
                vb.stop()
                scope.cancel()
            }
        }

    private fun waitUntil(
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    /** Records every "send" instead of touching the network; [failFor] simulates a target that always throws. */
    private class FakeDatagramSocket(
        private val failFor: Set<String> = emptySet(),
    ) : DatagramSocket() {
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        var closed = false
            private set

        override fun send(p: DatagramPacket) {
            val host: String = p.address.hostAddress ?: "?"
            if (host in failFor) throw IOException("simulated failure sending to $host")
            sent += host to String(p.data, 0, p.length, Charsets.US_ASCII)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
