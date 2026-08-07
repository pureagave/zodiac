"""The scalar decisions behind thermal normalisation and track association.

Both real failures of 2026-08-07 were in this arithmetic, and neither was
catchable without hardware because neither had a test. The first two classes
here are written so that each of those bugs fails loudly if reintroduced.
"""

import unittest

from zvision.normalize import (
    DEFAULT_MIN_SPREAD,
    assign_track_id,
    stretch_window,
)


class StretchCentreTest(unittest.TestCase):
    """Bug 1: smoothing the *centre* left the mapping trailing the data and
    made background subtraction useless (background movement went from 37.8 to
    128.7 of 255 levels). The centre must track the frame exactly."""

    def test_centre_is_used_unsmoothed(self):
        # Same steady spread, but the scene's DC level jumps — as it does on a
        # flat-field correction. The window must follow it immediately.
        lo1, scale1, ema = stretch_window(1000.0, 400.0, 400.0)
        lo2, scale2, _ = stretch_window(5000.0, 400.0, ema)
        self.assertAlmostEqual(4000.0, lo2 - lo1, places=6)
        self.assertAlmostEqual(scale1, scale2, places=6)

    def test_a_drifting_centre_never_lags(self):
        ema = None
        for centre in (1000.0, 1200.0, 1500.0, 900.0):
            lo, scale, ema = stretch_window(centre, 500.0, ema)
            # The window is always exactly centred on this frame.
            self.assertAlmostEqual(centre, lo + scale, places=6)


class StretchScaleTest(unittest.TestCase):
    """The scale, unlike the centre, *is* smoothed — that's what stops a hot
    object entering the frame from inflating the range and darkening
    everything else."""

    def test_first_frame_adopts_the_spread(self):
        _, _, ema = stretch_window(0.0, 640.0, None)
        self.assertAlmostEqual(640.0, ema, places=6)

    def test_a_sudden_hot_object_barely_moves_the_scale(self):
        # Settled on a quiet scene, then someone walks in and the frame's
        # contrast triples. One frame must stay far nearer the settled value
        # than the new one — that gap is what keeps the hot object an outlier
        # instead of something that rescales the whole image.
        settled, intruded = 600.0, 1800.0
        _, _, ema = stretch_window(0.0, intruded, settled, alpha=0.05)
        moved = (ema - settled) / (intruded - settled)
        self.assertLess(moved, 0.15, "one frame should not chase a transient")
        self.assertGreater(ema, settled, "but it should still move toward it")

    def test_the_scale_still_follows_a_sustained_change(self):
        ema = 600.0
        for _ in range(200):
            _, _, ema = stretch_window(0.0, 1800.0, ema, alpha=0.05)
        self.assertAlmostEqual(1800.0, ema, delta=5.0)


class GainFloorTest(unittest.TestCase):
    """Bug 2: with no floor, a thermally flat scene was amplified until sensor
    noise became contacts — 77 spurious detections in three minutes."""

    def test_a_flat_scene_does_not_get_amplified(self):
        # Measured reality: a still room gave a spread of ~51 counts against
        # ~37 counts of noise. Without a floor that is enormous gain.
        _, scale, _ = stretch_window(30000.0, 51.0, 51.0)
        self.assertEqual(DEFAULT_MIN_SPREAD, scale)

    def test_real_contrast_is_left_alone(self):
        # A person produces ~600 counts; the floor must not touch that.
        _, scale, _ = stretch_window(30000.0, 600.0, 600.0)
        self.assertAlmostEqual(600.0, scale, places=6)

    def test_the_floor_bounds_the_gain_not_the_ema(self):
        # The smoothed value keeps tracking the real (tiny) spread — only the
        # applied scale is clamped — so recovery is immediate when contrast
        # returns rather than having to climb back from the floor.
        _, scale, ema = stretch_window(0.0, 10.0, 10.0)
        self.assertEqual(DEFAULT_MIN_SPREAD, scale)
        self.assertAlmostEqual(10.0, ema, places=6)

    def test_floor_is_configurable(self):
        _, scale, _ = stretch_window(0.0, 5.0, 5.0, min_spread=1000.0)
        self.assertEqual(1000.0, scale)


