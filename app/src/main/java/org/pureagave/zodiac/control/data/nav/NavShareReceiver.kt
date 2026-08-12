package org.pureagave.zodiac.control.data.nav

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
import org.pureagave.zodiac.control.core.ops.NavShareMessage
import org.pureagave.zodiac.control.core.ops.NavShareProtocol
import org.pureagave.zodiac.control.data.sensor.MulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.WifiMulticastLockHandle
import org.pureagave.zodiac.control.data.sensor.openFleetNmeaSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Receives `$ZNAV` on [FleetBus.NAV_GROUP]:[FleetBus.NAV_PORT], mirroring
 * [org.pureagave.zodiac.control.data.sensor.NetworkLocationSource]'s hardened
 * listener rather than inventing a second one: wildcard bind + group join
 * (reusing [openFleetNmeaSocket]), a held
 * [org.pureagave.zodiac.control.data.sensor.WifiMulticastLockHandle] (own tag,
 * `"zodiac-znav"`, so it doesn't share reference counting with the NMEA
 * lock), and the same rebuild-on-silence + capped-exponential-backoff loop.
 *
 * **Silence is the normal idle state here** — unlike GPS, there is no owner
 * broadcasting unless *some* tablet currently holds nav authority and has
 * set a target. Rebuilding the socket every [rejoinSilentMs] while idle is
 * still correct (it's what re-joins the multicast group after an AP
 * failover) and cheap; it just fires on a schedule instead of in response to
 * a dead feed. The subnet-broadcast leg (sent by every
 * [org.pureagave.zodiac.control.data.nav.NavShareSender]) covers delivery
 * even while multicast membership is transiently lost, same as the beacon
 * (spec R6/R7).
 *
 * Reception is universal (spec R4) — every device runs one of these, whether
 * or not it holds nav authority; only sending is gated. `start()` is
 * idempotent; `stop()` joins the listener before clearing so a datagram
 * already inside [ingest] can't repopulate [messages] after the clear.
 */
class NavShareReceiver(
    private val scope: CoroutineScope,
    applicationContext: Context? = null,
    private val port: Int = FleetBus.NAV_PORT,
    private val group: String = FleetBus.NAV_GROUP,
    private val retryBaseMs: Long = RETRY_BASE_MS,
    private val retryMaxMs: Long = RETRY_MAX_MS,
    private val rejoinSilentMs: Long = REJOIN_SILENT_MS,
    private val multicastLockHandle: MulticastLockHandle =
        applicationContext?.let { WifiMulticastLockHandle(it, tag = "zodiac-znav") } ?: NoOpNavMulticastLockHandle,
    private val openSocket: () -> MulticastSocket = { openFleetNmeaSocket(port, group) },
    private val backoffWait: suspend (Long) -> Unit = { delay(it) },
) {
    private val _messages = MutableStateFlow<NavShareMessage?>(null)
    val messages: StateFlow<NavShareMessage?> = _messages.asStateFlow()

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
        _messages.value = null
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
    // retryable null, never crash the listener coroutine. There is no state
    // flow here to carry the message (unlike NetworkLocationSource's Error
    // state) -- the caller's retry loop is the only consumer, and it only
    // needs to know "try again", not why this attempt failed.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun openSocketOrNull(): MulticastSocket? =
        try {
            openSocket()
        } catch (ex: Exception) {
            null
        }

    /**
     * The broad catch is deliberate: any socket/IO failure must come back as
     * a retryable [ListenEnd.FAILED], never crash the listener coroutine. The
     * exception itself is deliberately not surfaced further -- see
     * [openSocketOrNull]'s note; a rebuild-and-retry is the only response
     * either path has.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun pump(
        listenerScope: CoroutineScope,
        sock: MulticastSocket,
    ): ListenResult {
        val buf = ByteArray(BUFFER_BYTES)
        var reason = ListenEnd.STOPPED
        var received = false
        var lastRxMs = nowMs()
        try {
            while (listenerScope.isActive) {
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

    /** Split a datagram on `\n`, parse each line, publish the latest valid message. A malformed line is silently dropped -- never crashes, never publishes garbage (spec R9). */
    private fun ingest(datagram: String) {
        datagram.split('\n').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            NavShareProtocol.parse(line)?.let { _messages.value = it }
        }
    }

    private fun backoffDelayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, BACKOFF_MAX_SHIFT)
        val grown = retryBaseMs shl shift
        return grown.coerceIn(retryBaseMs, maxOf(retryBaseMs, retryMaxMs))
    }

    private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

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

/** Used when there is no [Context] to hold a lock against (every JVM unit test today) -- separate instance from [org.pureagave.zodiac.control.data.sensor.NetworkLocationSource]'s (that one is file-private). */
private object NoOpNavMulticastLockHandle : MulticastLockHandle {
    override val isHeld: Boolean = false

    override fun acquire() = Unit

    override fun release() = Unit
}
