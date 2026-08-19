package org.pureagave.zodiac.control.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tablet's half of the ZCOVER camera-coverage format (RES-P2-1): parse,
 * mirror-format, malformed handling, and channel isolation from ZTHREAT. A blind
 * arc must reach the HUD as *less coverage*, never more — so every malformed or
 * out-of-range arc here degrades toward blind, paired with a positive control.
 */
class CoverageProtocolTest {
    @Test
    fun round_trip_of_a_forward_arc() {
        assertEquals("ZCOVER;-64.0:64.0", CoverageProtocol.format(listOf(-64f..64f)))
        assertEquals(listOf(-64f..64f), CoverageProtocol.parse("ZCOVER;-64.0:64.0"))
    }

    @Test
    fun a_bare_header_is_all_blind_not_all_clear() {
        // The degenerate message must be "nothing covered": a lost/empty payload
        // fails BLIND, never clear.
        assertEquals("ZCOVER", CoverageProtocol.format(emptyList()))
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), CoverageProtocol.parse("ZCOVER"))
    }

    @Test
    fun a_seam_crossing_arc_keeps_its_end_past_180() {
        assertEquals("ZCOVER;120.0:200.0", CoverageProtocol.format(listOf(120f..200f)))
        assertEquals(listOf(120f..200f), CoverageProtocol.parse("ZCOVER;120.0:200.0"))
    }

    @Test
    fun multiple_arcs_round_trip_in_order() {
        val arcs = listOf(-30f..30f, 100f..170f)
        assertEquals(arcs, CoverageProtocol.parse(CoverageProtocol.format(arcs)))
    }

    @Test
    fun trailing_framing_whitespace_is_ignored() {
        assertEquals(listOf(-64f..64f), CoverageProtocol.parse("ZCOVER;-64.0:64.0\r\n"))
    }

    @Test
    fun a_malformed_arc_is_skipped_while_a_wellformed_sibling_is_kept() {
        // Negative + positive control in one frame.
        assertEquals(listOf(10f..50f), CoverageProtocol.parse("ZCOVER;xx:yy;10.0:50.0"))
    }

    @Test
    fun a_hex_float_is_rejected_like_zthreat() {
        // The grammar is shared with ZTHREAT so 0x1p3 can never become a bearing;
        // here it just drops the arc -> more blind, the safe direction.
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), CoverageProtocol.parse("ZCOVER;0x1p3:64.0"))
    }

    @Test
    fun a_start_off_the_circle_is_rejected() {
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), CoverageProtocol.parse("ZCOVER;200.0:250.0"))
    }

    @Test
    fun a_reversed_arc_is_rejected() {
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), CoverageProtocol.parse("ZCOVER;50.0:10.0"))
    }

    @Test
    fun an_over_wide_arc_is_rejected_but_a_full_ring_is_allowed() {
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), CoverageProtocol.parse("ZCOVER;-180.0:200.0"))
        // Positive control: exactly one turn (whole ring covered) is the boundary and must pass.
        assertEquals(listOf(-180f..180f), CoverageProtocol.parse("ZCOVER;-180.0:180.0"))
    }

    @Test
    fun a_threat_frame_is_not_a_coverage_frame() {
        assertNull(CoverageProtocol.parse("ZTHREAT;1:-12.0:0.300:0"))
    }

    @Test
    fun a_coverage_frame_is_not_a_threat_frame() {
        assertNull(ThreatProtocol.parse("ZCOVER;-64.0:64.0"))
        // Positive control: a real ZTHREAT still parses, so the nulls above are
        // isolation, not a broken parser.
        assertEquals(emptyList<DriverThreat>(), ThreatProtocol.parse("ZTHREAT"))
    }

    @Test
    fun garbage_is_a_coverage_frame_for_neither() {
        assertNull(CoverageProtocol.parse("HELLO WORLD"))
    }

    @Test
    fun the_arc_count_is_capped() {
        val many = "ZCOVER;" + (0 until 21).joinToString(";") { "${it - 100}.0:${it - 99}.0" }
        assertEquals(16, CoverageProtocol.parse(many)!!.size)
    }
}
