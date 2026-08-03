"""Multi-camera rig: spec parsing, camera-local -> vehicle-global bearings,
overlap dedup, and the fused full-circle detect loop. All fake cameras — the
whole fusion path is provable before a single camera is bolted to the car."""

import contextlib
import io
import unittest

from zvision.detector import FakeDetector
from zvision.geometry import FOV_DIAGONAL, LENS_EQUIDISTANT, LENS_RECTILINEAR
from zvision.rig import (
    DEFAULT_DEDUP_DEG,
    ID_STRIDE,
    CameraMount,
    MultiDetector,
    build_camera,
    build_rig,
    coverage_gaps,
    merge_contacts,
    parse_camera_spec,
    to_global,
)
from zvision.threat import DriverThreat


def contact(az, size=0.5, collision=False, tid=1):
    return DriverThreat(rel_az_deg=az, size=size, collision=collision, id=tid)


class StubDetector:
    """A camera that reports a fixed camera-local contact list."""

    def __init__(self, contacts, raises=False):
        self.contacts = contacts
        self.raises = raises
        self.closed = False

    def detect(self, t):
        if self.raises:
            raise RuntimeError("camera unplugged")
        return list(self.contacts)

    def close(self):
        self.closed = True


class ParseSpecTest(unittest.TestCase):
    def test_full_spec(self):
        m = parse_camera_spec("thermal:/dev/video0:az=0:fov=160:lens=fisheye")
        self.assertEqual("thermal", m.source)
        self.assertEqual("/dev/video0", m.device)
        self.assertEqual(0.0, m.mount_az_deg)
        self.assertEqual(160.0, m.fov_deg)
        self.assertEqual(LENS_EQUIDISTANT, m.lens)

    def test_source_only_spec_takes_defaults(self):
        m = parse_camera_spec("fake")
        self.assertEqual("fake", m.source)
        self.assertEqual(0.0, m.mount_az_deg)
        self.assertEqual(160.0, m.fov_deg)

    def test_device_is_optional_before_keys(self):
        m = parse_camera_spec("fake:az=90")
        self.assertEqual(90.0, m.mount_az_deg)
        self.assertEqual("/dev/video0", m.device)

    def test_rgb_camera_with_a_normal_lens(self):
        m = parse_camera_spec("rgb:/dev/video2:az=-90:fov=90:lens=pinhole:width=1280:height=720")
        self.assertEqual(LENS_RECTILINEAR, m.lens)
        self.assertEqual(-90.0, m.mount_az_deg)
        self.assertEqual((1280, 720), (m.width, m.height))

    def test_diagonal_fov_reference(self):
        self.assertEqual(FOV_DIAGONAL, parse_camera_spec("thermal:fov=160:fovref=d").fov_ref)
        self.assertEqual(FOV_DIAGONAL, parse_camera_spec("thermal:fov=160:fovref=diagonal").fov_ref)

    def test_mount_angle_is_wrapped(self):
        self.assertEqual(-90.0, parse_camera_spec("fake:az=270").mount_az_deg)

    def test_default_name_is_indexed(self):
        self.assertEqual("rgb2", parse_camera_spec("rgb", index=2).name)
        self.assertEqual("port", parse_camera_spec("rgb:name=port").name)

    def test_rejects_unknown_source(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("lidar:az=0")

    def test_rejects_unknown_lens(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:lens=potato")

    def test_rejects_unknown_key(self):
        # A typo'd key must not be silently ignored — it would aim a real
        # spotlight at the wrong bearing.
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:azimuth=90")

    def test_rejects_non_numeric_and_non_positive_fov(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:az=left")
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:fov=0")

    def test_rejects_empty_spec(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("")


class ArcTest(unittest.TestCase):
    def test_forward_ultra_wide_arc(self):
        left, right = CameraMount("t", fov_deg=160.0).arc()
        self.assertAlmostEqual(-80.0, left)
        self.assertAlmostEqual(80.0, right)

    def test_rear_camera_arc_wraps_the_seam(self):
        left, right = CameraMount("r", mount_az_deg=180.0, fov_deg=90.0).arc()
        self.assertAlmostEqual(135.0, left)
        self.assertAlmostEqual(-135.0, right)


class ToGlobalTest(unittest.TestCase):
    def test_forward_camera_is_a_passthrough(self):
        mount = CameraMount("fwd", mount_az_deg=0.0)
        g = to_global(contact(30.0, tid=7), mount, 0)
        self.assertAlmostEqual(30.0, g.rel_az_deg)
        self.assertEqual(7, g.id)

    def test_side_camera_rotates_into_global_bearing(self):
        mount = CameraMount("stbd", mount_az_deg=90.0)
        self.assertAlmostEqual(60.0, to_global(contact(-30.0), mount, 1).rel_az_deg)
        self.assertAlmostEqual(120.0, to_global(contact(30.0), mount, 1).rel_az_deg)

    def test_rear_camera_wraps_past_the_seam(self):
        mount = CameraMount("aft", mount_az_deg=180.0)
        # 20° right of a rear camera's centre is 160° left of the nose.
        self.assertAlmostEqual(-160.0, to_global(contact(20.0), mount, 2).rel_az_deg)
        # Dead centre astern stays a stable +180, not -180.
        self.assertAlmostEqual(180.0, to_global(contact(0.0), mount, 2).rel_az_deg)

    def test_ids_are_namespaced_per_camera(self):
        mount = CameraMount("c")
        self.assertEqual(1, to_global(contact(0.0, tid=1), mount, 0).id)
        self.assertEqual(ID_STRIDE + 1, to_global(contact(0.0, tid=1), mount, 1).id)
        self.assertEqual(2 * ID_STRIDE + 1, to_global(contact(0.0, tid=1), mount, 2).id)

    def test_adhoc_id_zero_stays_zero_on_every_camera(self):
        # id 0 means "not a stable track"; the tracker light must keep refusing
        # to latch onto it no matter which camera saw it.
        self.assertEqual(0, to_global(contact(0.0, tid=0), CameraMount("c"), 3).id)

    def test_size_and_collision_pass_through(self):
        g = to_global(contact(0.0, size=0.8, collision=True), CameraMount("c", mount_az_deg=45.0), 1)
        self.assertEqual(0.8, g.size)
        self.assertTrue(g.collision)


class MergeTest(unittest.TestCase):
    def test_contacts_come_back_ordered_by_bearing(self):
        merged = merge_contacts([[contact(120.0, tid=1)], [contact(-30.0, tid=2)], [contact(10.0, tid=3)]])
        self.assertEqual([-30.0, 10.0, 120.0], [t.rel_az_deg for t in merged])

    def test_overlapping_cameras_collapse_one_person_to_one_contact(self):
        merged = merge_contacts([[contact(88.0, size=0.5, tid=1)], [contact(90.0, size=0.6, tid=2)]])
        self.assertEqual(1, len(merged))
        self.assertEqual(2, merged[0].id)  # the nearer sighting survives

    def test_two_people_in_one_camera_are_never_collapsed(self):
        # Same 2° separation, but one camera resolved them: keep both.
        merged = merge_contacts([[contact(88.0, tid=1), contact(90.0, tid=2)]])
        self.assertEqual(2, len(merged))

    def test_collision_survives_a_collapse_over_a_nearer_contact(self):
        merged = merge_contacts(
            [[contact(0.0, size=0.9, tid=1)], [contact(2.0, size=0.4, collision=True, tid=2)]]
        )
        self.assertEqual(1, len(merged))
        self.assertTrue(merged[0].collision)

    def test_dedup_spans_the_seam(self):
        merged = merge_contacts([[contact(179.0, size=0.5, tid=1)], [contact(-179.0, size=0.6, tid=2)]])
        self.assertEqual(1, len(merged))

    def test_contacts_further_apart_than_the_window_both_survive(self):
        merged = merge_contacts([[contact(0.0, tid=1)], [contact(DEFAULT_DEDUP_DEG + 1.0, tid=2)]])
        self.assertEqual(2, len(merged))

    def test_dedup_can_be_disabled(self):
        merged = merge_contacts([[contact(88.0, tid=1)], [contact(89.0, tid=2)]], dedup_deg=0.0)
        self.assertEqual(2, len(merged))

    def test_empty_rig_is_all_clear(self):
        self.assertEqual([], merge_contacts([[], []]))

    def test_result_is_deterministic_for_identical_contacts(self):
        a = merge_contacts([[contact(10.0, tid=5)], [contact(40.0, tid=9)]])
        b = merge_contacts([[contact(10.0, tid=5)], [contact(40.0, tid=9)]])
        self.assertEqual(a, b)


class MultiDetectorTest(unittest.TestCase):
    def _ring(self, **kwargs):
        cams = [
            (CameraMount("fwd", mount_az_deg=0.0), StubDetector([contact(10.0, tid=1)])),
            (CameraMount("stbd", mount_az_deg=90.0), StubDetector([contact(10.0, tid=1)])),
            (CameraMount("aft", mount_az_deg=180.0), StubDetector([contact(10.0, tid=1)])),
        ]
        return MultiDetector(cams, **kwargs)

    def test_three_cameras_fuse_into_one_full_circle_list(self):
        merged = self._ring().detect(0.0)
        self.assertEqual(3, len(merged))
        # Each camera's 10°-right contact, rotated by its mount: forward stays
        # 10, starboard becomes 100, aft becomes -170. Ordered port→starboard.
        self.assertEqual([-170.0, 10.0, 100.0], [t.rel_az_deg for t in merged])
        self.assertEqual({1, ID_STRIDE + 1, 2 * ID_STRIDE + 1}, {t.id for t in merged})

    def test_bearings_span_beyond_the_forward_arc(self):
        merged = self._ring().detect(0.0)
        self.assertTrue(any(abs(t.rel_az_deg) > 90.0 for t in merged))

    def test_a_failing_camera_does_not_take_down_the_rig(self):
        cams = [
            (CameraMount("fwd"), StubDetector([contact(0.0, tid=1)])),
            (CameraMount("aft", mount_az_deg=180.0), StubDetector([], raises=True)),
        ]
        rig = MultiDetector(cams)
        with contextlib.redirect_stderr(io.StringIO()) as err:
            merged = rig.detect(0.0)
            rig.detect(0.1)
        self.assertEqual(1, len(merged))
        self.assertEqual(1, err.getvalue().count("aft"))  # reported once, then quiet

    def test_close_closes_every_camera(self):
        cams = [
            (CameraMount("a"), StubDetector([])),
            (CameraMount("b"), StubDetector([])),
        ]
        rig = MultiDetector(cams)
        rig.close()
        self.assertTrue(all(d.closed for _, d in cams))

    def test_mounts_are_exposed_for_logging(self):
        self.assertEqual(["fwd", "stbd", "aft"], [m.name for m in self._ring().mounts])

    def test_build_camera_fake_source_needs_no_hardware(self):
        det = build_camera(CameraMount("f", source="fake"))
        self.assertIsInstance(det, FakeDetector)
        det.close()

    def test_build_rig_uses_the_injected_factory(self):
        mounts = [CameraMount("a"), CameraMount("b", mount_az_deg=90.0)]
        rig = build_rig(mounts, factory=lambda m: StubDetector([contact(0.0, tid=1)]))
        self.assertEqual(2, len(rig.detect(0.0)))


class CoverageGapsTest(unittest.TestCase):
    def test_single_ultra_wide_leaves_the_rear_blind(self):
        gaps = coverage_gaps([CameraMount("t", fov_deg=160.0)])
        self.assertEqual(1, len(gaps))
        start, end = gaps[0]
        # 1° sampling, so the reported edges land within a step of the true arc.
        self.assertAlmostEqual(80.0, start, delta=1.5)
        self.assertAlmostEqual(280.0, end, delta=1.5)  # sweeps clockwise through astern

    def test_a_closed_ring_reports_no_gaps(self):
        ring = [CameraMount(f"c{i}", mount_az_deg=i * 90.0, fov_deg=100.0) for i in range(4)]
        self.assertEqual([], coverage_gaps(ring))

    def test_no_cameras_is_entirely_blind(self):
        self.assertEqual([(-180.0, 180.0)], coverage_gaps([]))

    def test_two_opposed_narrow_cameras_leave_two_gaps(self):
        mounts = [CameraMount("f", fov_deg=60.0), CameraMount("a", mount_az_deg=180.0, fov_deg=60.0)]
        self.assertEqual(2, len(coverage_gaps(mounts)))


if __name__ == "__main__":
    unittest.main()
