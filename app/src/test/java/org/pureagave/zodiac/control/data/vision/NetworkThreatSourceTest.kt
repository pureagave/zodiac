package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun liveness_is_stamped_before_contacts_are_published() =
        runBlocking {
            // The ordering invariant the source documents but nothing pinned: if
            // contacts were published before the timestamp, a watchdog tick
            // landing in between would see fresh contacts against a stale clock
            // and clear them — dropping real people off the HUD intermittently.
            val port = 10224
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkThreatSource(scope = scope, port = port, staleMs = 400)
            try {
                source.start()
                val held =
                    waitUntil(4_000) {
                        sendUdp("ZTHREAT;7:12.0:0.6:1", port)
                        source.feedAlive.value && source.threats.value.size == 1
                    }
                assertTrue("contacts and liveness must be consistent", held)
                assertEquals(7, source.threats.value.first().id)
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
