package org.pureagave.zodiac.control.data.nav

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
 * [NavShareSender] runs its loop on real `Dispatchers.IO`, so these tests poll
 * real wall-clock time (same style as `NetworkLocationSourceTest`) rather than
 * a virtual test dispatcher.
 */
class NavShareSenderTest {
    @Test
    fun publish_sends_immediately_then_at_the_rebroadcast_cadence_with_identical_bytes() =
        runBlocking {
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val sender =
                NavShareSender(
                    scope = scope,
                    rebroadcastMs = 60,
                    openSocket = { socket },
                    dhcpIp = { 0 },
                )
            try {
                sender.publish("SENTENCE-A")
                assertTrue("must send immediately", waitUntil(2_000) { socket.sent.size >= 1 })

                // Let a few more ticks land at the ~60ms cadence.
                assertTrue("must keep re-sending on a timer", waitUntil(2_000) { socket.sent.size >= 6 })

                assertTrue("every send must carry identical bytes", socket.sent.all { it.second == "SENTENCE-A" })
            } finally {
                sender.stop()
                scope.cancel()
            }
        }

    @Test
    fun each_tick_sends_to_both_targets() =
        runBlocking {
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            // No lease -> group + limited broadcast fallback.
            val sender =
                NavShareSender(
                    scope = scope,
                    rebroadcastMs = 5_000,
                    openSocket = { socket },
                    dhcpIp = { 0 },
                )
            try {
                sender.publish("SENTENCE-B")
                assertTrue(waitUntil(2_000) { socket.sent.size >= 2 })
                val hosts = socket.sent.take(2).map { it.first }.toSet()
                assertEquals(setOf("239.7.7.30", "255.255.255.255"), hosts)
            } finally {
                sender.stop()
                scope.cancel()
            }
        }

    @Test
    fun one_target_throwing_does_not_block_the_other() =
        runBlocking {
            // The multicast leg fails every time; the broadcast fallback must
            // still get every sentence.
            val socket = FakeDatagramSocket(failFor = setOf("239.7.7.30"))
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val sender =
                NavShareSender(
                    scope = scope,
                    rebroadcastMs = 5_000,
                    openSocket = { socket },
                    dhcpIp = { 0 },
                )
            try {
                sender.publish("SENTENCE-C")
                assertTrue(
                    "the broadcast leg must still be sent despite the multicast leg failing",
                    waitUntil(2_000) { socket.sent.any { it.first == "255.255.255.255" } },
                )
            } finally {
                sender.stop()
                scope.cancel()
            }
        }

    @Test
    fun publish_replaces_the_old_sentence() =
        runBlocking {
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val sender =
                NavShareSender(
                    scope = scope,
                    rebroadcastMs = 40,
                    openSocket = { socket },
                    dhcpIp = { 0 },
                )
            try {
                sender.publish("OLD")
                assertTrue(waitUntil(2_000) { socket.sent.any { it.second == "OLD" } })
                val oldCountAtSwitch = socket.sent.count { it.second == "OLD" }

                sender.publish("NEW")
                assertTrue("must send the replacement", waitUntil(2_000) { socket.sent.any { it.second == "NEW" } })
                // Give several more rebroadcast intervals to elapse.
                assertTrue(waitUntil(2_000) { socket.sent.count { it.second == "NEW" } >= 3 })

                assertEquals(
                    "no further OLD sends after the switch",
                    oldCountAtSwitch,
                    socket.sent.count { it.second == "OLD" },
                )
            } finally {
                sender.stop()
                scope.cancel()
            }
        }

    @Test
    fun stop_ceases_sends_and_closes_the_socket() =
        runBlocking {
            val socket = FakeDatagramSocket()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val sender =
                NavShareSender(
                    scope = scope,
                    rebroadcastMs = 30,
                    openSocket = { socket },
                    dhcpIp = { 0 },
                )
            try {
                sender.publish("SENTENCE-D")
                assertTrue(waitUntil(2_000) { socket.sent.isNotEmpty() })

                sender.stop()
                assertTrue("stop() must close the socket", socket.closed)
                val countAtStop = socket.sent.size
                Thread.sleep(150) // several rebroadcast intervals
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
            val sender =
                NavShareSender(
                    scope = scope,
                    rebroadcastMs = 30,
                    openSocket = {
                        val n = attempts.incrementAndGet()
                        if (n <= 2) throw IOException("simulated: router not up yet") else socket
                    },
                    dhcpIp = { 0 },
                )
            try {
                sender.publish("SENTENCE-E")
                assertTrue(
                    "a failed open must be retried, not given up on",
                    waitUntil(2_000) { socket.sent.isNotEmpty() },
                )
                assertTrue("at least the two failures plus the success were attempted", attempts.get() >= 3)
            } finally {
                sender.stop()
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
