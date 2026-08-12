package org.pureagave.zodiac.control.data.sensor

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import org.pureagave.zodiac.control.core.sensor.LocationSourceError
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.core.telemetry.AudioLevel
import org.pureagave.zodiac.control.core.telemetry.BeaconSensors
import org.pureagave.zodiac.control.core.telemetry.VehicleTelemetry
import org.pureagave.zodiac.control.data.sensor.nmea.NmeaParser
import java.net.DatagramPacket
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
 *
 * **The listener re-binds and re-joins by itself.** The travel router owns
 * AP/DHCP in the vehicle and *will* be power-cycled mid-night. Both ways that
 * breaks this source used to be terminal:
 *  - the bind or a read throws (interface gone) — the old code set
 *    [LocationSourceState.Error] and `return`ed out of the listener for good;
 *  - nothing throws at all, but the WiFi re-association silently drops the
 *    IGMP group membership, so `receive` just times out forever and the tablet
 *    listens to a group it is no longer in.
 *
 * Either way the tablet was dead to GPS for the rest of the night with no cab-side
 * remedy but a reinstall — and on a Fire, which has no GNSS, that is *no position
 * at all*. So the listener now loops: failures back off (see [backoffDelayMs])
 * and retry forever, and a socket that has heard nothing for [rejoinSilentMs] is
 * torn down and rebuilt so the group is joined afresh. State stays honest
 * throughout — [LocationSourceState.Error] while the socket is down,
 * [LocationSourceState.Searching] once re-bound but before a fix, never a claim
 * that the link is up.
 */
