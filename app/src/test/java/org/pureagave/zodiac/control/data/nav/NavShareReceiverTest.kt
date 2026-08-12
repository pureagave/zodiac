package org.pureagave.zodiac.control.data.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.net.FleetBus
import org.pureagave.zodiac.control.core.ops.NavShareMessage
import org.pureagave.zodiac.control.core.ops.NavSharePayload
import org.pureagave.zodiac.control.core.ops.NavShareProtocol
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.data.sensor.MulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.openFleetNmeaSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Mirrors `NetworkLocationSourceTest`'s style: exercises the UDP receive path end-to-end over real loopback sockets. */
class NavShareReceiverTest {
    @Test
    fun receives_a_znav_datagram_and_emits_the_parsed_message() =
        runBlocking {
            val port = 10420
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = NavShareReceiver(scope = scope, port = port)
            try {
                receiver.start()
                val sentence = NavShareProtocol.build(NavShareMessage(1, "ABC123", NavSharePayload.Preset(NavTarget.MAN)))
                val received =
                    waitUntil(4_000) {
                        sendUdp(sentence, port)
                        receiver.messages.value != null
                    }
                assertTrue("a valid \$ZNAV over UDP should produce a message", received)
                val msg = receiver.messages.value
                assertEquals(1, msg!!.seq)
                assertEquals("ABC123", msg.src)
                assertEquals(NavSharePayload.Preset(NavTarget.MAN), msg.payload)
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    @Test
    fun malformed_datagrams_never_produce_an_emission() =
        runBlocking {
            val port = 10421
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = NavShareReceiver(scope = scope, port = port)
            try {
                receiver.start()
                val deadline = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < deadline) {
                    sendUdp("not a \$ZNAV sentence at all\r\n", port)
                    Thread.sleep(30)
                }
                assertNull("junk must never produce a message", receiver.messages.value)
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    @Test
    fun a_second_message_overwrites_the_first() =
        runBlocking {
            val port = 10422
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = NavShareReceiver(scope = scope, port = port)
            try {
                receiver.start()
                val first = NavShareProtocol.build(NavShareMessage(1, "ABC123", NavSharePayload.Bath))
                assertTrue(
                    waitUntil(4_000) {
                        sendUdp(first, port)
                        receiver.messages.value?.payload == NavSharePayload.Bath
                    },
                )
                val second = NavShareProtocol.build(NavShareMessage(2, "ABC123", NavSharePayload.Clear))
                assertTrue(
                    waitUntil(4_000) {
                        sendUdp(second, port)
                        receiver.messages.value?.payload == NavSharePayload.Clear
                    },
                )
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

    @Test
    fun stop_leaves_no_stale_message() =
        runBlocking {
            val port = 10423
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val receiver = NavShareReceiver(scope = scope, port = port)
            try {
                receiver.start()
                val sentence = NavShareProtocol.build(NavShareMessage(1, "ABC123", NavSharePayload.Bath))
                assertTrue(
                    "precondition: a message landed",
                    waitUntil(4_000) {
                        sendUdp(sentence, port)
                        receiver.messages.value != null
                    },
                )
                receiver.stop()
                assertNull("stop() must leave no stale message", receiver.messages.value)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun start_stop_acquires_and_releases_the_multicast_lock_exactly_once() =
        runBlocking {
            val port = 10424
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val lock = FakeMulticastLockHandle()
            val receiver = NavShareReceiver(scope = scope, port = port, multicastLockHandle = lock)
            receiver.start()
            // A redundant start() must not double-acquire (mirrors NetworkLocationSource's C5 pin).
            receiver.start()
            receiver.stop()
            scope.cancel()
            assertEquals(1, lock.acquireCalls)
            assertEquals(1, lock.releaseCalls)
        }

    @Test(timeout = 60_000)
    fun a_socket_hearing_nothing_is_rebuilt_to_rejoin_the_group() =
        runBlocking {
            // Silence is the NORMAL idle state for $ZNAV (no owner => no
            // traffic) -- this pins that idle rebuilds still happen (they're
            // what re-joins the group after an AP failover) without
            // fabricating an error state.
            val port = 10425
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port)
            val receiver = NavShareReceiver(scope = scope, port = port, rejoinSilentMs = 300, openSocket = factory)
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

    @Test(timeout = 60_000)
    fun a_socket_hearing_traffic_is_never_recycled() =
        runBlocking {
            val port = 10426
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val factory = CountingSocketFactory(port)
            val sentence = NavShareProtocol.build(NavShareMessage(1, "ABC123", NavSharePayload.Bath))
            val receiver = NavShareReceiver(scope = scope, port = port, rejoinSilentMs = 1_000, openSocket = factory)
            try {
                receiver.start()
                val deadline = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < deadline) {
                    sendUdp(sentence, port)
                    Thread.sleep(100)
                }
                assertEquals("a socket that is hearing traffic must not be recycled", 1, factory.opened.size)
            } finally {
                receiver.stop()
                scope.cancel()
            }
        }

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

    /** Opens real fleet sockets on the given [port], counting every attempt (mirrors `NetworkLocationSourceTest`'s). */
    private class CountingSocketFactory(private val port: Int) : () -> MulticastSocket {
        val opened = CopyOnWriteArrayList<MulticastSocket>()
        val attempts = AtomicInteger()

        override fun invoke(): MulticastSocket {
            attempts.incrementAndGet()
            return openFleetNmeaSocket(port, FleetBus.NAV_GROUP).also { opened += it }
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
}
