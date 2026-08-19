"""The ZCOVER camera-coverage wire format (RES-P2-1): format/parse round-trips,
malformed-frame handling, the send scheduler, and the R2 timeliness bound.

A blind arc must reach the tablets as *less coverage*, never more — so every
malformed or out-of-range arc here is asserted to degrade toward blind, and the
channel is proven isolated from ZTHREAT in both directions."""

import unittest

from zvision.coverage_protocol import (
    HEADER,
    MAX_ARCS,
    CoverageScheduler,
    format_coverage,
    parse_coverage,
)
from zvision.normalize import LIVE_WINDOW_SECS, CameraStallGuard
from zvision.threat_protocol import parse_frame


class FormatParseTest(unittest.TestCase):
    def test_round_trip_of_a_forward_arc(self):
        self.assertEqual("ZCOVER;-64.0:64.0", format_coverage([(-64.0, 64.0)]))
        self.assertEqual([(-64.0, 64.0)], parse_coverage("ZCOVER;-64.0:64.0"))

    def test_empty_is_the_bare_header_meaning_all_blind(self):
        # The degenerate message must be "nothing covered", so a lost or empty
        # payload fails BLIND rather than clear.
        self.assertEqual("ZCOVER", format_coverage([]))
        self.assertEqual([], parse_coverage("ZCOVER"))

    def test_a_seam_crossing_arc_keeps_its_end_past_180(self):
        self.assertEqual("ZCOVER;120.0:200.0", format_coverage([(120.0, 200.0)]))
        self.assertEqual([(120.0, 200.0)], parse_coverage("ZCOVER;120.0:200.0"))

    def test_multiple_arcs_round_trip_in_order(self):
        arcs = [(-30.0, 30.0), (100.0, 170.0)]
        self.assertEqual([(-30.0, 30.0), (100.0, 170.0)], parse_coverage(format_coverage(arcs)))

    def test_trailing_framing_whitespace_is_ignored(self):
        self.assertEqual([(-64.0, 64.0)], parse_coverage("ZCOVER;-64.0:64.0\r\n"))

    def test_non_finite_arcs_are_dropped_at_the_source(self):
        self.assertEqual("ZCOVER", format_coverage([(float("nan"), 10.0)]))


class MalformedFrameTest(unittest.TestCase):
    def test_a_malformed_arc_is_skipped_while_a_wellformed_sibling_is_kept(self):
        # Negative + positive control in one frame: garbage must not poison the
        # good arc, and the good arc must survive.
        self.assertEqual([(10.0, 50.0)], parse_coverage("ZCOVER;xx:yy;10.0:50.0"))

    def test_hex_float_is_rejected_like_zthreat(self):
        # The grammar is shared with ZTHREAT precisely so 0x1p3 can never become
        # a live bearing; here it just drops the arc -> more blind, the safe way.
        self.assertEqual([], parse_coverage("ZCOVER;0x1p3:64.0"))

    def test_a_start_off_the_circle_is_rejected(self):
        self.assertEqual([], parse_coverage("ZCOVER;200.0:250.0"))

    def test_a_reversed_arc_is_rejected(self):
        self.assertEqual([], parse_coverage("ZCOVER;50.0:10.0"))

    def test_an_over_wide_arc_is_rejected(self):
        self.assertEqual([], parse_coverage("ZCOVER;-180.0:200.0"))

    def test_a_full_ring_arc_is_allowed(self):
        # Exactly one turn is the whole ring covered — the boundary must pass.
        self.assertEqual([(-180.0, 180.0)], parse_coverage("ZCOVER;-180.0:180.0"))

    def test_the_arc_count_is_capped(self):
        many = "ZCOVER;" + ";".join(f"{i - 100}.0:{i - 99}.0" for i in range(MAX_ARCS + 5))
        self.assertEqual(MAX_ARCS, len(parse_coverage(many)))


