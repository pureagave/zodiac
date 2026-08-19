package org.pureagave.zodiac.control.data.vision

import android.content.Context
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
import org.pureagave.zodiac.control.core.vision.CoverageProtocol
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.ThreatProtocol
import org.pureagave.zodiac.control.data.sensor.MulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.NoOpMulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.WifiMulticastLockHandle
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Receives thermal detections broadcast by the edge box (Jetson) over UDP —
 * one [ThreatProtocol] frame per datagram on port 10120 — and exposes them as
 * the live contact list. Same shape as `NetworkLocationSource`: binds a
 * datagram socket, holds a [MulticastLockHandle] so Android doesn't filter
 * the broadcast, and unwinds promptly on [stop] via a read timeout.
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
    private val coverageStaleMs: Long = COVERAGE_STALE_MS,
    private val group: String = FleetBus.THREAT_GROUP,
    private val retryBaseMs: Long = RETRY_BASE_MS,
    private val retryMaxMs: Long = RETRY_MAX_MS,
    private val rejoinSilentMs: Long = REJOIN_SILENT_MS,
    // Seam for the WifiManager.MulticastLock (mirrors `NetworkLocationSource`,
    // AUDIT-2026-08-09 C5): Context is null in every JVM unit test today, so
    // without this parameter the lock path — and its double-acquire bug — is
    // never exercised by a test. Real callers (ZodiacApplication) just pass a
    // Context, same as before; tests can inject a fake handle and assert
    // acquire()/release() counts.
    private val multicastLockHandle: MulticastLockHandle =
        applicationContext?.let { WifiMulticastLockHandle(it, "zodiac-threats") } ?: NoOpMulticastLockHandle,
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
    /**
     * Seam for the clock, mirroring the injectable clocks elsewhere in this
     * repo (`FailoverLocationSource`'s `nowMs`, the burn-in manager). Lets a
     * test observe exactly what state is visible at the moment liveness is
     * stamped, without which the ingest-ordering invariant this class
     * documents was never actually pinned.
     */
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) : ThreatSource {
    private val _threats = MutableStateFlow<List<DriverThreat>>(emptyList())
    override val threats: StateFlow<List<DriverThreat>> = _threats.asStateFlow()

    private val _feedAlive = MutableStateFlow(false)
    override val feedAlive: StateFlow<Boolean> = _feedAlive.asStateFlow()

    private val _coverage = MutableStateFlow<List<ClosedFloatingPointRange<Float>>?>(null)
    override val coverage: StateFlow<List<ClosedFloatingPointRange<Float>>?> = _coverage.asStateFlow()

    private var job: Job? = null
    private var watchdog: Job? = null

    /**
     * The socket the listener is currently reading, or null while it is
     * between attempts (backing off after a failure, or rebuilding after
     * silence). Exactly one socket exists at a time.
     */
    @Volatile private var socket: MulticastSocket? = null

    @Volatile private var lastRxMs: Long = 0L

    /**
     * When the last `ZCOVER` frame landed — its own timestamp, independent of
     * [lastRxMs]. Coverage and threat liveness expire on separate clocks so a
     * coverage-only stream never marks the threat feed alive (R4), and a stale
     * coverage signal falls back to the ring's static assumption without
     * disturbing the threat feed's own staleness.
     */
    @Volatile private var lastCoverageMs: Long = 0L

    override suspend fun start() {
        if (job?.isActive == true) {
            // Already running. RoutedThreatSource may call start() more than
            // once for the same source; a redundant start must be a genuine
            // no-op rather than cancel the live listener and re-acquire the
            // multicast lock out from under it (mirrors `NetworkLocationSource`,
            // AUDIT-2026-08-09 C5).
            return
        }
        watchdog?.cancel()
        multicastLockHandle.acquire()
        job = scope.launch(Dispatchers.IO) { runListener(this) }
        watchdog =
            scope.launch {
                while (isActive) {
                    if (nowMs() - lastRxMs > staleMs) {
                        // Feed has gone silent: mark it dead (so a routed source can
                        // fall back) and clear any lingering contacts to all-clear.
                        // update{} re-reads lastRxMs inside the CAS rather than
                        // writing unconditionally: ingest() stamps lastRxMs
                        // *before* it publishes, so a frame landing between the
                        // outer check above and this clear makes the retried CAS
                        // see the fresh timestamp and keep the frame — closing
                        // the check-then-clear race that would otherwise flash a
                        // real contact to ABSENT for one frame.
                        _feedAlive.update { alive -> if (nowMs() - lastRxMs > staleMs) false else alive }
                        _threats.update { contacts -> if (nowMs() - lastRxMs > staleMs) emptyList() else contacts }
                    }
                    // Coverage expires on its own, slower clock: a Jetson that
                    // stops sending ZCOVER (crashed, or an old build) drops the
                    // ring back to its static coverage assumption, never leaving
                    // a stale watched/blind picture on the HUD. Independent of
                    // the threat watchdog above so the two never interfere.
                    if (nowMs() - lastCoverageMs > coverageStaleMs) {
                        _coverage.update { cov -> if (nowMs() - lastCoverageMs > coverageStaleMs) null else cov }
                    }
                    delay(staleMs / 2)
                }
            }
    }

    override suspend fun stop() {
        // cancelAndJoin, not cancel: cancellation is cooperative, so a
        // datagram already inside ingestFrame() would otherwise finish AFTER
        // the clears below and repopulate the very state we just cleared
        // (mirrors `NetworkLocationSource.stop()`). Joining first makes the
        // clears the last word.
        job?.cancelAndJoin()
        watchdog?.cancelAndJoin()
        job = null
        watchdog = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
        multicastLockHandle.release()
        _threats.value = emptyList()
        _feedAlive.value = false
        _coverage.value = null
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
     * Decode one datagram to a string and delegate to [ingestFrame]. Split out
     * of [pump] to keep that loop shallow — [pump] already counted this packet
     * toward the rejoin-silence timer regardless of whether it parses, so
     * garbage on the port defers a rejoin without ever claiming the vision
     * feed is live.
     */
    private fun ingestPacket(packet: DatagramPacket) {
        ingestFrame(String(packet.data, 0, packet.length, Charsets.US_ASCII))
    }

    /**
     * Parse one frame and route it to its channel. A [ThreatProtocol] frame
     * stamps threat liveness and publishes contacts; a [CoverageProtocol]
     * frame publishes coverage on its own timestamp and **never touches**
     * [lastRxMs]/[_feedAlive]/[_threats] — a coverage-only stream must not
     * resurrect a dead threat feed (R4). Split out of [ingestPacket] so a test
     * can drive it directly with no socket involved.
     */
    internal fun ingestFrame(frame: String) {
        val threats = ThreatProtocol.parse(frame)
        if (threats != null) {
            // Order matters: stamp liveness before publishing so a watchdog
            // tick can't see fresh contacts with a stale timestamp and clear
            // them. An empty frame is a valid "all clear" — still a live feed.
            lastRxMs = nowMs()
            _feedAlive.value = true
            _threats.value = threats
            return
        }
        // Only if it wasn't a ZTHREAT frame: try the coverage channel. This
        // path stamps ONLY the coverage timestamp/flow, preserving the ABSENT
        // semantics of a feed that is emitting coverage but no threats.
        val coverage = CoverageProtocol.parse(frame)
        if (coverage != null) {
            lastCoverageMs = nowMs()
            _coverage.value = coverage
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

    companion object {
        const val STALE_MS: Long = 1_500L

        /**
         * How long a `ZCOVER` signal is trusted after the last one arrives.
         * Wider than [STALE_MS] because coverage is a 1 Hz heartbeat, not the
         * 10 Hz threat stream — 5 s is several missed heartbeats, past which the
         * ring reverts to its static coverage assumption rather than trusting a
         * stale picture.
         */
        const val COVERAGE_STALE_MS: Long = 5_000L
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
