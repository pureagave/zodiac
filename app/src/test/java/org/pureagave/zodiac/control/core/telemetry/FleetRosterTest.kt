package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Roster aggregation is pure, so every rule is pinned against fixed inputs with
 * an injected clock. The load-bearing cases are the "one rule" ones: a dirty or
 * unknown build must never read CURRENT or set the bar, and a peer gone quiet
 * must read OFFLINE — each paired against a genuinely-current control so the
 * assertion can fail.
 */
class FleetRosterTest {
    private val stale = 35_000L
    private val now = 1_000_000L

    private fun obs(
        node: String,
        epoch: Long,
        ageMs: Long = 0L,
        self: Boolean = false,
        sha: String = "abc1234de",
        dirty: Boolean = false,
        name: String = node,
    ) = FleetObservation(
        FleetVersion(node, name, BuildIdentity(base = "0.1.0", sha = sha, dirty = dirty, commitEpochSeconds = epoch)),
        lastHeardAtMs = now - ageMs,
        isSelf = self,
    )

    private fun byNode(entries: List<FleetRosterEntry>) = entries.associate { it.version.node to it.status }

    @Test
    fun newest_epoch_is_current_older_is_behind() {
        val s = byNode(FleetRoster.compute(listOf(obs("A", 200), obs("B", 100)), now, stale))
        assertEquals(FleetStatus.CURRENT, s["A"])
        assertEquals(FleetStatus.BEHIND, s["B"])
    }

    @Test
    fun equal_newest_epochs_are_both_current() {
        val s = byNode(FleetRoster.compute(listOf(obs("A", 200), obs("B", 200)), now, stale))
        assertEquals(FleetStatus.CURRENT, s["A"])
        assertEquals(FleetStatus.CURRENT, s["B"])
    }

    @Test
    fun a_dirty_build_is_unknown_and_does_not_set_the_bar() {
        // B is dirty with a HIGHER epoch; it must not make the clean A look behind.
        val s = byNode(FleetRoster.compute(listOf(obs("A", 100), obs("B", 200, dirty = true)), now, stale))
        assertEquals(FleetStatus.CURRENT, s["A"])
        assertEquals(FleetStatus.UNKNOWN, s["B"])
    }

    @Test
    fun unknown_sha_is_unknown() {
        val s = byNode(FleetRoster.compute(listOf(obs("A", 200), obs("B", 100, sha = BuildIdentity.UNKNOWN_SHA)), now, stale))
        assertEquals(FleetStatus.UNKNOWN, s["B"])
    }

    @Test
    fun epoch_zero_is_unknown() {
        val s = byNode(FleetRoster.compute(listOf(obs("A", 200), obs("B", 0)), now, stale))
        assertEquals(FleetStatus.UNKNOWN, s["B"])
    }

    @Test
    fun a_peer_not_heard_from_within_the_window_is_offline() {
        val s = byNode(FleetRoster.compute(listOf(obs("A", 200), obs("B", 200, ageMs = stale + 1)), now, stale))
        assertEquals(FleetStatus.CURRENT, s["A"])
        assertEquals("silence must read offline, not current", FleetStatus.OFFLINE, s["B"])
    }

    @Test
    fun self_is_never_offline_even_when_stale() {
        val s = byNode(FleetRoster.compute(listOf(obs("A", 200, ageMs = stale + 10_000, self = true)), now, stale))
        assertEquals(FleetStatus.CURRENT, s["A"])
    }

    @Test
    fun rows_are_sorted_worst_status_first() {
        val entries =
            FleetRoster.compute(
                listOf(
                    obs("CUR", 200),
                    obs("OFF", 200, ageMs = stale + 1),
                    obs("UNK", 200, dirty = true),
                    obs("BEH", 100),
                ),
                now,
                stale,
            )
        assertEquals(
            listOf(FleetStatus.OFFLINE, FleetStatus.UNKNOWN, FleetStatus.BEHIND, FleetStatus.CURRENT),
            entries.map { it.status },
        )
    }

    @Test
    fun a_duplicate_node_keeps_the_freshest_observation() {
        // A stale old sighting and a fresh new one for one node: the fresh one
        // wins, so the node is not wrongly OFFLINE and appears once.
        val entries = FleetRoster.compute(listOf(obs("A", 200, ageMs = stale + 100), obs("A", 200)), now, stale)
        assertEquals(1, entries.size)
        assertEquals(FleetStatus.CURRENT, entries.single().status)
    }

    @Test
    fun everything_is_unknown_when_no_clean_reference_exists() {
        val s =
            byNode(
                FleetRoster.compute(
                    listOf(obs("A", 200, dirty = true), obs("B", 100, sha = BuildIdentity.UNKNOWN_SHA)),
                    now,
                    stale,
                ),
            )
        assertEquals(FleetStatus.UNKNOWN, s["A"])
        assertEquals(FleetStatus.UNKNOWN, s["B"])
    }

    @Test
    fun empty_input_is_empty() {
        assertEquals(emptyList<FleetRosterEntry>(), FleetRoster.compute(emptyList(), now, stale))
    }
}