class StretchRobustnessTest(unittest.TestCase):
    def test_a_perfectly_flat_frame_cannot_divide_by_zero(self):
        # Mid-FFC the shutter is closed and every pixel reads the same.
        lo, scale, ema = stretch_window(100.0, 0.0, None)
        self.assertGreater(scale, 0.0)
        self.assertGreaterEqual(ema, 1.0)

    def test_non_finite_inputs_do_not_poison_the_window(self):
        lo, scale, ema = stretch_window(float("nan"), float("inf"), None)
        self.assertEqual(lo, lo)          # not NaN
        self.assertGreater(scale, 0.0)

    def test_a_corrupt_ema_recovers(self):
        _, scale, ema = stretch_window(0.0, 500.0, float("nan"))
        self.assertAlmostEqual(500.0, ema, places=6)


class TrackAssignmentTest(unittest.TestCase):
    """Stable ids are load-bearing: the collision estimator measures bearing
    *rate* per id, and the tracker light latches onto one. Churn makes a
    standing person look like a stream of new contacts."""

    def test_a_lone_blob_gets_a_fresh_id(self):
        tid, nxt = assign_track_id(0.5, 0.5, {}, {}, 0.15, 7)
        self.assertEqual(7, tid)
        self.assertEqual(8, nxt)

    def test_a_small_movement_keeps_the_same_id(self):
        tracks = {3: (0.50, 0.50)}
        tid, nxt = assign_track_id(0.52, 0.51, tracks, {}, 0.15, 9)
        self.assertEqual(3, tid)
        self.assertEqual(9, nxt)  # no id consumed

    def test_a_jump_beyond_the_window_is_a_new_contact(self):
        tracks = {3: (0.10, 0.10)}
        tid, nxt = assign_track_id(0.90, 0.90, tracks, {}, 0.15, 9)
        self.assertEqual(9, tid)
        self.assertEqual(10, nxt)

    def test_the_nearest_track_wins(self):
        tracks = {1: (0.40, 0.50), 2: (0.55, 0.50)}
        tid, _ = assign_track_id(0.54, 0.50, tracks, {}, 0.3, 9)
        self.assertEqual(2, tid)

    def test_a_track_already_claimed_this_frame_cannot_be_reused(self):
        # Two blobs must not collapse onto one id — that would make a single
        # contact appear to teleport between two people.
        tracks = {1: (0.50, 0.50)}
        seen = {1: (0.50, 0.50)}
        tid, nxt = assign_track_id(0.51, 0.50, tracks, seen, 0.15, 9)
        self.assertEqual(9, tid)
        self.assertEqual(10, nxt)

    def test_two_blobs_near_one_track_get_distinct_ids(self):
        tracks = {1: (0.50, 0.50)}
        seen = {}
        first, nxt = assign_track_id(0.50, 0.50, tracks, seen, 0.15, 9)
        seen[first] = (0.50, 0.50)
        second, nxt2 = assign_track_id(0.53, 0.50, tracks, seen, 0.15, nxt)
        self.assertNotEqual(first, second)
        self.assertEqual(1, first)     # the closer one inherits the track
        self.assertEqual(9, second)    # the other is genuinely new

    def test_match_distance_boundary_is_exclusive(self):
        tracks = {1: (0.0, 0.0)}
        # exactly at the threshold does not match (strict <)
        tid, _ = assign_track_id(0.15, 0.0, tracks, {}, 0.15, 9)
        self.assertEqual(9, tid)
        tid, _ = assign_track_id(0.149, 0.0, tracks, {}, 0.15, 9)
        self.assertEqual(1, tid)

    def test_matching_is_euclidean_not_per_axis(self):
        # 0.11 in each axis is 0.156 away — outside a 0.15 window, even though
        # neither axis alone exceeds it.
        tracks = {1: (0.0, 0.0)}
        tid, _ = assign_track_id(0.11, 0.11, tracks, {}, 0.15, 9)
        self.assertEqual(9, tid)

    def test_ids_keep_increasing_across_many_new_contacts(self):
        nxt = 1
        ids = []
        for i in range(5):
            tid, nxt = assign_track_id(float(i), 0.0, {}, {}, 0.15, nxt)
            ids.append(tid)
        self.assertEqual([1, 2, 3, 4, 5], ids)


if __name__ == "__main__":
    unittest.main()