class NetworkLocationSource(
    private val scope: CoroutineScope,
    applicationContext: Context? = null,
    private val port: Int = FleetBus.TELEMETRY_PORT,
    private val group: String = FleetBus.TELEMETRY_GROUP,
    private val staleMs: Long = STALE_MS,
    private val beaconSilentMs: Long = BEACON_SILENT_MS,
    private val headingStaleMs: Long = HEADING_STALE_MS,
    private val retryBaseMs: Long = RETRY_BASE_MS,
    private val retryMaxMs: Long = RETRY_MAX_MS,
    private val rejoinSilentMs: Long = REJOIN_SILENT_MS,
    // Seam for the WifiManager.MulticastLock (AUDIT-2026-08-09 C5): Context
    // is null in every JVM unit test today, so without this parameter the
    // lock path — and its double-acquire bug — is never exercised by a test.
    // Real callers (ZodiacApplication) just pass a Context, same as before;
    // tests can inject a fake handle and assert acquire()/release() counts.
    private val multicastLockHandle: MulticastLockHandle =
        applicationContext?.let { WifiMulticastLockHandle(it) } ?: NoOpMulticastLockHandle,
    /**
     * Seam for opening (bind + group join) the listening socket. Production
     * uses [openFleetNmeaSocket]; tests inject a factory that counts attempts
     * and can fail on demand, which is the only way to exercise a router
     * reboot without one.
     */
    private val openSocket: () -> MulticastSocket = { openFleetNmeaSocket(port, group) },
    /**
     * Seam for the retry backoff wait — the injectable scheduler this repo
     * uses elsewhere ([FailoverLocationSource]'s `nowMs`, the burn-in
     * manager's clock). Tests record the requested delays instead of sleeping,
     * so the policy is asserted exactly rather than inferred from wall time.
     * The default must stay a real `delay`: it is what makes a [stop] during a
     * backoff cancel promptly instead of parking a thread.
     */
    private val backoffWait: suspend (Long) -> Unit = { delay(it) },
) : LocationSource {
    override val type: LocationSourceType = LocationSourceType.NET

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null

    /**
     * The socket the listener is currently reading, or null while it is
     * between attempts (backing off after a failure, or rebuilding after
     * silence). Exactly one socket exists at a time — every attempt closes its
     * own socket in [pump]'s `finally` before the next is opened — so a night
     * of retries cannot leak file descriptors.
     */
    @Volatile private var socket: MulticastSocket? = null

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
    // bundled into one flow for the cockpit.
    private val _beaconSensors = MutableStateFlow(BeaconSensors())
    val beaconSensors: StateFlow<BeaconSensors> = _beaconSensors.asStateFlow()

    /**
     * Mic level from `$ZAUD` (~15 Hz), kept OUT of [beaconSensors] on purpose:
     * it updates two orders of magnitude faster than the other channels, and
     * folding it in would rewrite that whole value — and every consumer of it —
     * fifteen times a second. The only subscriber is the passenger display's
     * audio visualiser; the driver-facing cockpit never reads it.
     *
     * Null when the beacon has gone quiet, so the visualiser can flatline
     * honestly rather than hold the last waveform forever.
     */
    private val _audioLevel = MutableStateFlow<AudioLevel?>(null)
    val audioLevel: StateFlow<AudioLevel?> = _audioLevel.asStateFlow()

    // Dedup state for $ZSHK (AUDIT-2026-08-09 C7): the beacon sends every
    // sentence to both the multicast group and the subnet-broadcast
    // fallback, and this source binds wildcard *and* joins the group — so on
    // any AP that forwards multicast, each datagram arrives twice. Every
    // other channel is idempotent full-state (a repeat just overwrites
    // itself with the same value); shockCount is a monotonic counter, so a
    // duplicate doubles it. See [isDuplicateShockLine].
    @Volatile private var lastShockLine: String? = null

    @Volatile private var lastShockLineMs: Long = 0L

    override suspend fun start() {
        if (job?.isActive == true) {
            // Already running. CockpitViewModel init calls `select(saved)`
            // then `start()` unconditionally right after — select() already
            // started this source, so this second call must be a genuine
            // no-op rather than cancel the live listener and re-acquire the
            // multicast lock out from under it (AUDIT-2026-08-09 C5).
            return
        }
        watchdog?.cancel()
        multicastLockHandle.acquire()
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
                        clearBeaconReadings()
                    }
                    delay(staleMs / 2)
                }
            }
    }

    override suspend fun stop() {
        // cancelAndJoin, not cancel: cancellation is cooperative, so a datagram
        // already inside ingest() would otherwise finish AFTER
        // clearBeaconReadings() below and repopulate the very readings we just
        // cleared. Invisible on a fast machine, reproducible on a loaded CI
        // runner -- and in production it means switching source can leave one
        // stale beacon reading behind, which is the whole bug stop() is here to
        // prevent. Joining first makes the clear the last word.
        job?.cancelAndJoin()
        watchdog?.cancelAndJoin()
        job = null
        watchdog = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
        multicastLockHandle.release()
        // A stopped source must not leave the last beacon readings looking
        // live — the watchdog above was the only thing that ever cleared
        // them, and stop() kills the watchdog without doing its job.
        clearBeaconReadings()
        _state.value = LocationSourceState.Disconnected
    }

    /**
     * Drop every beacon-derived reading. [BeaconSensors.shockCount] is
     * deliberately preserved: it is a monotonic event counter consumers diff
     * against, not a reading — rewinding it would swallow the next real
     * impact. Used by both [stop] and the silence watchdog so the two paths
     * cannot drift apart.
     */
    private fun clearBeaconReadings() {
        _beaconSensors.update { BeaconSensors(shockCount = it.shockCount) }
        _telemetry.value = null
        _audioLevel.value = null
    }

    /**
     * Own the socket for as long as the job lives: open it, read it until it
     * breaks or goes silent, then open it again after a backoff. Unlike the
     * attempt *interval*, the attempt *count* is deliberately unbounded — the
     * router may come back after ten seconds or after an hour, and a tablet
     * that gave up after N tries is exactly the terminal-Error bug this
     * replaces. What is bounded is the cost: one socket at a time, and a wait
     * that grows to [retryMaxMs] so an all-night outage costs a couple of binds
     * a minute rather than a spin.
     */
    private suspend fun runListener(listenerScope: CoroutineScope) {
        var failures = 0
        while (listenerScope.isActive) {
            val sock = openSocketOrNull()
            if (sock == null) {
                failures++
                backoffWait(backoffDelayMs(failures))
                continue
            }
            socket = sock
            // Bound and listening, nothing heard yet. Never assert Active here:
            // a re-bind proves only that the socket exists, not that the hub is
            // on the air. (An already-Active fix inside its staleness window is
            // left alone; the staleness watchdog owns demoting it.)
            if (_state.value !is LocationSourceState.Active) _state.value = LocationSourceState.Searching
            val result = pump(listenerScope, sock)
            socket = null
            // Only traffic counts as progress. Resetting on a successful bind
            // alone would let a socket that dies the instant it opens retry at
            // the base interval forever.
            if (result.received) failures = 0
            when (result.reason) {
                ListenEnd.STOPPED -> return
                ListenEnd.SILENT -> Unit // rebuild immediately; rejoinSilentMs is the rate limit
                ListenEnd.FAILED -> {
                    _state.value = LocationSourceState.Error("NET: ${result.detail}", LocationSourceError.IO_ERROR)
                    failures++
                    backoffWait(backoffDelayMs(failures))
                }
            }
        }
    }

    // Broad catch is deliberate: any socket/IO failure must surface as an Error
    // state and a retry, never crash the IO coroutine.
    @Suppress("TooGenericExceptionCaught")
    private fun openSocketOrNull(): MulticastSocket? =
        try {
            openSocket()
        } catch (ex: Exception) {
            _state.value =
                LocationSourceState.Error("NET: bind :$port failed — ${ex.message}", LocationSourceError.IO_ERROR)
            null
        }

    /**
     * Read [sock] until the job is cancelled, the socket breaks, or it has
     * heard nothing for [rejoinSilentMs]. Always closes [sock] on the way out,
     * so the caller may open a fresh one unconditionally.
     *
     * The broad catch is deliberate: any socket/IO failure must come back as a
     * retryable [ListenEnd.FAILED], never crash the IO coroutine.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun pump(
        listenerScope: CoroutineScope,
        sock: MulticastSocket,
    ): ListenResult {
        val buf = ByteArray(BUFFER_BYTES)
        var reason = ListenEnd.STOPPED
        var detail: String? = null
        var received = false
        var lastRxMs = nowMs()
        try {
            while (listenerScope.isActive) {
                // Total silence for this long means the group membership is
                // presumed lost: a WiFi re-association after a router reboot
                // takes the interface down and back up without ever failing a
                // read, so the reads keep timing out politely on a group we are
                // no longer a member of. Rebuilding is the only rejoin
                // available. Checked here rather than inside the timeout branch
                // below so it is a statement about *silence*, not about the
                // socket's age — a socket that is hearing the beacon reaches
                // this line just as often and must never be recycled.
                if (nowMs() - lastRxMs > rejoinSilentMs) {
                    reason = ListenEnd.SILENT
                    break
                }
                val packet = receiveOrNull(sock, buf)
                if (packet != null) {
                    lastRxMs = nowMs()
                    received = true
                    ingest(String(packet.data, 0, packet.length, Charsets.US_ASCII))
                }
            }
        } catch (ex: Exception) {
            // A read throwing after stop() closed the socket is a normal shutdown.
            if (listenerScope.isActive) {
                reason = ListenEnd.FAILED
                detail = ex.message
            }
        } finally {
            closeSocket(sock)
        }
        return ListenResult(reason, received, detail)
    }

    /**
     * One read, or null if the socket's receive timeout elapsed with nothing
     * on it. The swallow is deliberate — a read timeout is the normal "nothing
     * arrived this tick" path, and returning to the loop is what lets the
     * source notice cancellation (so [stop] unwinds promptly) and notice
     * silence (so a lost group membership gets re-joined).
     */
    @Suppress("SwallowedException")
    private fun receiveOrNull(
        sock: MulticastSocket,
        buf: ByteArray,
    ): DatagramPacket? {
        val packet = DatagramPacket(buf, buf.size)
        return try {
            sock.receive(packet)
            packet
        } catch (timeout: SocketTimeoutException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun closeSocket(sock: MulticastSocket) {
        runCatching { sock.leaveGroup(InetAddress.getByName(group)) }
        runCatching { sock.close() }
    }

    /**
     * Exponential backoff, capped: [retryBaseMs] × 2^(failures−1), never above
     * [retryMaxMs]. 1 s first — a momentary blip (the beacon phone flipping AP,
     * a brief deauth) heals before anyone in the cab notices. 30 s ceiling —
     * a router power-cycle is a ~30-60 s outage, so that is the longest anyone
     * waits past the network actually returning, while an all-night dead
     * router costs two binds a minute instead of a hot loop. No jitter: the
     * retry is a local bind plus an IGMP join, so ten tablets retrying in step
     * put nothing on the air worth spreading out.
     */
    private fun backoffDelayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, BACKOFF_MAX_SHIFT)
        val grown = retryBaseMs shl shift
        return grown.coerceIn(retryBaseMs, maxOf(retryBaseMs, retryMaxMs))
    }

    private enum class ListenEnd { STOPPED, SILENT, FAILED }

    /** Why [pump] returned, and whether anything at all was heard while it ran. */
    private data class ListenResult(
        val reason: ListenEnd,
        val received: Boolean,
        val detail: String? = null,
    )

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
            ingestSensorChannels(line)
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

    /**
     * The hub's own proprietary channels, split out from [ingest] so the
     * position/heading merge stays readable as one idea and this stays a flat
     * list of independent sentence handlers — adding a seventh channel
     * shouldn't make the fix logic harder to follow.
     */
    private fun ingestSensorChannels(line: String) {
        NmeaParser.parseVehicleTelemetry(line)?.let { _telemetry.value = it }
        NmeaParser.parseAmbientLight(line)?.let { al -> _beaconSensors.update { it.copy(ambientLight = al) } }
        NmeaParser.parseAudioLevel(line)?.let { _audioLevel.value = it }
        NmeaParser.parseBeaconHealth(line)?.let { h -> _beaconSensors.update { it.copy(beaconHealth = h) } }
        NmeaParser.parseOdometer(line)?.let { o -> _beaconSensors.update { it.copy(odometer = o) } }
        NmeaParser.parseShockEvent(line)?.let { s ->
            if (isDuplicateShockLine(line)) return@let
            _beaconSensors.update { it.copy(lastShockG = s.peakG, shockCount = it.shockCount + 1) }
        }
    }

    /**
     * True if [line] is byte-identical to the immediately preceding `$ZSHK`
     * line and arrived within [SHOCK_DEDUP_WINDOW_MS] of it — i.e. the AP's
     * second copy of the same datagram, not a second impact. The wire format
     * carries no sequence number, so exact-bytes-within-a-short-window is the
     * only signal available; the beacon's own shock detector has a 500 ms
     * refractory before it emits a second real event, well outside this
     * window. Always updates the dedup state, whether or not this call
     * reports a duplicate, so the *next* line is compared against the one
     * just seen.
     */
    private fun isDuplicateShockLine(line: String): Boolean {
        val now = nowMs()
        val duplicate = line == lastShockLine && now - lastShockLineMs <= SHOCK_DEDUP_WINDOW_MS
        lastShockLine = line
        lastShockLineMs = now
        return duplicate
    }

    private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

    private companion object {
        const val BUFFER_BYTES: Int = 2048
        const val STALE_MS: Long = 5_000L

        /** First retry after a socket failure — short enough that a blip heals unnoticed. */
        const val RETRY_BASE_MS: Long = 1_000L

        /** Ceiling on the backoff, i.e. the worst-case blindness after the network returns. */
        const val RETRY_MAX_MS: Long = 30_000L

        /**
         * How long a bound socket may hear *nothing at all* before it is
         * rebuilt to re-join the multicast group. Comfortably past
         * [BEACON_SILENT_MS] (12 s) so a hub that is merely quiet has already
         * been reported as such before we start recycling sockets underneath
         * it, and past any plausible gap in a 4 Hz feed.
         */
        const val REJOIN_SILENT_MS: Long = 20_000L

        /** Caps the shift in [backoffDelayMs] well short of Long overflow. */
        const val BACKOFF_MAX_SHIFT: Int = 16

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

        /**
         * How long a byte-identical repeat of a `$ZSHK` line is attributed to
         * the AP delivering the same physical impact twice rather than a
         * genuine second one (AUDIT-2026-08-09 C7). The beacon's own
         * refractory between real events is 500 ms; 200 ms comfortably covers
         * dual multicast/broadcast delivery of one datagram without eating
         * into that margin.
         */
        const val SHOCK_DEDUP_WINDOW_MS: Long = 200L
    }
}

