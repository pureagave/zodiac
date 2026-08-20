package org.pureagave.zodiac.control.data.fleet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.telemetry.BuildIdentity
import org.pureagave.zodiac.control.core.telemetry.FleetObservation
import org.pureagave.zodiac.control.core.telemetry.FleetRosterEntry
import org.pureagave.zodiac.control.core.telemetry.FleetStatus
import org.pureagave.zodiac.control.core.telemetry.FleetVersion

@OptIn(ExperimentalCoroutinesApi::class)
class FleetVersionMonitorTest {
    @Test
    fun self_is_always_present_even_with_no_peers() =
        runTest {
            val peers = MutableStateFlow<Map<String, FleetObservation>>(emptyMap())
            val monitor = FleetVersionMonitor(peers, self = selfVersion(), scope = backgroundScope, now = { NOW })

            val roster = monitor.roster.value
            assertEquals("self is the only row", 1, roster.size)
            assertEquals(SELF_NODE, roster.single().version.node)
            assertTrue("self must be flagged isSelf", roster.single().isSelf)
            // Self is the only trustworthy build, so it sets the bar and reads CURRENT.
            assertEquals(FleetStatus.CURRENT, roster.single().status)
        }

    @Test
    fun a_received_peer_appears_alongside_self() =
        runTest {
            val peers =
                MutableStateFlow(
                    mapOf("PEER01" to FleetObservation(peerVersion("PEER01", epoch = 500L), lastHeardAtMs = NOW)),
                )
            val monitor = FleetVersionMonitor(peers, self = selfVersion(), scope = backgroundScope, now = { NOW })

            assertEquals(setOf(SELF_NODE, "PEER01"), monitor.roster.value.map { it.version.node }.toSet())
        }

    @Test
    fun the_devices_own_echo_is_not_duplicated() =
        runTest {
            // A device is joined to its own multicast group, so it hears its own
            // $ZVER back — the peer table can hold a row keyed by OUR node. That
            // echo must not become a second self row, nor age to OFFLINE.
            val echo =
                FleetObservation(
                    FleetVersion(SELF_NODE, "SM-X810", BuildIdentity("0.1.0", "aaaaaaaaa", dirty = false, 1_000L)),
                    lastHeardAtMs = NOW,
                    isSelf = false,
                )
            val peers = MutableStateFlow(mapOf(SELF_NODE to echo))
            val monitor = FleetVersionMonitor(peers, self = selfVersion(), scope = backgroundScope, now = { NOW })

            val selfRows = monitor.roster.value.filter { it.version.node == SELF_NODE }
            assertEquals("exactly one row for our own node", 1, selfRows.size)
            assertTrue("and it must be the authoritative self row", selfRows.single().isSelf)
        }

    @Test
    fun a_silent_peer_ages_to_offline_on_a_tick() =
        runTest {
            var clock = 10_000L
            val peers =
                MutableStateFlow(
                    mapOf("PEER01" to FleetObservation(peerVersion("PEER01", epoch = 500L), lastHeardAtMs = 10_000L)),
                )
            val monitor =
                FleetVersionMonitor(
                    peers = peers,
                    self = selfVersion(),
                    scope = backgroundScope,
                    staleAfterMs = 5_000L,
                    recomputeMs = 1_000L,
                    now = { clock },
                )
            runCurrent()
            assertNotEquals("fresh peer is not offline", FleetStatus.OFFLINE, statusOf(monitor.roster.value, "PEER01"))

            // No new datagram arrives; only the clock advances past staleAfter.
            clock = 10_000L + 5_001L
            advanceTimeBy(1_001L)
            runCurrent()
            assertEquals(
                "a silent peer must flip to OFFLINE once a recompute tick sees it stale",
                FleetStatus.OFFLINE,
                statusOf(monitor.roster.value, "PEER01"),
            )
        }

    private fun statusOf(
        roster: List<FleetRosterEntry>,
        node: String,
    ): FleetStatus = roster.first { it.version.node == node }.status

    private fun selfVersion(): FleetVersion = FleetVersion(SELF_NODE, "SM-X810", BuildIdentity("0.1.0", "aaaaaaaaa", dirty = false, 1_000L))

    private fun peerVersion(
        node: String,
        epoch: Long,
    ): FleetVersion = FleetVersion(node, "KFTUWI", BuildIdentity("0.1.0", "bbbbbbbbb", dirty = false, epoch))

    private companion object {
        const val SELF_NODE = "SELF01"
        const val NOW = 10_000L
    }
}
