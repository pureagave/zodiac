package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.sensor.nmea.NmeaParser
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Shared-WiFi GPS source: listens for NMEA datagrams broadcast over UDP on the
 * car's local network (the de-facto NMEA-over-IP port 10110) and feeds each
 * line into [NmeaParser], exactly like the USB/BLE sources feed their byte
 * streams. Same [LocationSourceState] contract and selector-chip pattern as
 * every other source.
 *
 * This is the production fleet path: one shared GPS broadcasts to every tablet,
 * so no tablet needs its own receiver. Bring-up is an Android GPS→UDP forwarder
 * on the XCover pushing NMEA to the tablets; production is a Pi + u-blox doing
 * the same. A datagram may carry one or several `\r\n`-terminated sentences;
 * each line is parsed independently. The socket uses a receive timeout so [stop]
 * can unwind the read loop promptly instead of blocking on `receive`.
 */
class NetworkLocationSource(
    private val scope: CoroutineScope,
    private val applicationContext: Context? = null,
    private val port: Int = DEFAULT_PORT,
) : LocationSource {
    override val type: LocationSourceType = LocationSourceType.NET

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Telemetry arrives as separate sentences — position from GGA/RMC, compass
    // heading from HDT — so we hold the latest of each and emit a merged fix.
    @Volatile private var lastFix: GpsFix? = null

    @Volatile private var lastHeadingDeg: Double? = null

    override suspend fun start() {
        job?.cancel()
        acquireMulticastLock()
        _state.value = LocationSourceState.Searching
        job = scope.launch(Dispatchers.IO) { runListener(this) }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
        releaseMulticastLock()
        _state.value = LocationSourceState.Disconnected
    }

    /**
     * Android's WiFi driver filters broadcast/multicast frames out before they
     * reach an app to save power; a held [WifiManager.MulticastLock] disables
     * that filter so we actually receive the fleet's UDP broadcast. No-op in
     * unit tests (no Context → loopback unicast needs no lock).
     */
    private fun acquireMulticastLock() {
        val wifi = applicationContext?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock =
            wifi.createMulticastLock("zodiac-nmea").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    // Broad catch is deliberate: any socket/IO failure must surface as an Error
    // state, never crash the IO coroutine. The SocketTimeoutException swallow is
    // also deliberate — a read timeout is the normal "nothing arrived this tick"
    // path that lets the loop re-check isActive so stop() unwinds promptly.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun runListener(listenerScope: CoroutineScope) {
        val sock =
            try {
                // NB: use also{}, not apply{} — inside apply the receiver is the
                // DatagramSocket, whose `port` property shadows our constructor
                // `port` (an unconnected socket reports port -1).
                DatagramSocket(null).also { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(port))
                    s.soTimeout = READ_TIMEOUT_MS
                    s.broadcast = true
                }
            } catch (ex: Exception) {
                _state.value = LocationSourceState.Error(detail = "NET: bind :$port failed — ${ex.message}")
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
                    continue // let the loop re-check isActive so stop() unwinds promptly
                }
                ingest(String(packet.data, 0, packet.length, Charsets.US_ASCII))
            }
        } catch (ex: Exception) {
            // A read throwing after stop() closed the socket is a normal shutdown.
            if (listenerScope.isActive) {
                _state.value = LocationSourceState.Error(detail = "NET: ${ex.message}")
            }
        } finally {
            runCatching { sock.close() }
        }
    }

    /**
     * Split a datagram into NMEA lines and merge them: position from GGA/RMC,
     * compass heading from HDT. Emits an [Active] fix whenever a position is
     * known, preferring the compass heading over GPS course (the compass is
     * valid when stopped; GPS course is not).
     */
    private fun ingest(datagram: String) {
        datagram.split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            NmeaParser.parseHeadingDeg(line)?.let { lastHeadingDeg = it }
            NmeaParser.parse(line)?.let { lastFix = it }
            val fix = lastFix ?: return@forEach
            _state.value = LocationSourceState.Active(fix.copy(headingDeg = lastHeadingDeg ?: fix.headingDeg))
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 10110
        private const val BUFFER_BYTES: Int = 2048
        private const val READ_TIMEOUT_MS: Int = 1_000
    }
}