/** How long a `receive` blocks before the loop re-checks cancellation. */
private const val SOCKET_READ_TIMEOUT_MS: Int = 1_000

/**
 * Bind the fleet NMEA socket and join the fixed multicast group. Split out of
 * the class so it is both the production implementation of
 * [NetworkLocationSource]'s `openSocket` seam and something a test can call
 * directly to hand the source a genuine working socket after simulating a
 * failed attempt or two.
 */
@Suppress("DEPRECATION")
internal fun openFleetNmeaSocket(
    port: Int,
    group: String,
): MulticastSocket =
    // NB: use also{}, not apply{} — inside apply the receiver's own `port`
    // property (−1 when unconnected) would shadow the argument.
    MulticastSocket(null).also { s ->
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        s.soTimeout = SOCKET_READ_TIMEOUT_MS
        // Join the fixed fleet multicast group. runCatching: a host with no
        // multicast-capable interface (some CI) fails the join, but the socket
        // still receives unicast/broadcast to the port — so tests and any
        // broadcast fallback keep working.
        runCatching { s.joinGroup(InetAddress.getByName(group)) }
    }

/**
 * Seam for the [WifiManager.MulticastLock] this source holds while running —
 * Android's WiFi driver filters broadcast/multicast frames out before they
 * reach an app to save power, and a held lock disables that filter so the
 * fleet's UDP broadcast actually arrives. Mirrors
 * [SystemLocationManagerHandle]: the real implementation talks to a live
 * [Context]; tests inject a fake and assert [acquire]/[release] call counts
 * directly, which the previous Context-only design made impossible — Context
 * is null in every JVM unit test, so the lock path (and its double-acquire
 * bug, AUDIT-2026-08-09 C5) was never exercised.
 */
