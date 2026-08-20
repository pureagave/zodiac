package org.pureagave.zodiac.control.data.fleet

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pureagave.zodiac.control.core.net.FleetBus
import org.pureagave.zodiac.control.core.telemetry.FleetObservation
import org.pureagave.zodiac.control.core.telemetry.FleetPeerTable
import org.pureagave.zodiac.control.data.sensor.MulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.WifiMulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.openFleetNmeaSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Receives `$ZVER` build announcements on [FleetBus.VERSION_GROUP]:[FleetBus.VERSION_PORT]
 * (FLEET-1) and folds each into a per-node peer table via the pure
 * [FleetPeerTable]. Deliberately a near-clone of
 * [org.pureagave.zodiac.control.data.nav.NavShareReceiver] — the same hardened
 * listener the whole fleet uses: wildcard bind + group join (reusing
 * [openFleetNmeaSocket]), a held
 * [org.pureagave.zodiac.control.data.sensor.WifiMulticastLockHandle] under its
 * own tag (`"zodiac-zver"`, so its reference counting is independent of the NMEA
 * and `$ZNAV` locks), and the same rebuild-on-silence + capped-exponential-backoff
 * loop that re-joins the group after an AP failover.
 *
 * Unlike `$ZNAV` (one current target, a single `StateFlow`), a version roster is
 * a *set* of peers keyed by node, so the received state is a
 * `Map<node, FleetObservation>`: a fresh sighting upserts that one node —
 * overwriting its build the instant a reflashed device re-announces — and can
 * never evict another. Malformed or foreign datagrams leave the map untouched
 * ([FleetPeerTable] returns it unchanged), so garbage on the wire can neither
 * corrupt nor drop a peer.
 *
 * Staleness is **not** applied here (that is [org.pureagave.zodiac.control.core.telemetry.FleetRoster]'s
 * job from `now`): this only records what was heard and when, stamping each
 * observation with [now]. [now] defaults to a monotonic clock; the aggregator
 * that later reads these timestamps must compute against the same clock base.
 *
 * `start()` is idempotent; `stop()` joins the listener before clearing so a
 * datagram already inside [ingest] can't repopulate [peers] after the clear.
 */
class FleetVersionReceiver(
    private val scope: CoroutineScope,
    applicationContext: Context? = null,
    private val port: Int = FleetBus.VERSION_PORT,
    private val group: String = FleetBus.VERSION_GROUP,
    private val retryBaseMs: Long = RETRY_BASE_MS,
    private val retryMaxMs: Long = RETRY_MAX_MS,
    private val rejoinSilentMs: Long = REJOIN_SILENT_MS,
    private val multicastLockHandle: MulticastLockHandle =
        applicationContext?.let { WifiMulticastLockHandle(it, tag = "zodiac-zver") } ?: NoOpFleetMulticastLockHandle,
    private val openSocket: () -> MulticastSocket = { openFleetNmeaSocket(port, group) },
    private val backoffWait: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val _peers = MutableStateFlow<Map<String, FleetObservation>>(emptyMap())
    val peers: StateFlow<Map<String, FleetObservation>> = _peers.asStateFlow()

    private var job: Job? = null

    @Volatile private var socket: MulticastSocket? = null

    suspend fun start() {
        if (job?.isActive == true) return
        multicastLockHandle.acquire()
        job = scope.launch(Dispatchers.IO) { runListener(this) }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(Dispatchers.IO) {
            runCatching { socket?.close() }
            socket = null
        }
        multicastLockHandle.release()
        _peers.value = emptyMap()
    }

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

    // Broad catch is deliberate: any bind/IO failure must come back as a
    // retryable null, never crash the listener coroutine. No state flow carries
    // the reason (the retry loop only needs "try again"), mirroring
    // NavShareReceiver.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun openSocketOrNull(): MulticastSocket? =
        try {
            openSocket()
        } catch (ex: Exception) {
            null
        }

    /**
     * The broad catch is deliberate: any socket/IO failure must come back as a
     * retryable [ListenEnd.FAILED], never crash the listener coroutine. The
     * exception is not surfaced further — a rebuild-and-retry is the only
     * response either path has.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun pump(
        listenerScope: CoroutineScope,
        sock: MulticastSocket,
    ): ListenResult {
        val buf = ByteArray(BUFFER_BYTES)
        var reason = ListenEnd.STOPPED
        var received = false
        var lastRxMs = now()
        try {
            while (listenerScope.isActive) {
                if (now() - lastRxMs > rejoinSilentMs) {
                    reason = ListenEnd.SILENT
                    break
                }
                val packet = receiveOrNull(sock, buf)
                if (packet != null) {
                    lastRxMs = now()
                    received = true
                    ingest(String(packet.data, 0, packet.length, Charsets.US_ASCII))
                }
            }
        } catch (ex: Exception) {
            if (listenerScope.isActive) reason = ListenEnd.FAILED
        } finally {
            closeSocket(sock)
        }
        return ListenResult(reason, received)
    }

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
     * Split a datagram on `\n` and fold each line into the peer table. A malformed
     * or foreign line is a no-op ([FleetPeerTable.ingest] returns the map
     * unchanged) — never crashes, never evicts a peer, never records garbage.
     */
    private fun ingest(datagram: String) {
        datagram.split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            _peers.value = FleetPeerTable.ingest(_peers.value, line, now())
        }
    }

    private fun backoffDelayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, BACKOFF_MAX_SHIFT)
        val grown = retryBaseMs shl shift
        return grown.coerceIn(retryBaseMs, maxOf(retryBaseMs, retryMaxMs))
    }

    private enum class ListenEnd { STOPPED, SILENT, FAILED }

    private data class ListenResult(val reason: ListenEnd, val received: Boolean)

    private companion object {
        const val BUFFER_BYTES: Int = 2048
        const val RETRY_BASE_MS: Long = 1_000L
        const val RETRY_MAX_MS: Long = 30_000L
        const val REJOIN_SILENT_MS: Long = 30_000L
        const val BACKOFF_MAX_SHIFT: Int = 16
        const val NANOS_PER_MS: Long = 1_000_000L
    }
}

/** Used when there is no [Context] to hold a lock against (every JVM unit test today). */
private object NoOpFleetMulticastLockHandle : MulticastLockHandle {
    override val isHeld: Boolean = false

    override fun acquire() = Unit

    override fun release() = Unit
}
