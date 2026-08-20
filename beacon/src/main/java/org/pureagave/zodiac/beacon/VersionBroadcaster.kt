package org.pureagave.zodiac.beacon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket

/** A DHCP lease's address + netmask (Android's little-endian `DhcpInfo` form). 0/0 = no lease yet. */
internal data class DhcpLease(val ipAddress: Int, val netmask: Int)

/**
 * This beacon's own build as a `$ZVER` sentence (FLEET-1). Node is derived from
 * the device's `ANDROID_ID` (sanitised to the grammar by [Nmea.zver]); name is
 * the model; the rest comes straight from the FLEET-2 `BuildConfig` fields, so an
 * unidentifiable build carries `unknown`/dirty and renders *unknown* on the hero,
 * never a confident current.
 */
internal fun beaconVersionSentence(
    androidId: String,
    model: String,
): String =
    Nmea.zver(
        node = androidId,
        name = model,
        base = BuildConfig.VERSION_BASE,
        sha = BuildConfig.GIT_SHA,
        dirty = BuildConfig.GIT_DIRTY,
        epoch = BuildConfig.GIT_COMMIT_EPOCH_SECONDS,
    )

/**
 * Announces the beacon's build on the FLEET-1 version bus
 * (`239.7.7.40:10140`) — deliberately a **separate** socket and coroutine from
 * [TelemetryBroadcaster], so the fleet's only GNSS transmit path is left
 * byte-untouched: a fault in version emit can never take GPS off the wire.
 *
 * Mirrors the app's `NavShareSender`/`FleetVersionSender` send-twice mechanics
 * (belt-and-suspenders multicast + `/24`-aware subnet broadcast via [BeaconNet])
 * and the same router-boot-race tolerance the beacon's own broadcaster uses: the
 * socket is opened lazily and retried every tick, and the broadcast targets are
 * re-resolved every [targetRefreshMs] since the phone can win the boot race
 * against the travel router and have no DHCP lease yet. Each target is sent under
 * its own `runCatching` so a failing leg can't block the other. The payload is
 * fixed for the process (a running build's identity doesn't change): [start]
 * latches one sentence and re-sends it every [rebroadcastMs].
 */
internal class VersionBroadcaster(
    private val scope: CoroutineScope,
    private val group: String = VERSION_GROUP,
    private val port: Int = VERSION_PORT,
    private val rebroadcastMs: Long = REBROADCAST_MS,
    private val targetRefreshMs: Long = TARGET_REFRESH_MS,
    private val openSocket: () -> DatagramSocket = {
        MulticastSocket().apply {
            timeToLive = TTL
            broadcast = true
        }
    },
    private val dhcp: () -> DhcpLease = { DhcpLease(0, 0) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    @Volatile private var currentSentence: String? = null
    private var loopJob: Job? = null

    @Volatile private var socket: DatagramSocket? = null
    private var targets: List<InetAddress> = emptyList()
    private var targetsResolvedAtMs: Long? = null

    /** Begin announcing [sentence] now, then every [rebroadcastMs] until [stop]. Idempotent-ish: a second call replaces the sentence and restarts the timer. */
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

    /** Stop announcing and release the socket (service teardown). */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        currentSentence = null
        socket?.let { runCatching { it.close() } }
        socket = null
        targetsResolvedAtMs = null
    }

    private fun sendToTargets(sentence: String) {
        maintainTransport(nowMs())
        val sock = socket ?: return
        if (targets.isEmpty()) return
        val bytes = sentence.toByteArray(Charsets.US_ASCII)
        targets.forEach { dst ->
            runCatching { sock.send(DatagramPacket(bytes, bytes.size, dst, port)) }
        }
    }

    /** Open the socket if needed and re-derive the broadcast targets if stale — both retried every tick, since the router may not be up when this first runs. */
    private fun maintainTransport(now: Long) {
        if (socket == null) {
            socket = runCatching { openSocket() }.getOrNull()
        }
        val last = targetsResolvedAtMs
        if (last != null && now - last < targetRefreshMs) return
        targetsResolvedAtMs = now
        val lease = dhcp()
        val fresh =
            runCatching {
                BeaconNet.broadcastTargets(group, lease.ipAddress, lease.netmask, LIMITED_BROADCAST)
            }.getOrNull()
        if (fresh != null) targets = fresh
    }

    companion object {
        // Mirror of the app's FleetBus.VERSION_* and the Jetson's fleet_bus.
        const val VERSION_GROUP = "239.7.7.40"
        const val VERSION_PORT = 10140
        private const val TTL = 1
        private const val REBROADCAST_MS = 10_000L
        private const val TARGET_REFRESH_MS = 5_000L
        private const val LIMITED_BROADCAST = "255.255.255.255"
    }
}
