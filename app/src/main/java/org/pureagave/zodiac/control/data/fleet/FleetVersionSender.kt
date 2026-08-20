package org.pureagave.zodiac.control.data.fleet

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.net.FleetBus
import org.pureagave.zodiac.control.core.net.FleetSendTargets
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * Announces this node's build on [group]:[port] as a `$ZVER` sentence (FLEET-1),
 * every [rebroadcastMs]. A near-clone of
 * [org.pureagave.zodiac.control.data.nav.NavShareSender], keeping the same
 * send-twice mechanics (belt-and-suspenders multicast + `/24` subnet broadcast)
 * and router-boot-race tolerance (socket opened lazily and retried every tick;
 * broadcast targets re-resolved every [targetRefreshMs] via [FleetSendTargets],
 * since a tablet can win the boot race against the travel router and have no
 * DHCP lease yet).
 *
 * The one structural difference from `$ZNAV`: **there is no authority gate.**
 * Every device on the fleet announces its own build — that is the whole point of
 * the monitor — so this has no follower/no-op variant. And the payload is fixed
 * for the process lifetime (a running build's identity does not change), so
 * unlike the nav target there is nothing to *replace* mid-run: [start] latches
 * one sentence and re-sends it. Each target is sent under its own `runCatching`
 * so a failing multicast leg can't block the broadcast fallback, or vice versa.
 */
class FleetVersionSender(
    private val scope: CoroutineScope,
    applicationContext: Context? = null,
    private val group: String = FleetBus.VERSION_GROUP,
    private val port: Int = FleetBus.VERSION_PORT,
    private val rebroadcastMs: Long = REBROADCAST_MS,
    private val targetRefreshMs: Long = TARGET_REFRESH_MS,
    private val openSocket: () -> DatagramSocket = {
        MulticastSocket().apply {
            timeToLive = FleetBus.TTL
            broadcast = true
        }
    },
    private val dhcpIp: () -> Int = { defaultDhcpIp(applicationContext) },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    @Volatile private var currentSentence: String? = null
    private var loopJob: Job? = null

    @Volatile private var socket: DatagramSocket? = null
    private var targets: List<InetAddress> = emptyList()
    private var targetsResolvedAtMs: Long? = null

    /**
     * Begin announcing [sentence] now, then every [rebroadcastMs] until [stop].
     * A build's identity is fixed for the process, so this is normally called
     * once; calling again replaces the sentence and restarts the timer.
     */
    fun start(sentence: String) {
        currentSentence = sentence
        loopJob?.cancel()
        loopJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    currentSentence?.let(::sendToTargets)
                    wait(rebroadcastMs)
                }
            }
    }

    /** Stop the periodic announcement and release the socket (process teardown). */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        currentSentence = null
        socket?.let { runCatching { it.close() } }
        socket = null
        targetsResolvedAtMs = null
    }

    private fun sendToTargets(sentence: String) {
        maintainTransport(System.currentTimeMillis())
        val sock = socket ?: return
        if (targets.isEmpty()) return
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        targets.forEach { dst ->
            runCatching { sock.send(DatagramPacket(bytes, bytes.size, dst, port)) }
        }
    }

    /** Open the socket if it isn't already, and re-derive the broadcast targets if they're stale. Both retried unconditionally on every tick — the router may not be up yet when this first runs. */
    private fun maintainTransport(nowMs: Long) {
        if (socket == null) {
            socket = runCatching { openSocket() }.getOrNull()
        }
        val last = targetsResolvedAtMs
        if (last != null && nowMs - last < targetRefreshMs) return
        targetsResolvedAtMs = nowMs
        val fresh = runCatching { FleetSendTargets.broadcastTargets(group, dhcpIp(), LIMITED_BROADCAST) }.getOrNull()
        if (fresh != null) targets = fresh
    }

    private companion object {
        const val REBROADCAST_MS: Long = 10_000L
        const val TARGET_REFRESH_MS: Long = 5_000L
        const val LIMITED_BROADCAST: String = "255.255.255.255"

        @Suppress("DEPRECATION")
        fun defaultDhcpIp(applicationContext: Context?): Int =
            (applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.dhcpInfo?.ipAddress ?: 0
    }
}
