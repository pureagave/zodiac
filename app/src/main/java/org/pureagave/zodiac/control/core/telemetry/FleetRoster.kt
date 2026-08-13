package org.pureagave.zodiac.control.core.telemetry

/**
 * A build announcement we are holding: the [version], when we last heard it
 * ([lastHeardAtMs]), and whether it is our own. Self is folded in as an
 * observation too, marked [isSelf] — we know our own build directly, not off the
 * wire, so it is always fresh.
 */
data class FleetObservation(
    val version: FleetVersion,
    val lastHeardAtMs: Long,
    val isSelf: Boolean = false,
)

/** How a node's build compares to the newest the fleet is actually running. */
enum class FleetStatus { CURRENT, BEHIND, UNKNOWN, OFFLINE }

/** One row of the version roster: a node, its [status], sorted worst-first by [FleetRoster]. */
data class FleetRosterEntry(
    val version: FleetVersion,
    val status: FleetStatus,
    val isSelf: Boolean,
)

/**
 * Pure aggregation of build announcements into a roster (FLEET-1). No I/O and no
 * clock of its own — [nowMs] is injected — so it is unit-tested against fixed
 * inputs.
 *
 * **"latest"** is the newest commit-epoch among the *trustworthy references*: the
 * known, clean, epoch-bearing builds. Newest build wins; there is no server and
 * no notion of HEAD, only the newest build any peer is actually running. A dirty
 * or unidentifiable build never sets the bar (it can't be trusted as a
 * reference) and never reads CURRENT.
 *
 * **The one rule** (see `design/FLEET-1-version-monitor-spec.md`): silence reads
 * as unknown, never healthy. A peer not heard from within [staleAfterMs] is
 * OFFLINE regardless of the build it last claimed; a build that can't identify
 * itself (dirty, `unknown` sha, or epoch 0) is UNKNOWN, never CURRENT. Self never
 * goes OFFLINE — we read our own build directly.
 *
 * Rows are deduplicated by node (freshest wins) and sorted worst-status-first, so
 * a stale or unknown device sits at the top where it will be seen.
 */
object FleetRoster {
    fun compute(
        observations: List<FleetObservation>,
        nowMs: Long,
        staleAfterMs: Long,
    ): List<FleetRosterEntry> {
        val latest =
            observations
                .filter { it.version.identity.isTrustworthyReference }
                .maxOfOrNull { it.version.identity.commitEpochSeconds }
        return observations
            .groupBy { it.version.node }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.lastHeardAtMs } }
            .map { obs -> FleetRosterEntry(obs.version, statusOf(obs, latest, nowMs, staleAfterMs), obs.isSelf) }
            .sortedWith(compareBy({ it.status.severity }, { it.version.name }))
    }

    private fun statusOf(
        obs: FleetObservation,
        latest: Long?,
        nowMs: Long,
        staleAfterMs: Long,
    ): FleetStatus {
        val id = obs.version.identity
        return when {
            !obs.isSelf && nowMs - obs.lastHeardAtMs > staleAfterMs -> FleetStatus.OFFLINE
            !id.isTrustworthyReference -> FleetStatus.UNKNOWN
            latest != null && id.commitEpochSeconds >= latest -> FleetStatus.CURRENT
            else -> FleetStatus.BEHIND
        }
    }

    /** A build usable as the "latest" yardstick: it names a real commit, was built clean, and dates itself. */
    private val BuildIdentity.isTrustworthyReference: Boolean
        get() = known && !dirty && commitEpochSeconds > 0

    /** Sort key: worst first, so a problem is at the top of the roster. */
    private val FleetStatus.severity: Int
        get() =
            when (this) {
                FleetStatus.OFFLINE -> 0
                FleetStatus.UNKNOWN -> 1
                FleetStatus.BEHIND -> 2
                FleetStatus.CURRENT -> 3
            }
}
