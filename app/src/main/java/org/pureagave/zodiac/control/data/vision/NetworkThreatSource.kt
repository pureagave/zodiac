package org.pureagave.zodiac.control.data.vision

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pureagave.zodiac.control.core.net.FleetBus
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.ThreatProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Receives thermal detections broadcast by the edge box (Jetson) over UDP —
 * one [ThreatProtocol] frame per datagram on port 10120 — and exposes them as
 * the live contact list. Same shape as `NetworkLocationSource`: binds a
 * datagram socket, holds a [WifiManager.MulticastLock] so Android doesn't
 * filter the broadcast, and unwinds promptly on [stop] via a read timeout.
 *
 * A watchdog clears the contacts to empty when no frame has arrived for
 * [staleMs] — so a dropped feed reads as "all clear / no data" rather than a
 * frozen stale threat stuck on screen. That empty state is what lets a routed
 * source fall back to a demo/fake feed.
 */
class NetworkThreatSource(
    private val scope: CoroutineScope,
    private val applicationContext: Context? = null,
    private val port: Int = FleetBus.THREAT_PORT,
    private val staleMs: Long = STALE_MS,
    private val group: String = FleetBus.THREAT_GROUP,
) : ThreatSource {
    private val _threats = MutableStateFlow<List<DriverThreat>>(emptyList())
    override val threats: StateFlow<List<DriverThreat>> = _threats.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var lastRxMs: Long = 0L

    override suspend fun start() {
        job?.cancel()
        watchdog?.cancel()
        acquireMulticastLock()
        job = scope.launch(Dispatchers.IO) { runListener(this) }
        watchdog =
            scope.launch {
                while (isActive) {
                    if (_threats.value.isNotEmpty() && nowMs() - lastRxMs > staleMs) _threats.value = emptyList()
                    delay(staleMs / 2)
                }
            }
    }

    override suspend fun stop() {
        job?.cancel()
        watchdog?.cancel()
        job = null
        watchdog = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
        releaseMulticastLock()
        _threats.value = emptyList()
    }

    // Broad + timeout catches are deliberate (IO boundary): a timeout is the
    // normal "nothing this tick" path; other failures should not crash the loop.
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "DEPRECATION")
    private fun runListener(listenerScope: CoroutineScope) {
        val sock =
            try {
                MulticastSocket(null).also { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(port))
                    s.soTimeout = READ_TIMEOUT_MS
                    runCatching { s.joinGroup(InetAddress.getByName(group)) }
                }
            } catch (ex: Exception) {
                // No state channel for errors here; a dead socket just means no
                // threats (the HUD falls back). Swallow and bail.
                return
            }
        socket = sock
        val buf = ByteArray(BUFFER_BYTES)
        try {
            while (listenerScope.isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                }
                ThreatProtocol.parse(String(packet.data, 0, packet.length, Charsets.US_ASCII))?.let {
                    _threats.value = it
                    lastRxMs = nowMs()
                }
            }
        } catch (ex: Exception) {
            // socket closed on stop() → normal shutdown.
        } finally {
            runCatching { sock.leaveGroup(InetAddress.getByName(group)) }
            runCatching { sock.close() }
        }
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock =
            wifi.createMulticastLock("zodiac-threats").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

    companion object {
        const val STALE_MS: Long = 1_500L
        private const val BUFFER_BYTES: Int = 4096
        private const val READ_TIMEOUT_MS: Int = 500
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
