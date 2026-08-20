package org.pureagave.zodiac.control.data.fleet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.net.FleetBus
import org.pureagave.zodiac.control.core.telemetry.BuildIdentity
import org.pureagave.zodiac.control.core.telemetry.FleetVersion
import org.pureagave.zodiac.control.core.telemetry.FleetVersionProtocol
import org.pureagave.zodiac.control.data.sensor.MulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.openFleetNmeaSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the `$ZVER` receive path end-to-end over real loopback sockets
 * (mirrors `NavShareReceiverTest`). The map-fold assertions — two nodes coexist,
 * a reflashed node's build is overwritten in place, the arrival is timestamped —
 * are what pin the behaviour that differs from the single-value `$ZNAV` receiver.
 */
class FleetVersionReceiverTest {
    @Test
    fun receives_a_zver_datagram_and_records_the_peer_stamped_now() =
        runBlocking {
            val port = 10440
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            // A fixed clock so the stamped lastHeardAtMs is assertable (kills a
            // mutant that drops or zeroes the timestamp on ingest).
            val receiver = FleetVersionReceiver(scope = scope, port = port, now = { FIXED_NOW })
            try {
                receiver.start()
                val sentence = zver(node = "9C1977", name = "SM-X810", sha = "8f531e18a", epoch = 1_691_900_000L)
                val received =
                    waitUntil(4_000) {
                        sendUdp(sentence, port)
                        receiver.peers.value.containsKey("9C1977")
                    }
                assertTrue("a valid \$ZVER over UDP should record the peer", received)
                val obs = receiver.peers.value.getValue("9C1977")
                assertEquals("SM-X810", obs.version.name)
                assertEquals("8f531e18a", obs.version.identity.sha)
                assertEquals(1_691_900_000L, obs.version.identity.commitEpochSeconds)
                assertEquals("the arrival must be stamped with now()", FIXED_NOW, obs.lastHeardAtMs)
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    @Test
    fun malformed_datagrams_never_populate_the_table() =
        runBlocking {
            val port = 10441
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = FleetVersionReceiver(scope = scope, port = port)
            try {
                receiver.start()
                val deadline = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < deadline) {
                    sendUdp("not a \$ZVER sentence at all\r\n", port)
                    Thread.sleep(30)
                }
                assertTrue("junk must never record a peer", receiver.peers.value.isEmpty())
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    @Test
    fun two_distinct_nodes_both_appear_in_the_table() =
        runBlocking {
            // The core difference from $ZNAV: a roster is a SET keyed by node, so
            // a second device must not evict the first (a single-StateFlow mutant
            // would only ever hold one).
            val port = 10442
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = FleetVersionReceiver(scope = scope, port = port)
            try {
                receiver.start()
                assertTrue(
                    waitUntil(4_000) {
                        sendUdp(zver(node = "AAAAAA", name = "SM-X810", sha = "1111111aa"), port)
                        receiver.peers.value.containsKey("AAAAAA")
                    },
                )
                assertTrue(
                    waitUntil(4_000) {
                        sendUdp(zver(node = "BBBBBB", name = "KFTUWI", sha = "2222222bb"), port)
                        receiver.peers.value.containsKey("BBBBBB")
                    },
                )
                assertEquals(setOf("AAAAAA", "BBBBBB"), receiver.peers.value.keys)
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    @Test
    fun a_reflashed_node_has_its_build_overwritten_in_place() =
        runBlocking {
            val port = 10443
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = FleetVersionReceiver(scope = scope, port = port)
            try {
                receiver.start()
                assertTrue(
                    waitUntil(4_000) {
                        sendUdp(zver(node = "CAFE01", name = "SM-X810", sha = "0000000aa", epoch = 100L), port)
                        receiver.peers.value["CAFE01"]?.version?.identity?.sha == "0000000aa"
                    },
                )
                // Same node re-announces after a reflash: newer sha + epoch.
                assertTrue(
                    waitUntil(4_000) {
                        sendUdp(zver(node = "CAFE01", name = "SM-X810", sha = "ffffff9bb", epoch = 200L), port)
                        receiver.peers.value["CAFE01"]?.version?.identity?.sha == "ffffff9bb"
                    },
                )
                assertEquals("one node stays one row", 1, receiver.peers.value.size)
                assertEquals(200L, receiver.peers.value.getValue("CAFE01").version.identity.commitEpochSeconds)
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    @Test
    fun stop_clears_the_peer_table() =
        runBlocking {
            val port = 10444
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = FleetVersionReceiver(scope = scope, port = port)
            try {
                receiver.start()
                assertTrue(
                    "precondition: a peer landed",
                    waitUntil(4_000) {
                        sendUdp(zver(node = "DDDDDD", name = "SM-X810", sha = "3333333cc"), port)
                        receiver.peers.value.isNotEmpty()
                    },
                )
                receiver.stop()
                assertTrue("stop() must clear the table", receiver.peers.value.isEmpty())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun start_stop_acquires_and_releases_the_multicast_lock_exactly_once() =
        runBlocking {
            val port = 10445
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val lock = FakeMulticastLockHandle()
            val receiver = FleetVersionReceiver(scope = scope, port = port, multicastLockHandle = lock)
            receiver.start()
            // A redundant start() must not double-acquire (mirrors C5).
            receiver.start()
            receiver.stop()
            scope.cancel()
            assertEquals(1, lock.acquireCalls)
            assertEquals(1, lock.releaseCalls)
        }

    @Test(timeout = 60_000)
    fun a_socket_hearing_nothing_is_rebuilt_to_rejoin_the_group() =
        runBlocking {
            // Silence is the normal state here too (versions announce only every
            // 10s). Pins that idle rebuilds still happen — what re-joins the group
            // after an AP failover — without fabricating an error.
            val port = 10446
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port)
            val receiver =
                FleetVersionReceiver(scope = scope, port = port, rejoinSilentMs = 300, openSocket = factory)
            try {
                receiver.start()
                val rebuilt = waitUntil(15_000) { factory.opened.size >= 3 }
                assertTrue("a socket hearing nothing must be rebuilt; opened=${factory.opened.size}", rebuilt)
                assertTrue(
                    "every superseded socket must be closed",
                    factory.opened.dropLast(1).all { it.isClosed },
                )
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    private fun zver(
        node: String,
        name: String,
        sha: String,
        base: String = "0.1.0",
        dirty: Boolean = false,
        epoch: Long = 1_691_900_000L,
    ): String = FleetVersionProtocol.build(FleetVersion(node, name, BuildIdentity(base, sha, dirty, epoch)))

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

    /** Opens real fleet sockets on the given [port], counting every attempt. */
    private class CountingSocketFactory(private val port: Int) : () -> MulticastSocket {
        val opened = CopyOnWriteArrayList<MulticastSocket>()
        val attempts = AtomicInteger()

        override fun invoke(): MulticastSocket {
            attempts.incrementAndGet()
            return openFleetNmeaSocket(port, FleetBus.VERSION_GROUP).also { opened += it }
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

    private fun waitUntil(
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

    private companion object {
        const val FIXED_NOW: Long = 123_456L
    }
}
