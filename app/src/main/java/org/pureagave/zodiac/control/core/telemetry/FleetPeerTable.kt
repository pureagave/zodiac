package org.pureagave.zodiac.control.core.telemetry

/**
 * The receive-side decision for FLEET-1, kept pure and off the socket loop (the
 * project's "decisions live in core with tests" rule): fold a received line into
 * the peer table.
 *
 * A valid `$ZVER` upserts its node's observation stamped at [nowMs]; anything
 * else — a malformed sentence, a foreign datagram — leaves the table untouched,
 * so garbage on the wire can never evict or corrupt a peer. Keyed by node, so
 * one device is always exactly one row and a fresh sighting overwrites the older
 * one *including its build*, so a device that has just been reflashed is
 * reflected the instant its next announcement arrives.
 *
 * Staleness is deliberately **not** applied here — this only records what was
 * heard and when. [FleetRoster] decides OFFLINE from `now`, so a peer is never
 * forgotten by the table (its last-known build stays for the roster to mark
 * offline), only ever superseded by a newer sighting of the same node.
 */
object FleetPeerTable {
    fun ingest(
        peers: Map<String, FleetObservation>,
        line: String,
        nowMs: Long,
    ): Map<String, FleetObservation> {
        val version = FleetVersionProtocol.parse(line) ?: return peers
        return peers + (version.node to FleetObservation(version, lastHeardAtMs = nowMs))
    }
}
