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
 *
 * **The listener re-binds and re-joins by itself**, mirroring
 * `NetworkLocationSource`: a socket-open failure or a mid-loop read failure
 * backs off (see [backoffDelayMs]) and retries forever, and a socket that has
 * heard nothing for [rejoinSilentMs] is torn down and rebuilt so the
 * multicast group is joined afresh after a router power-cycle. Unlike the GPS
 * twin there is no `LocationSourceState` to update on failure — a failed
 * socket just means no threats until the next attempt, and the existing
 * [staleMs] watchdog already reports that honestly (contacts clear, feed
 * marked dead) without this listener needing a state channel of its own.
 */
class NetworkThreatSource(
    private val scope: CoroutineScope,
    private val applicationContext: Context? = null,
    private val port: Int = FleetBus.THREAT_PORT,
    private val staleMs: Long = STALE_MS,
    private val group: String = FleetBus.THREAT_GROUP,
    private val retryBaseMs: Long = RETRY_BASE_MS,
    private val retryMaxMs: Long = RETRY_MAX_MS,
    private val rejoinSilentMs: Long = REJOIN_SILENT_MS,
    /**
     * Seam for opening (bind + group join) the listening socket. Production
     * uses [openThreatSocket]; tests inject a factory that counts attempts
     * and can fail on demand, which is the only way to exercise a router
     * reboot without one.
     */
    private val openSocket: () -> MulticastSocket = { openThreatSocket(port, group) },
    /**
     * Seam for the retry backoff wait, mirroring `NetworkLocationSource`.
     * Tests record the requested delays instead of sleeping, so the policy is
     * asserted exactly rather than inferred from wall time. The default must
     * stay a real `delay`: it is what makes a [stop] during a backoff cancel
     * promptly instead of parking a thread.
     */
    private val backoffWait: suspend (Long) -> Unit = { delay(it) },
) : ThreatSource {
    private val _threats = MutableStateFlow<List<DriverThreat>>(emptyList())
    override val threats: StateFlow<List<DriverThreat>> = _threats.asStateFlow()

    private val _feedAlive = MutableStateFlow(false)
    override val feedAlive: StateFlow<Boolean> = _feedAlive.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null

    /**
     * The socket the listener is currently reading, or null while it is
     * between attempts (backing off after a failure, or rebuilding after
     * silence). Exactly one socket exists at a time.
     */
    private var socket: MulticastSocket? = null
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
                    if (nowMs() - lastRxMs > staleMs) {
                        // Feed has gone silent: mark it dead (so a routed source can
                        // fall back) and clear any lingering contacts to all-clear.
                        if (_feedAlive.value) _feedAlive.value = false
                        if (_threats.value.isNotEmpty()) _threats.value = emptyList()
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
        _threats.value = emptyList()
        _feedAlive.value = false
    }

    /**
     * Own the socket for as long as the job lives: open it, read it until it
     * breaks or goes silent, then open it again after a backoff. Mirrors
     * `NetworkLocationSource.runListener`; the attempt count is deliberately
     * unbounded for the same reason — a router reboot may take ten seconds or
     * an hour, and a listener that gives up after N tries is exactly the
     * threat-deaf-till-restart bug this replaces.
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
            val result = pump(listenerScope, sock)
            socket = null
            // Only traffic counts as progress. Resetting on a successful open
            // alone would let a socket that dies the instant it opens retry at
            // the base interval forever.
            if (result.received) failures = 0
            when (result.reason) {
                ListenEnd.STOPPED -> return
                ListenEnd.SILENT -> Unit // rebuild immediately; rejoinSilentMs is the rate limit
                ListenEnd.FAILED -> {
                    failures++
                    backoffWait(backoffDelayMs(failures))
                }
            }
        }
    }

    // Broad catch is deliberate: any socket/IO failure must come back as a
    // retryable null, never crash the IO coroutine. There is no state channel
    // to report the failure on — a dead socket just means no threats, and the
    // staleMs watchdog already reports that honestly. Swallowed rather than
    // logged for the same reason the original single-shot version was: no
    // logging channel existed here, and the retry loop is the response, not
    // the message.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun openSocketOrNull(): MulticastSocket? =
        try {
            openSocket()
        } catch (ex: Exception) {
            null
        }

    /**
     * Read [sock] until the job is cancelled, the socket breaks, or it has
     * heard nothing for [rejoinSilentMs]. Always closes [sock] on the way
     * out, so the caller may open a fresh one unconditionally.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun pump(
        listenerScope: CoroutineScope,
        sock: MulticastSocket,
    ): ListenResult {
        val buf = ByteArray(BUFFER_BYTES)
        var reason = ListenEnd.STOPPED
        var received = false
        var lastHeardMs = nowMs()
        try {
            while (listenerScope.isActive) {
                // Total silence for this long means the group membership is
                // presumed lost (see `NetworkLocationSource.pump`). Tracked
                // separately from [lastRxMs]: this timer resets on *any*
                // packet (proving the socket itself is still receiving),
                // while [lastRxMs] — and the liveness this listener reports —
                // only ever advances on a frame that actually parses.
                if (nowMs() - lastHeardMs > rejoinSilentMs) {
                    reason = ListenEnd.SILENT
                    break
                }
                val packet = receiveOrNull(sock, buf)
                if (packet != null) {
                    lastHeardMs = nowMs()
                    received = true
                    ingestPacket(packet)
                }
            }
        } catch (ex: Exception) {
            // A read throwing after stop() closed the socket is a normal
            // shutdown; only surface FAILED (and retry) while still active.
            if (listenerScope.isActive) reason = ListenEnd.FAILED
        } finally {
            closeSocket(sock)
        }
        return ListenResult(reason, received)
    }

    /**
     * Parse one datagram and, only on a successful [ThreatProtocol] frame,
     * stamp liveness and publish. Split out of [pump] to keep that loop
     * shallow — [pump] already counted this packet toward the rejoin-silence
     * timer regardless of whether it parses, so garbage on the port defers a
     * rejoin without ever claiming the vision feed is live.
     */
    private fun ingestPacket(packet: DatagramPacket) {
        ThreatProtocol.parse(String(packet.data, 0, packet.length, Charsets.US_ASCII))?.let {
            // Order matters: stamp liveness before publishing so a watchdog
            // tick can't see fresh contacts with a stale timestamp and clear
            // them. An empty frame is a valid "all clear" — still a live feed.
            lastRxMs = nowMs()
            _feedAlive.value = true
            _threats.value = it
        }
    }

    /**
     * One read, or null if the socket's receive timeout elapsed with nothing
     * on it. The swallow is deliberate — a read timeout is the normal
     * "nothing arrived this tick" path, and returning to the loop is what
     * lets the source notice cancellation (so [stop] unwinds promptly) and
     * notice silence (so a lost group membership gets re-joined).
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
     * Exponential backoff, capped: [retryBaseMs] × 2^(failures−1), never
     * above [retryMaxMs]. Same policy and same rationale as
     * `NetworkLocationSource.backoffDelayMs`.
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
    )

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
        private const val NANOS_PER_MS: Long = 1_000_000L

        /** First retry after a socket failure — short enough that a blip heals unnoticed. */
        private const val RETRY_BASE_MS: Long = 1_000L

        /** Ceiling on the backoff, i.e. the worst-case blindness after the network returns. */
        private const val RETRY_MAX_MS: Long = 30_000L

        /**
         * How long a bound socket may hear *nothing at all* before it is
         * rebuilt to re-join the multicast group. Not a guess: the feed is a
         * continuous 10 Hz stream (`ThreatBroadcaster`) that emits an empty
         * all-clear frame even with zero contacts, so any real gap between
         * frames is far under a second — [staleMs] (1.5 s) already fronts the
         * honest "no vision" state well before this timer could fire on a
         * merely-quiet feed. 10 s is a wide margin past any plausible gap in
         * that 10 Hz stream, yet still heals a router-reboot IGMP loss
         * (`NetworkLocationSource`'s [REJOIN_SILENT_MS] analog) within about
         * that window.
         */
        private const val REJOIN_SILENT_MS: Long = 10_000L

        /** Caps the shift in [backoffDelayMs] well short of Long overflow. */
        private const val BACKOFF_MAX_SHIFT: Int = 16
    }
}

/** How long a `receive` blocks before the loop re-checks cancellation/silence. */
private const val SOCKET_READ_TIMEOUT_MS: Int = 500

/**
 * Bind the threat socket and join the fixed multicast group. Split out of the
 * class so it is both the production implementation of [NetworkThreatSource]'s
 * `openSocket` seam and something a test can call directly after simulating a
 * failed attempt or two — mirrors `openFleetNmeaSocket` in
 * `NetworkLocationSource`.
 */
@Suppress("DEPRECATION")
internal fun openThreatSocket(
    port: Int,
    group: String,
): MulticastSocket =
    MulticastSocket(null).also { s ->
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        s.soTimeout = SOCKET_READ_TIMEOUT_MS
        // Join the fixed threat multicast group. runCatching: a host with no
        // multicast-capable interface (some CI) fails the join, but the
        // socket still receives unicast/broadcast to the port — so the
        // existing loopback tests keep working.
        runCatching { s.joinGroup(InetAddress.getByName(group)) }
    }