interface MulticastLockHandle {
    val isHeld: Boolean

    fun acquire()

    fun release()
}

/** Used when there is no [Context] to hold a lock against (every JVM unit test today). */
private object NoOpMulticastLockHandle : MulticastLockHandle {
    override val isHeld: Boolean = false

    override fun acquire() = Unit

    override fun release() = Unit
}

/**
 * Not `private`: [org.pureagave.zodiac.control.data.nav.NavShareReceiver]
 * reuses this same seam under its own [tag] rather than inventing a second
 * `WifiManager.MulticastLock` wrapper.
 */
internal class WifiMulticastLockHandle(context: Context, tag: String = "zodiac-nmea") : MulticastLockHandle {
    private val lock: WifiManager.MulticastLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock(tag)
            ?.apply { setReferenceCounted(false) }

    override val isHeld: Boolean
        get() = lock?.isHeld == true

    // acquire()/release() are idempotent by construction — each is a no-op
    // when the lock is already in the requested state — so a caller that
    // forgets to check isHeld first (exactly the shape of the C5 bug) cannot
    // double-acquire or double-release through this seam.
    override fun acquire() {
        if (isHeld) return
        runCatching { lock?.acquire() }
    }

    override fun release() {
        if (!isHeld) return
        runCatching { lock?.release() }
    }
}
