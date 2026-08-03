import math
import unittest

from zvision.geometry import (
    FOV_DIAGONAL,
    LENS_EQUIDISTANT,
    LENS_EQUISOLID,
    LENS_LINEAR,
    LENS_RECTILINEAR,
    CollisionEstimator,
    bbox_height_to_size,
    bbox_to_rel_az,
    pixel_to_bearing,
    wrap180,
)


class RelAzTest(unittest.TestCase):
    def test_centre_is_dead_ahead(self):
        self.assertAlmostEqual(0.0, bbox_to_rel_az(0.5, 57.0), places=6)

    def test_left_edge_is_negative_half_fov(self):
        self.assertAlmostEqual(-28.5, bbox_to_rel_az(0.0, 57.0), places=6)

    def test_right_edge_is_positive_half_fov(self):
        self.assertAlmostEqual(28.5, bbox_to_rel_az(1.0, 57.0), places=6)

    def test_ultra_wide_edges_reach_half_of_160(self):
        # The whole point of the UW fix: an 160° lens must report ±80° at the
        # frame edges, not ±28.5° left over from the Lepton 3.5's default.
        self.assertAlmostEqual(-80.0, bbox_to_rel_az(0.0, 160.0), places=6)
        self.assertAlmostEqual(80.0, bbox_to_rel_az(1.0, 160.0), places=6)


class LensModelTest(unittest.TestCase):
    def test_equidistant_is_linear_along_the_horizon(self):
        # f-theta on the centreline: quarter of the way in from the left edge is
        # exactly a quarter of the half-FOV short of it.
        self.assertAlmostEqual(-40.0, bbox_to_rel_az(0.25, 160.0, lens=LENS_EQUIDISTANT), places=6)

    def test_rectilinear_edge_matches_configured_fov(self):
        az = bbox_to_rel_az(1.0, 90.0, lens=LENS_RECTILINEAR)
        self.assertAlmostEqual(45.0, az, places=6)

    def test_rectilinear_mid_frame_is_not_linear(self):
        # A pinhole lens compresses angle toward the edges: halfway out in pixels
        # is well under halfway out in degrees (atan(0.5*tan45) = 26.57°).
        az = bbox_to_rel_az(0.75, 90.0, lens=LENS_RECTILINEAR)
        self.assertAlmostEqual(math.degrees(math.atan(0.5)), az, places=6)
        self.assertLess(az, 22.5 + 10.0)

    def test_rectilinear_cannot_be_pushed_past_180(self):
        # tan(90°) is infinite; a 160° "rectilinear" config must stay finite.
        az = bbox_to_rel_az(1.0, 160.0, lens=LENS_RECTILINEAR)
        self.assertTrue(math.isfinite(az))
        self.assertLess(az, 90.0)

    def test_equisolid_edge_matches_configured_fov(self):
        self.assertAlmostEqual(80.0, bbox_to_rel_az(1.0, 160.0, lens=LENS_EQUISOLID), places=6)

    def test_equisolid_squeezes_more_angle_toward_the_edge_than_equidistant(self):
        # Both hit 80° at the edge, but equisolid gets there later: mid-frame it
        # reports a smaller angle, i.e. the periphery is more compressed.
        eqd = bbox_to_rel_az(0.75, 160.0, lens=LENS_EQUIDISTANT)
        eqs = bbox_to_rel_az(0.75, 160.0, lens=LENS_EQUISOLID)
        self.assertLess(eqs, eqd)

    def test_legacy_linear_model_ignores_vertical_offset(self):
        flat = bbox_to_rel_az(1.0, 160.0, cy_norm=1.0, lens=LENS_LINEAR)
        self.assertAlmostEqual(80.0, flat, places=6)


