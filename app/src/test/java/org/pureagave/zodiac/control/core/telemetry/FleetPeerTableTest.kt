package org.pureagave.zodiac.control.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetPeerTableTest {
    private fun wire(
        node: String,
        sha: String,
        epoch: Long,
        name: String = "SM-X810",
    ) = FleetVersionProtocol.build(
        FleetVersion(node, name, BuildIdentity(base = "0.1.0", sha = sha, dirty = false, commitEpochSeconds = epoch)),
    )

    @Test
    fun a_valid_frame_adds_a_peer_stamped_now() {
        val out = FleetPeerTable.ingest(emptyMap(), wire("9C1977", "8f531e18a", 1_691_900_000L), nowMs = 500L)
        val peer = out["9C1977"]
        assertEquals(1, out.size)
        assertEquals(500L, peer?.lastHeardAtMs)
        assertEquals("8f531e18a", peer?.version?.identity?.sha)
    }

    @Test
    fun a_malformed_frame_leaves_the_table_untouched() {
        val start = FleetPeerTable.ingest(emptyMap(), wire("9C1977", "8f531e18a", 100L), nowMs = 1L)
        val after = FleetPeerTable.ingest(start, "\$ZVER,9C1977,SM-X810,0.1.0,8f531e18a,0,100*00\r\n", nowMs = 2L)
        assertSame("a bad checksum must not create or mutate a row", start, after)
    }

    @Test
    fun re_hearing_a_node_upserts_the_build_and_timestamp() {
        val v1 = FleetPeerTable.ingest(emptyMap(), wire("9C1977", "aaaaaaaaa", 100L), nowMs = 10L)
        val v2 = FleetPeerTable.ingest(v1, wire("9C1977", "bbbbbbbbb", 200L), nowMs = 50L)
        assertEquals("still one row for the node", 1, v2.size)
        assertEquals("bbbbbbbbb", v2["9C1977"]?.version?.identity?.sha)
        assertEquals(50L, v2["9C1977"]?.lastHeardAtMs)
    }

    @Test
    fun distinct_nodes_get_distinct_rows() {
        val a = FleetPeerTable.ingest(emptyMap(), wire("9C1977", "aaaaaaaaa", 100L), nowMs = 1L)
        val both = FleetPeerTable.ingest(a, wire("4A0C11", "bbbbbbbbb", 100L, name = "KFTUWI"), nowMs = 1L)
        assertEquals(2, both.size)
        assertTrue(both.keys.containsAll(listOf("9C1977", "4A0C11")))
    }

    @Test
    fun upserting_one_node_leaves_the_other_rows_untouched() {
        val a = FleetPeerTable.ingest(emptyMap(), wire("9C1977", "aaaaaaaaa", 100L), nowMs = 1L)
        val ab = FleetPeerTable.ingest(a, wire("4A0C11", "bbbbbbbbb", 100L, name = "KFTUWI"), nowMs = 2L)
        val reheard = FleetPeerTable.ingest(ab, wire("9C1977", "ccccccccc", 300L), nowMs = 9L)
        assertEquals("still two rows", 2, reheard.size)
        assertSame("the other peer's row must carry over unchanged", ab["4A0C11"], reheard["4A0C11"])
        assertEquals("ccccccccc", reheard["9C1977"]?.version?.identity?.sha)
    }

    @Test
    fun a_wrong_type_sentence_with_the_right_shape_and_checksum_is_ignored() {
        // A frame with the exact $ZVER field count and a VALID checksum but a
        // different sentence type ($XVER) must be rejected on the type gate — not
        // the checksum, not the shape. Leaves the table by-reference identical.
        val start = FleetPeerTable.ingest(emptyMap(), wire("9C1977", "8f531e18a", 100L), nowMs = 1L)
        val after =
            FleetPeerTable.ingest(start, "\$XVER,9C1977,SM-X810,0.1.0,8f531e18a,0,1700000000*5A\r\n", nowMs = 2L)
        assertSame("a wrong-type but otherwise valid sentence must not touch the table", start, after)
    }
}
