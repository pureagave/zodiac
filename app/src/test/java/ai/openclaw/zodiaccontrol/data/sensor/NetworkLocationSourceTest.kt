package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Exercises the UDP receive path end-to-end over real loopback sockets: a
 * datagram of NMEA in on the wire must come out as an [LocationSourceState.Active]
 * fix, and non-NMEA junk must not. Packets are re-sent across a window so the
 * test doesn't race the listener's bind (UDP has no pre-bind buffering).
 */
class NetworkLocationSourceTest {
    // Canonical valid GGA: 4807.038N 01131.000E → 48.1173, 11.5167 (checksum *47).
    private val validGga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"

    @Test
    fun receives_nmea_over_udp_and_emits_active_fix() =
        runBlocking {
            val port = 10176
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                val active =
                    waitUntil(4_000) {
                        sendUdp(validGga, port) // re-send until received; harmless once Active
                        source.state.value is LocationSourceState.Active
                    }
                assertTrue("a valid GGA over UDP should produce an Active fix; state=${source.state.value}", active)
                val fix = (source.state.value as LocationSourceState.Active).fix
                assertEquals(48.1173, fix.location.lat, 0.001)
                assertEquals(11.5167, fix.location.lon, 0.001)
            } finally {
                source.stop()
                scope.cancel()
            }
        }

    @Test
    fun non_nmea_garbage_does_not_produce_a_fix() =
        runBlocking {
            val port = 10177
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val source = NetworkLocationSource(scope = scope, port = port)
            try {
                source.start()
                // Blast junk across a window wide enough to cover the bind; the
                // parser must reject all of it and the source must stay Searching.
                val deadline = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < deadline) {
                    sendUdp("hello, this is not nmea\r\n", port)
                    Thread.sleep(30)
                }
                assertTrue(
                    "junk must never yield a fix",
                    source.state.value is LocationSourceState.Searching,
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