class VerticalCouplingTest(unittest.TestCase):
    def test_corner_of_a_160_hfov_square_frame_wraps_past_the_edge_bearing(self):
        # 160° *horizontal* across a square sensor means the diagonal reaches
        # ~113° off-axis — past the camera plane — so a corner contact is
        # actually behind the lens' own edge bearing, not short of it. This is
        # the flat map's biggest lie and the reason FOV_DIAGONAL exists.
        az, el = pixel_to_bearing(1.0, 1.0, fov_deg=160.0, aspect=1.0)
        self.assertGreater(az, 90.0)
        self.assertLess(el, 0.0)  # bottom of the frame is below the axis

    def test_corner_bearing_is_inside_the_edge_when_fov_is_diagonal(self):
        # Quote the same 160° as a diagonal and the geometry is consistent: the
        # corner sits at 80° off-axis, split between azimuth and elevation, so
        # its azimuth is comfortably inside 80°.
        az, el = pixel_to_bearing(1.0, 1.0, fov_deg=160.0, aspect=1.0, fov_ref=FOV_DIAGONAL)
        self.assertLess(az, 80.0)
        self.assertGreater(az, 0.0)
        self.assertLess(el, 0.0)

    def test_diagonal_reference_narrows_the_horizontal_edge(self):
        h = bbox_to_rel_az(1.0, 160.0, aspect=1.0)
        d = bbox_to_rel_az(1.0, 160.0, aspect=1.0, fov_ref=FOV_DIAGONAL)
        self.assertAlmostEqual(80.0, h, places=6)
        self.assertAlmostEqual(80.0 / math.sqrt(2.0), d, places=6)

    def test_top_of_frame_is_positive_elevation(self):
        _, el = pixel_to_bearing(0.5, 0.0, fov_deg=160.0, aspect=1.0)
        self.assertAlmostEqual(80.0, el, places=6)

    def test_centreline_has_zero_elevation(self):
        az, el = pixel_to_bearing(0.9, 0.5, fov_deg=160.0)
        self.assertAlmostEqual(0.0, el, places=9)
        self.assertGreater(az, 0.0)

    def test_wide_frame_aspect_shrinks_vertical_angle(self):
        _, tall = pixel_to_bearing(0.5, 0.0, fov_deg=160.0, aspect=1.0)
        _, wide = pixel_to_bearing(0.5, 0.0, fov_deg=160.0, aspect=0.5)
        self.assertLess(wide, tall)  # 16:9-ish sensor sees less vertically

    def test_beyond_90_off_axis_stays_signed_correctly(self):
        # A 200° lens: the frame edge is 100° off the nose — behind the camera
        # plane. atan2 must report +100, not fold it back toward zero.
        az, _ = pixel_to_bearing(1.0, 0.5, fov_deg=200.0)
        self.assertAlmostEqual(100.0, az, places=6)

    def test_off_axis_angle_never_exceeds_a_half_turn(self):
        # A nonsense 400° config must still produce a bearing on the circle.
        az, _ = pixel_to_bearing(1.0, 0.5, fov_deg=400.0)
        self.assertLessEqual(abs(az), 180.0)

    def test_degenerate_inputs_are_dead_ahead(self):
        self.assertEqual((0.0, 0.0), pixel_to_bearing(float("nan"), 0.5, 160.0))
        self.assertEqual((0.0, 0.0), pixel_to_bearing(0.5, 0.5, 0.0))
        self.assertEqual((0.0, 0.0), pixel_to_bearing(0.5, 0.5, 160.0))


class Wrap180Test(unittest.TestCase):
    def test_folds_into_signed_half_circle(self):
        self.assertAlmostEqual(-170.0, wrap180(190.0), places=9)
        self.assertAlmostEqual(170.0, wrap180(-190.0), places=9)
        self.assertAlmostEqual(0.0, wrap180(360.0), places=9)
        self.assertAlmostEqual(180.0, wrap180(180.0), places=9)


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

    def test_min_size_boundary_is_inclusive(self):
        est = CollisionEstimator()  # min_size 0.35
        est.update(1, az=2.0, size=0.30, t=0.0)
        self.assertTrue(est.update(1, az=2.0, size=0.35, t=1.0)) # exactly at the floor
        est.update(2, az=2.0, size=0.30, t=0.0)
        self.assertFalse(est.update(2, az=2.0, size=0.349, t=1.0)) # just under

    def test_tracks_are_independent(self):
        est = CollisionEstimator()
        est.update(1, az=2.0, size=0.40, t=0.0)
        est.update(2, az=0.0, size=0.40, t=0.0)
        # id 1 closes on a constant bearing (flags); id 2 crosses fast (does not).
        self.assertTrue(est.update(1, az=2.0, size=0.55, t=1.0))
        self.assertFalse(est.update(2, az=30.0, size=0.55, t=1.0))


if __name__ == "__main__":
    unittest.main()
