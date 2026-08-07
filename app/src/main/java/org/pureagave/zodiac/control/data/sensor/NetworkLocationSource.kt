package org.pureagave.zodiac.control.data.sensor

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pureagave.zodiac.control.core.net.FleetBus
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.core.telemetry.BeaconSensors
import org.pureagave.zodiac.control.core.telemetry.VehicleTelemetry
import org.pureagave.zodiac.control.data.sensor.nmea.NmeaParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
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
    private val port: Int = FleetBus.TELEMETRY_PORT,
    private val group: String = FleetBus.TELEMETRY_GROUP,
    private val staleMs: Long = STALE_MS,
    private val beaconSilentMs: Long = BEACON_SILENT_MS,
    private val headingStaleMs: Long = HEADING_STALE_MS,
) : LocationSource {
    override val type: LocationSourceType = LocationSourceType.NET

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Telemetry arrives as separate sentences — position from GGA/RMC, compass
    // heading from HDT — so we hold the latest of each and emit a merged fix.
    @Volatile private var lastFix: GpsFix? = null

    @Volatile private var lastHeadingDeg: Double? = null

    // When a POSITION sentence (GGA/RMC) last arrived — tracked separately from
    // heading so a live compass can't keep a dead GPS looking alive.
    @Volatile private var positionRxMs: Long = 0L

    /**
     * Last time *any* line arrived — i.e. the hub is alive, whether or not it
     * currently has a fix. Distinct from [positionRxMs]: a beacon indoors keeps
     * reporting battery and heading with no GPS at all, and its readings are
     * still valid.
     */
    @Volatile private var beaconRxMs: Long = 0L

    /**
     * Last time a *compass* sentence arrived. Position had a staleness
     * watchdog; heading did not, so a dead compass channel froze
     * [lastHeadingDeg] and every subsequent fix overwrote its live GPS course
     * with that frozen value — while the source stayed cheerfully `Active`.
     * Map rotation, turn cues and the guidance chevron all steer off it.
     */
    @Volatile private var headingRxMs: Long = 0L

    // Vehicle IMU/motion telemetry from the Sensor Hub's ZTLM sentence, exposed
    // separately from the GPS fix for any consumer that wants tilt/speed.
    private val _telemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val telemetry: StateFlow<VehicleTelemetry?> = _telemetry.asStateFlow()

    // Low-rate Sensor Hub channels (ambient light, shock, health, odometer),
    // bundled into one flow for the cockpit. Audio ($ZAUD) is not consumed here.
    private val _beaconSensors = MutableStateFlow(BeaconSensors())
    val beaconSensors: StateFlow<BeaconSensors> = _beaconSensors.asStateFlow()

    override suspend fun start() {
        job?.cancel()
        watchdog?.cancel()
        acquireMulticastLock()
        _state.value = LocationSourceState.Searching
        job = scope.launch(Dispatchers.IO) { runListener(this) }
        watchdog =
            scope.launch {
                while (isActive) {
                    if (_state.value is LocationSourceState.Active && nowMs() - positionRxMs > staleMs) {
                        // Position feed died — demote off the frozen fix instead of
                        // guiding forever off a stale position (a live compass alone
                        // must not read as a healthy GPS).
                        _state.value = LocationSourceState.Searching
                    }
                    if (beaconRxMs != 0L && nowMs() - beaconRxMs > beaconSilentMs) {
                        // The hub itself has gone quiet — not just its position.
                        // Drop its readings rather than let the ops footer keep
                        // reporting a battery, satellite count and uptime from a
                        // beacon that died an hour ago. Silence is fine; a stale
                        // number presented as current is not.
                        //
                        // shockCount is monotonic and deliberately preserved: it's
                        // an event counter the ViewModel diffs against, and
                        // rewinding it would swallow the next real impact.
                        _beaconSensors.update { BeaconSensors(shockCount = it.shockCount) }
                    }
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
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "DEPRECATION")
    private fun runListener(listenerScope: CoroutineScope) {
        val sock =
            try {
                // NB: use also{}, not apply{} — inside apply the receiver's own
                // `port` property (−1 when unconnected) would shadow our ctor port.
                MulticastSocket(null).also { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(port))
                    s.soTimeout = READ_TIMEOUT_MS
                    // Join the fixed fleet multicast group. runCatching: a host
                    // with no multicast-capable interface (some CI) fails the
                    // join, but the socket still receives unicast/broadcast to
                    // the port — so tests and any broadcast fallback keep working.
                    runCatching { s.joinGroup(InetAddress.getByName(group)) }
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
            runCatching { sock.leaveGroup(InetAddress.getByName(group)) }
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
            NmeaParser.parseHeadingDeg(line)?.let {
                lastHeadingDeg = it
                headingRxMs = nowMs()
            }
            // Liveness is stamped only by sentences the hub itself emits. Any
            // NMEA sender sharing this port (a second GPS, a bring-up
            // forwarder) would otherwise keep a dead hub's battery, satellite
            // count and uptime on the ops footer indefinitely.
            if (line.startsWith(HUB_SENTENCE_PREFIX)) beaconRxMs = nowMs()
            NmeaParser.parseVehicleTelemetry(line)?.let { _telemetry.value = it }
            NmeaParser.parseAmbientLight(line)?.let { al -> _beaconSensors.update { it.copy(ambientLight = al) } }
            NmeaParser.parseBeaconHealth(line)?.let { h -> _beaconSensors.update { it.copy(beaconHealth = h) } }
            NmeaParser.parseOdometer(line)?.let { o -> _beaconSensors.update { it.copy(odometer = o) } }
            NmeaParser.parseShockEvent(line)?.let { s ->
                _beaconSensors.update { it.copy(lastShockG = s.peakG, shockCount = it.shockCount + 1) }
            }
            NmeaParser.parse(line)?.let {
                lastFix = it
                positionRxMs = nowMs()
            }
            val fix = lastFix ?: return@forEach
            // Only report Active while the POSITION itself is fresh; a heading- or
            // telemetry-only line must not re-assert Active on a stale position.
            if (nowMs() - positionRxMs <= staleMs) {
                // Prefer the compass (valid when stopped, unlike GPS course) —
                // but only while it is still arriving. A frozen compass must
                // fall back to course rather than steer the cockpit forever.
                val compass = lastHeadingDeg?.takeIf { nowMs() - headingRxMs <= headingStaleMs }
                _state.value = LocationSourceState.Active(fix.copy(headingDeg = compass ?: fix.headingDeg))
            }
        }
    }

    private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

    private companion object {
        const val BUFFER_BYTES: Int = 2048
        const val READ_TIMEOUT_MS: Int = 1_000
        const val STALE_MS: Long = 5_000L

        /**
         * How long the hub can be entirely silent before its readings are
         * dropped. The slowest channel (`$ZBCN` health) arrives every ~5 s, so
         * this is two missed beats — long enough to ride out a dropped
         * multicast burst, short enough that nobody reads a dead beacon's
         * battery as live.
         */
        const val BEACON_SILENT_MS: Long = 12_000L

        /**
         * How long a compass reading stays usable. Generous next to the 250 ms
         * broadcast cadence — this is catching a dead channel, not a dropped
         * packet — but far short of steering off a heading frozen minutes ago.
         */
        const val HEADING_STALE_MS: Long = 8_000L

        /** Proprietary prefix the Sensor Hub's own channels share (`$Z…`). */
        const val HUB_SENTENCE_PREFIX: String = "\u0024Z"
        const val NANOS_PER_MS: Long = 1_000_000L
    }
}
