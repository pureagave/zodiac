package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.ThreatProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
