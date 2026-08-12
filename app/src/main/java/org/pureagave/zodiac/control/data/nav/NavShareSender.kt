package org.pureagave.zodiac.control.data.nav

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

/** What [org.pureagave.zodiac.control.ui.viewmodel.NavShareController] sends `$ZNAV` sentences through — real transmit or a no-op, injected so a follower device never touches the network at all. */
interface NavSharePublisher {
    fun publish(sentence: String)

    fun stop()
}

/**
 * The app's first device-to-device transmit path: broadcasts `$ZNAV`
 * sentences on [group]:[port], mirroring `:beacon`'s `TelemetryBroadcaster`
 * send-twice mechanics (belt-and-suspenders multicast + `/24` subnet
 * broadcast) rather than inventing new ones.
 *
 * One coroutine, one sentence: [publish] replaces the current sentence and
 * restarts the periodic re-send timer, sending immediately and then every
 * [rebroadcastMs] — the owning tablet's periodic re-broadcast (spec R6) that
 * lets a rebooted/cold-started follower re-sync within seconds. Broadcast
 * targets are re-resolved every [targetRefreshMs] (mirrors the beacon's own
 * cadence) via [FleetSendTargets], since the phone/tablet may win the boot
 * race against the travel router and have no DHCP lease yet. The socket is
 * opened lazily and retried on every tick — the same router-boot-race
 * tolerance [org.pureagave.zodiac.control.data.sensor.NetworkLocationSource]
 * uses on the receive side. Each target is sent under its own `runCatching`
 * so a failing multicast leg can't block the broadcast fallback, or vice
 * versa.
 */
class NavShareSender(
    private val scope: CoroutineScope,
    applicationContext: Context? = null,
    private val group: String = FleetBus.NAV_GROUP,
    private val port: Int = FleetBus.NAV_PORT,
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
) : NavSharePublisher {
    @Volatile private var currentSentence: String? = null
    private var loopJob: Job? = null

    @Volatile private var socket: DatagramSocket? = null
    private var targets: List<InetAddress> = emptyList()
    private var targetsResolvedAtMs: Long? = null

    /** Send [sentence] now, then keep re-sending it every [rebroadcastMs] until the next [publish] or [stop]. */
    override fun publish(sentence: String) {
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

    /** Yield: stop the periodic re-send and release the socket. Called when this device adopts a higher-seq remote target (ownership transferred away) or the process is tearing down. */
    override fun stop() {
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
        const val REBROADCAST_MS: Long = 3_000L
        const val TARGET_REFRESH_MS: Long = 5_000L
        const val LIMITED_BROADCAST: String = "255.255.255.255"

        @Suppress("DEPRECATION")
        fun defaultDhcpIp(applicationContext: Context?): Int =
            (applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.dhcpInfo?.ipAddress ?: 0
    }
}

/** No-op publisher for devices without nav authority (spec R3/R4) — a follower never opens a socket at all. */
object NoOpNavSharePublisher : NavSharePublisher {
    override fun publish(sentence: String) = Unit

    override fun stop() = Unit
}
