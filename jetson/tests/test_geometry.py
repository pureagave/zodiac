import unittest

from zvision.geometry import CollisionEstimator, bbox_height_to_size, bbox_to_rel_az


class RelAzTest(unittest.TestCase):
    def test_centre_is_dead_ahead(self):
        self.assertAlmostEqual(0.0, bbox_to_rel_az(0.5, 57.0), places=6)

    def test_left_edge_is_negative_half_fov(self):
        self.assertAlmostEqual(-28.5, bbox_to_rel_az(0.0, 57.0), places=6)

    def test_right_edge_is_positive_half_fov(self):
        self.assertAlmostEqual(28.5, bbox_to_rel_az(1.0, 57.0), places=6)


class SizeTest(unittest.TestCase):
    def test_tall_box_is_near(self):
        self.assertGreater(bbox_height_to_size(0.9), 0.95)

    def test_tiny_box_is_far(self):
        self.assertLess(bbox_height_to_size(0.05), 0.05)

    def test_clamped_to_unit_range(self):
        self.assertEqual(1.0, bbox_height_to_size(2.0))
        self.assertEqual(0.0, bbox_height_to_size(-1.0))


class CollisionTest(unittest.TestCase):
    def test_first_sighting_never_flags(self):
        est = CollisionEstimator()
        self.assertFalse(est.update(1, az=0.0, size=0.5, t=0.0))

    def test_constant_bearing_closing_contact_flags(self):
        est = CollisionEstimator()
        est.update(1, az=2.0, size=0.40, t=0.0)
        # Same bearing, growing (closing) -> intercept.
        self.assertTrue(est.update(1, az=2.1, size=0.55, t=1.0))

    def test_crossing_contact_does_not_flag(self):
        est = CollisionEstimator()
        est.update(1, az=0.0, size=0.40, t=0.0)
        # Bearing swinging fast (crossing in front) -> not a collision course.
        self.assertFalse(est.update(1, az=20.0, size=0.55, t=1.0))

    def test_receding_contact_does_not_flag(self):
        est = CollisionEstimator()
        est.update(1, az=2.0, size=0.60, t=0.0)
        # Shrinking (opening range) -> no threat even on constant bearing.
        self.assertFalse(est.update(1, az=2.0, size=0.50, t=1.0))

    def test_distant_contact_below_min_size_ignored(self):
        est = CollisionEstimator()
        est.update(1, az=2.0, size=0.10, t=0.0)
        self.assertFalse(est.update(1, az=2.0, size=0.20, t=1.0))

    def test_out_of_order_frame_does_not_poison_the_baseline(self):
        est = CollisionEstimator()
        est.update(1, az=2.0, size=0.40, t=1.0)
        est.update(1, az=2.0, size=0.30, t=0.5)  # replayed/out-of-order: ignored
        # The next legitimate delta must be measured from the t=1.0 sample, so a
        # constant-bearing close from 0.40→0.55 still flags.
        self.assertTrue(est.update(1, az=2.1, size=0.55, t=2.0))

    def test_az_delta_wraps_across_the_antimeridian(self):
        est = CollisionEstimator()
        est.update(1, az=179.0, size=0.40, t=0.0)
        # 179 → -179 is a 2°/s crossing, not 358°/s — so it stays a valid
        # constant-bearing close, not rejected as fast crossing.
        self.assertTrue(est.update(1, az=-179.0, size=0.55, t=1.0))

    def test_forget_resets_track_history(self):
        est = CollisionEstimator()
        est.update(1, az=2.0, size=0.40, t=0.0)
        est.forget(1)
        # After forget, the next update is a first-sighting again → never flags.
        self.assertFalse(est.update(1, az=2.0, size=0.55, t=1.0))


if __name__ == "__main__":
    unittest.main()
