package org.pureagave.zodiac.control.data.fleet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.pureagave.zodiac.control.core.telemetry.FleetObservation
import org.pureagave.zodiac.control.core.telemetry.FleetRoster
import org.pureagave.zodiac.control.core.telemetry.FleetRosterEntry
import org.pureagave.zodiac.control.core.telemetry.FleetVersion

/**
 * FLEET-1 phase 4: folds the received-peer table ([peers], from
 * [FleetVersionReceiver]) plus this device's own [self] build into the roster the
 * hero card renders, recomputed by the pure [FleetRoster.compute].
 *
 * Deliberately **off** `CockpitUiState` — a version roster changes on the order of
 * minutes (a reflash), not per frame, so it is a slow side-channel with its own
 * `StateFlow`; keeping it out of the cockpit state leaves the A5 hot path lean.
 *
 * Two things drive a recompute: a change in [peers] (a fresh announcement), and a
 * periodic [recomputeMs] tick — the tick is what lets a peer that has simply gone
 * *silent* flip to OFFLINE on the clock, since no datagram arrives to announce
 * absence (the one rule: silence reads as unknown/offline, never healthy). [now]
 * must share the clock base [FleetVersionReceiver] stamped observations with
 * (both default to the same process-global monotonic clock).
 *
 * Self is always folded in fresh as an `isSelf` observation, and this device's own
 * announcement — which it *does* hear back, being joined to its own multicast group
 * — is filtered out first by node so self is represented exactly once, always as
 * the authoritative self row (never aged to OFFLINE).
 */
class FleetVersionMonitor(
    peers: StateFlow<Map<String, FleetObservation>>,
    private val self: FleetVersion,
    scope: CoroutineScope,
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val recomputeMs: Long = RECOMPUTE_MS,
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    val roster: StateFlow<List<FleetRosterEntry>> =
        peers
            .combine(ticks()) { peerTable, _ -> peerTable }
            .map { peerTable -> FleetRoster.compute(observationsOf(peerTable), now(), staleAfterMs) }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = FleetRoster.compute(observationsOf(peers.value), now(), staleAfterMs),
            )

    /** Peers minus our own echo, plus self stamped fresh — so self is exactly one row and never OFFLINE. */
    private fun observationsOf(peerTable: Map<String, FleetObservation>): List<FleetObservation> {
        val others = peerTable.values.filter { it.version.node != self.node }
        return others + FleetObservation(self, lastHeardAtMs = now(), isSelf = true)
    }

    /** Emits immediately, then every [recomputeMs] — the heartbeat that ages silent peers to OFFLINE. */
    private fun ticks(): Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(recomputeMs)
            }
        }

    companion object {
        /** A peer unheard for this long reads OFFLINE (spec: ~3½ missed 10 s ticks). */
        const val STALE_AFTER_MS: Long = 35_000L

        /** How often the roster is re-evaluated so OFFLINE appears without a new datagram. */
        const val RECOMPUTE_MS: Long = 5_000L

        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