class ChannelIsolationTest(unittest.TestCase):
    """ZCOVER and ZTHREAT ride the same bus; each parser must ignore the other's
    frames cleanly, or a coverage datagram would be read as a threat frame (or
    vice-versa) and corrupt the HUD."""

    def test_a_threat_frame_is_not_a_coverage_frame(self):
        self.assertIsNone(parse_coverage("ZTHREAT;1:-12.0:0.300:0"))

    def test_a_coverage_frame_is_not_a_threat_frame(self):
        self.assertIsNone(parse_frame("ZCOVER;-64.0:64.0"))

    def test_a_bare_coverage_header_is_not_a_threat_frame(self):
        # Positive control that a real ZTHREAT parses, so the None above is the
        # isolation and not a broken parser.
        self.assertIsNone(parse_frame("ZCOVER"))
        self.assertEqual([], parse_frame("ZTHREAT"))

    def test_garbage_is_a_coverage_frame_for_neither(self):
        self.assertIsNone(parse_coverage("HELLO"))


class CoverageSchedulerTest(unittest.TestCase):
    """Pure send-decision: 1 Hz heartbeat plus an immediate send on any change,
    on an injectable clock."""

    @staticmethod
    def _sched(times, period=1.0):
        it = iter(times)
        return CoverageScheduler(period=period, clock=lambda: next(it))

    def test_first_call_always_sends(self):
        s = self._sched([0.0])
        self.assertTrue(s.due([(-64.0, 64.0)]))

    def test_no_change_within_the_period_does_not_send(self):
        s = self._sched([0.0, 0.5], period=1.0)
        self.assertTrue(s.due([(-64.0, 64.0)]))     # first send
        self.assertFalse(s.due([(-64.0, 64.0)]))    # 0.5 s later, unchanged

    def test_the_heartbeat_fires_at_the_period(self):
        s = self._sched([0.0, 1.0], period=1.0)
        self.assertTrue(s.due([(-64.0, 64.0)]))     # first send
        self.assertTrue(s.due([(-64.0, 64.0)]))     # 1.0 s later, unchanged -> heartbeat

    def test_a_covered_set_change_sends_immediately(self):
        # A camera dies mid-period: the driver must not wait for the heartbeat.
        s = self._sched([0.0, 0.1, 0.2], period=1.0)
        self.assertTrue(s.due([(-64.0, 64.0)]))             # first send
        self.assertTrue(s.due([]))                          # 0.1 s later, now blind
        self.assertFalse(s.due([]))                         # 0.2 s later, unchanged

    def test_recovery_also_sends_immediately(self):
        s = self._sched([0.0, 0.2, 0.3], period=5.0)
        self.assertTrue(s.due([]))                          # blind, first send
        self.assertTrue(s.due([(-64.0, 64.0)]))             # camera recovered
        self.assertFalse(s.due([(-64.0, 64.0)]))            # unchanged, before heartbeat


class TimelinessBoundTest(unittest.TestCase):
    """R2: a camera that stops delivering makes its arc BLIND within a bounded,
    tested time. The pinned number is LIVE_WINDOW_SECS = 3.0 s (plus one loop
    tick), and it is mutation-proof: 2.9 s still covers, 3.1 s is blind."""

    def test_the_pinned_bound_is_three_seconds(self):
        self.assertEqual(3.0, LIVE_WINDOW_SECS)

    @staticmethod
    def _guard(times):
        it = iter(times)
        return CameraStallGuard(clock=lambda: next(it))

    def test_just_under_the_window_still_covers(self):
        g = self._guard([0.0, 2.9])
        g.note(True)
        self.assertTrue(g.delivering(LIVE_WINDOW_SECS))

    def test_just_over_the_window_is_blind(self):
        g = self._guard([0.0, 3.1])
        g.note(True)
        self.assertFalse(g.delivering(LIVE_WINDOW_SECS))

    def test_one_delivered_frame_flips_it_back_immediately(self):
        g = self._guard([0.0, 3.1, 3.1, 3.2])
        g.note(True)                                   # t=0 frame
        self.assertFalse(g.delivering(LIVE_WINDOW_SECS))  # t=3.1 blind
        g.note(True)                                   # t=3.1 a frame arrives
        self.assertTrue(g.delivering(LIVE_WINDOW_SECS))   # t=3.2 covering again


if __name__ == "__main__":
    unittest.main()
