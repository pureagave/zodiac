"""Multi-camera rig: spec parsing, camera-local -> vehicle-global bearings,
overlap dedup, and the fused full-circle detect loop. All fake cameras — the
whole fusion path is provable before a single camera is bolted to the car."""

import contextlib
import io
import unittest

from zvision.detector import DetectorTuning, FakeDetector
from zvision.geometry import FOV_DIAGONAL, FOV_HORIZONTAL, LENS_EQUIDISTANT, LENS_RECTILINEAR
from zvision.rig import (
    DEFAULT_DEDUP_DEG,
    ID_STRIDE,
    MAX_MOUNT_TILT_DEG,
    CameraMount,
    MultiDetector,
    build_camera,
    build_rig,
    coverage_gaps,
    covered_arcs,
    merge_contacts,
    parse_camera_spec,
    to_global,
    validate_mount,
)
from zvision.threat import DriverThreat


def contact(az, size=0.5, collision=False, tid=1):
    return DriverThreat(rel_az_deg=az, size=size, collision=collision, id=tid)


class StubDetector:
    """A camera that reports a fixed camera-local contact list, and — for the
    blind-arc tests — a fixed ``delivering`` verdict."""

    def __init__(self, contacts, raises=False, delivering=True):
        self.contacts = contacts
        self.raises = raises
        self.closed = False
        self._delivering = delivering

    def detect(self, t):
        if self.raises:
            raise RuntimeError("camera unplugged")
        return list(self.contacts)

    def delivering(self, *_args):
        return self._delivering

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


    def test_device_path_may_contain_colons(self):
        # Stable /dev/v4l/by-path names contain colons, and they are the
        # *correct* way to name a camera — /dev/videoN reshuffles across
        # reboots. The parser must not mistake path colons for field separators.
        path = "/dev/v4l/by-path/platform-3610000.usb-usb-0:2.3:1.0-video-index0"
        m = parse_camera_spec(f"thermal:{path}:az=90:fov=160")
        self.assertEqual(path, m.device)
        self.assertEqual(90.0, m.mount_az_deg)
        self.assertEqual(160.0, m.fov_deg)

    def test_colon_path_with_no_options(self):
        path = "/dev/v4l/by-path/platform-x-usb-0:1.2:1.0-video-index0"
        self.assertEqual(path, parse_camera_spec(f"rgb:{path}").device)

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


class NonFiniteSpecTest(unittest.TestCase):
    """float() accepts "nan" and "inf", and every guard downstream is a
    comparison NaN answers False to. Before these were rejected, az=nan passed
    --check and then turned every bearing from that camera into the string
    "nan" on the wire — which the tablet parser silently drops, so the service
    looked green while the HUD was blind to that whole arc. farh=nan was
    worse still: every contact read as size 1.0, touching the vehicle."""

    def test_a_nan_mount_angle_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("fake:az=nan")

    def test_an_infinite_fov_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("fake:fov=inf")

    def test_non_finite_tuning_values_are_rejected(self):
        for key in ("minarea", "match", "farh", "nearh", "azrate", "minsize"):
            with self.assertRaises(ValueError, msg=f"{key}=nan must not pass"):
                parse_camera_spec(f"fake:{key}=nan")

    def test_a_nan_fps_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("fake:fps=nan")

    def test_a_nan_default_flowing_in_from_the_rig_is_still_caught(self):
        # The rig-wide CLI flags come from argparse, which also accepts "nan";
        # a bad default must not sneak into a spec that never mentions it.
        bad_default = CameraMount("d", fov_deg=float("nan"))
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:az=90", defaults=bad_default)

    def test_a_camera_name_that_is_a_path_is_rejected(self):
        # The name becomes a recording directory (<dump>/<name>/...); a
        # separator would scatter frames outside the dump dir.
        for bad in ("a/b", "..", "port\\aft"):
            with self.assertRaises(ValueError, msg=f"name={bad!r} must not pass"):
                parse_camera_spec(f"fake:name={bad}")


class MountAttitudeTest(unittest.TestCase):
    """A ring camera has no pitch/roll term in the bearing math (to_global
    applies only mount_az_deg), so a tilted mount silently folds elevation
    into azimuth — measured up to 4.0° at 5° pitch and 35.9° at 30° pitch on
    the Lepton's 160°-diagonal fisheye corner (roll is worse: 5.6° at 5°).
    el/roll let a mount state its measured attitude, and --check refuses
    anything past MAX_MOUNT_TILT_DEG so a non-level mount fails loudly
    offline instead of silently on the vehicle."""

    def test_el_and_roll_parse_and_store(self):
        self.assertEqual(3.0, parse_camera_spec("fake:el=3").mount_el_deg)
        self.assertEqual(2.0, parse_camera_spec("fake:roll=2").mount_roll_deg)

    def test_default_mount_is_level(self):
        m = CameraMount("t")
        self.assertEqual(0.0, m.mount_el_deg)
        self.assertEqual(0.0, m.mount_roll_deg)

    def test_a_non_level_mount_is_refused(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("fake:el=30")
        with self.assertRaises(ValueError):
            parse_camera_spec("fake:roll=30")

    def test_a_near_level_mount_is_accepted(self):
        # Pins the limit as a real tolerance, not a de-facto ban on any tilt.
        self.assertEqual(2.0, parse_camera_spec("fake:el=2").mount_el_deg)

    def test_non_finite_attitude_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("fake:el=nan")
        with self.assertRaises(ValueError):
            parse_camera_spec("fake:roll=inf")

    def test_validate_mount_rejects_tilt_directly(self):
        # The shared gate the legacy single-camera path also runs through.
        m = CameraMount("c", mount_el_deg=MAX_MOUNT_TILT_DEG + 1.0)
        with self.assertRaises(ValueError):
            validate_mount(m)


class TuningSpecTest(unittest.TestCase):
    """The field-tuning knobs must be reachable without editing code — that's
    the whole point of them, since none can be got right until the rig is on the
    vehicle with real people walking around it."""

    def test_defaults_when_unspecified(self):
        t = parse_camera_spec("rgb").tuning
        self.assertEqual(DetectorTuning(), t)

    def test_every_tuning_key_parses(self):
        m = parse_camera_spec(
            "rgb:minarea=0.02:match=0.3:farh=0.1:nearh=0.8:azrate=5:minsize=0.5"
        )
        self.assertEqual(0.02, m.tuning.min_area_frac)
        self.assertEqual(0.3, m.tuning.match_dist)
        self.assertEqual(0.1, m.tuning.far_h)
        self.assertEqual(0.8, m.tuning.near_h)
        self.assertEqual(5.0, m.tuning.collision_az_rate_dps)
        self.assertEqual(0.5, m.tuning.collision_min_size)

    def test_defaults_flow_in_from_the_rig(self):
        rig_default = CameraMount("d", tuning=DetectorTuning(min_area_frac=0.05), fov_deg=87.0)
        m = parse_camera_spec("rgb:az=90", defaults=rig_default)
        self.assertEqual(0.05, m.tuning.min_area_frac)  # inherited
        self.assertEqual(87.0, m.fov_deg)               # optics inherit too

    def test_per_camera_value_overrides_the_rig_default(self):
        rig_default = CameraMount("d", tuning=DetectorTuning(min_area_frac=0.05))
        m = parse_camera_spec("rgb:minarea=0.001", defaults=rig_default)
        self.assertEqual(0.001, m.tuning.min_area_frac)

    def test_inverted_range_calibration_is_rejected(self):
        # near_h <= far_h makes bbox_height_to_size return 0 for everything, so
        # every contact would read as maximum range — silently useless.
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:farh=0.9:nearh=0.1")

    def test_typo_in_a_tuning_key_is_loud(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:minarae=0.02")

    def test_non_numeric_tuning_value_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_camera_spec("rgb:minarea=lots")

    def test_tuning_reaches_the_built_camera(self):
        seen = {}

        def factory(mount):
            seen["tuning"] = mount.tuning
            return StubDetector([])

        build_rig([parse_camera_spec("rgb:minarea=0.09")], factory=factory)
        self.assertEqual(0.09, seen["tuning"].min_area_frac)


class FovReferenceDefaultTest(unittest.TestCase):
    """The default is DIAGONAL, and that is a measured fact, not a preference.

    Measured 2026-08-10 against a known target at 85 cm off centre, 1.0 m away
    (a true 40.4°): the running rig reported 42.5°, implying a ~61° horizontal
    half-FOV. The horizontal reading of the same 160° spec would have put the
    half-FOV at 80° and made that contact report as 32.3° — nothing like the
    observation. Defaulting to horizontal stretched every bearing by 1.25x for
    any camera spec that omitted fovref, and the tracker light is aimed from
    these bearings.
    """

    def test_the_default_fov_reference_is_diagonal(self):
        self.assertEqual(FOV_DIAGONAL, CameraMount("t").fov_ref)

    def test_the_default_ultra_wide_camera_covers_64_not_80(self):
        left, right = CameraMount("t", fov_deg=160.0, width=160, height=120).arc()
        self.assertAlmostEqual(-64.0, left, places=3)
        self.assertAlmostEqual(64.0, right, places=3)

    def test_the_cli_default_matches_the_dataclass_default(self):
        # Two defaults for one decision is how they drift apart.
        from zvision.app import _parse_args

        self.assertEqual(CameraMount("t").fov_ref, _parse_args([]).fov_ref)


class ArcTest(unittest.TestCase):
    def test_forward_ultra_wide_arc(self):
        # Width-quoted explicitly: this test is about the arc arithmetic, not
        # about which convention happens to be the default.
        left, right = CameraMount("t", fov_deg=160.0, fov_ref=FOV_HORIZONTAL).arc()
        self.assertAlmostEqual(-80.0, left)
        self.assertAlmostEqual(80.0, right)

    def test_rear_camera_arc_wraps_the_seam(self):
        left, right = CameraMount(
            "r", mount_az_deg=180.0, fov_deg=90.0, fov_ref=FOV_HORIZONTAL
        ).arc()
        self.assertAlmostEqual(135.0, left)
        self.assertAlmostEqual(-135.0, right)

    def test_a_diagonal_quoted_fov_covers_a_narrower_arc_than_its_number(self):
        # 160° *diagonal* over the Lepton's 4:3 sensor reaches only ±64°
        # horizontally. Crediting ±80° made the coverage report — the thing
        # you check before trusting the ring — claim arcs no camera can see.
        m = CameraMount("t", fov_deg=160.0, fov_ref=FOV_DIAGONAL, width=160, height=120)
        left, right = m.arc()
        self.assertAlmostEqual(64.0, right, places=3)
        self.assertAlmostEqual(-64.0, left, places=3)

    def test_a_width_quoted_fov_still_covers_exactly_its_number(self):
        # Every lens model puts the frame edge at fov/2 when the FOV is quoted
        # across the width. Stated explicitly rather than relying on the
        # default, which is diagonal.
        for lens in ("equidistant", "rectilinear", "equisolid", "linear"):
            m = CameraMount(
                "c", fov_deg=100.0, lens=lens, width=1280, height=720,
                fov_ref=FOV_HORIZONTAL,
            )
            self.assertAlmostEqual(50.0, m.half_h_fov_deg(), places=3, msg=lens)


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


    def test_a_real_track_is_never_folded_onto_the_adhoc_sentinel(self):
        # id 0 means "not a stable track, never latch onto it". A detector that
        # ignores the id contract and hands over exactly ID_STRIDE must not have
        # that contact silently marked un-latchable — the DMX light would refuse
        # to hold a real person.
        mount = CameraMount("c")
        for bad in (ID_STRIDE, 2 * ID_STRIDE, 5 * ID_STRIDE):
            self.assertNotEqual(0, to_global(contact(0.0, tid=bad), mount, 0).id, f"id {bad}")

    def test_ids_from_different_cameras_stay_in_their_own_blocks(self):
        # Even for out-of-contract ids, a camera must not reach into another
        # camera's id block.
        for cam in range(3):
            for tid in (1, ID_STRIDE - 1, ID_STRIDE, ID_STRIDE + 1):
                gid = to_global(contact(0.0, tid=tid), CameraMount("c"), cam).id
                self.assertEqual(cam, gid // ID_STRIDE, f"cam {cam} tid {tid} -> {gid}")

    def test_the_detector_id_wrap_matches_the_rig_stride(self):
        # These two constants encode one contract; if they drift, ids alias.
        from zvision.normalize import TRACK_ID_LIMIT
        self.assertEqual(TRACK_ID_LIMIT, ID_STRIDE)

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

    def test_every_real_camera_type_honours_the_close_contract(self):
        # MultiDetector.close politely skips anything without a close(), so a
        # camera class that forgets one leaks its V4L2 handle in silence —
        # UvcCamera shipped that way while ThermalCamera had close() twice.
        from zvision.capture import ThermalCamera, UvcCamera

        self.assertTrue(callable(getattr(UvcCamera, "close", None)))
        self.assertTrue(callable(getattr(ThermalCamera, "close", None)))

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

    def test_a_camera_that_will_not_open_is_skipped_not_fatal(self):
        def factory(mount):
            if mount.name == "missing":
                raise RuntimeError("could not open camera '/dev/video9'")
            return StubDetector([contact(0.0, tid=1)])

        mounts = [CameraMount("missing", device="/dev/video9"), CameraMount("good")]
        with contextlib.redirect_stderr(io.StringIO()) as err:
            rig = build_rig(mounts, factory=factory)
        self.assertEqual(["good"], [m.name for m in rig.mounts])
        self.assertEqual(1, len(rig.detect(0.0)))
        self.assertIn("/dev/video9", err.getvalue())

    def test_a_rig_where_nothing_opens_is_empty(self):
        def factory(mount):
            raise RuntimeError("no camera")

        with contextlib.redirect_stderr(io.StringIO()):
            rig = build_rig([CameraMount("a"), CameraMount("b")], factory=factory)
        self.assertEqual([], rig.mounts)
        self.assertEqual([], rig.detect(0.0))


class CoverageGapsTest(unittest.TestCase):
    def test_single_ultra_wide_leaves_the_rear_blind(self):
        gaps = coverage_gaps([CameraMount("t", fov_deg=160.0, fov_ref=FOV_HORIZONTAL)])
        self.assertEqual(1, len(gaps))
        start, end = gaps[0]
        # 1° sampling, so the reported edges land within a step of the true arc.
        self.assertAlmostEqual(80.0, start, delta=1.5)
        self.assertAlmostEqual(280.0, end, delta=1.5)  # sweeps clockwise through astern

    def test_a_closed_ring_reports_no_gaps(self):
        ring = [
            CameraMount(f"c{i}", mount_az_deg=i * 90.0, fov_deg=100.0, fov_ref=FOV_HORIZONTAL)
            for i in range(4)
        ]
        self.assertEqual([], coverage_gaps(ring))

    def test_no_cameras_is_entirely_blind(self):
        self.assertEqual([(-180.0, 180.0)], coverage_gaps([]))

    def test_two_opposed_narrow_cameras_leave_two_gaps(self):
        mounts = [CameraMount("f", fov_deg=60.0), CameraMount("a", mount_az_deg=180.0, fov_deg=60.0)]
        self.assertEqual(2, len(coverage_gaps(mounts)))

    def test_diagonal_quoted_fovs_do_not_fake_a_closed_ring(self):
        # Quoted as widths this ring closes (160+120+120 with overlap); quoted
        # as *diagonals* the same numbers leave real blind arcs between the
        # cameras. Reporting "blind: none" here is a confident all-clear
        # pointed at exactly the person standing in the gap.
        ring = [
            CameraMount("f", fov_deg=160.0, fov_ref=FOV_DIAGONAL, width=160, height=120),
            CameraMount("s", mount_az_deg=120.0, fov_deg=120.0, fov_ref=FOV_DIAGONAL, width=1280, height=720),
            CameraMount("p", mount_az_deg=-120.0, fov_deg=120.0, fov_ref=FOV_DIAGONAL, width=1280, height=720),
        ]
        self.assertNotEqual([], coverage_gaps(ring))
        as_widths = [
            CameraMount("f", fov_deg=160.0, width=160, height=120, fov_ref=FOV_HORIZONTAL),
            CameraMount(
                "s", mount_az_deg=120.0, fov_deg=120.0, width=1280, height=720,
                fov_ref=FOV_HORIZONTAL,
            ),
            CameraMount(
                "p", mount_az_deg=-120.0, fov_deg=120.0, width=1280, height=720,
                fov_ref=FOV_HORIZONTAL,
            ),
        ]
        self.assertEqual([], coverage_gaps(as_widths))


class LiveMountsTest(unittest.TestCase):
    """RES-P2-1: the blind set is computed from *liveness*, not config. A camera
    that is configured but not delivering — stalled, or throwing this frame —
    must drop out of coverage so its arc goes blind, not all-clear."""

    def test_a_non_delivering_camera_is_excluded(self):
        cams = [
            (CameraMount("fwd", mount_az_deg=0.0), StubDetector([contact(0.0)], delivering=True)),
            (CameraMount("aft", mount_az_deg=180.0), StubDetector([], delivering=False)),
        ]
        rig = MultiDetector(cams)
        self.assertEqual(["fwd"], [m.name for m in rig.live_mounts()])

    def test_all_delivering_cameras_are_included(self):
        # Positive control: nothing is excluded when every camera delivers.
        cams = [
            (CameraMount("fwd", mount_az_deg=0.0), StubDetector([contact(0.0)], delivering=True)),
            (CameraMount("aft", mount_az_deg=180.0), StubDetector([], delivering=True)),
        ]
        rig = MultiDetector(cams)
        self.assertEqual(["fwd", "aft"], [m.name for m in rig.live_mounts()])

    def test_a_camera_that_threw_this_frame_is_excluded(self):
        # The exception path (unplugged mid-run) also drops from coverage: it is
        # in _failed after detect(), so its arc must read blind.
        cams = [
            (CameraMount("fwd", mount_az_deg=0.0), StubDetector([contact(0.0)])),
            (CameraMount("aft", mount_az_deg=180.0), StubDetector([], raises=True)),
        ]
        rig = MultiDetector(cams)
        with contextlib.redirect_stderr(io.StringIO()):
            rig.detect(0.0)  # marks aft failed
        self.assertEqual(["fwd"], [m.name for m in rig.live_mounts()])

    def test_a_camera_without_a_delivering_method_is_assumed_live(self):
        # A detector predating the signal (or a bare stub) must not silently
        # blind its arc — a missing signal counts as delivering.
        class NoSignalStub:
            def detect(self, t):
                return []

        cams = [(CameraMount("legacy", mount_az_deg=0.0), NoSignalStub())]
        rig = MultiDetector(cams)
        self.assertEqual(["legacy"], [m.name for m in rig.live_mounts()])


class CoveredArcsTest(unittest.TestCase):
    """The wire signal itself: the union of arcs a live camera watches, and its
    exact complement (the blind set). Both share one scan so they can never
    disagree."""

    def test_single_thermal_covers_the_forward_arc(self):
        # The shipped config: one forward UW thermal (160 deg diagonal -> ±64
        # horizontal). This wire value must agree with the tablet's static
        # COVERED_ARCS fallback so old and new Jetsons render the same arc.
        covered = covered_arcs([CameraMount("t")])
        self.assertEqual(1, len(covered))
        start, end = covered[0]
        self.assertAlmostEqual(-64.0, start, delta=1.5)
        self.assertAlmostEqual(64.0, end, delta=1.5)

    def test_single_thermal_leaves_the_stern_blind(self):
        blind = coverage_gaps([CameraMount("t")])
        self.assertEqual(1, len(blind))
        start, end = blind[0]
        self.assertAlmostEqual(64.0, start, delta=1.5)
        self.assertAlmostEqual(296.0, end, delta=1.5)  # sweeps clockwise through astern

    def test_a_closed_live_ring_has_no_blind_arc(self):
        ring = [
            CameraMount(f"c{i}", mount_az_deg=i * 90.0, fov_deg=100.0, fov_ref=FOV_HORIZONTAL)
            for i in range(4)
        ]
        self.assertEqual([], coverage_gaps(ring))
        self.assertEqual([(-180.0, 180.0)], covered_arcs(ring))

    def test_nothing_live_is_wholly_blind(self):
        self.assertEqual([], covered_arcs([]))
        self.assertEqual([(-180.0, 180.0)], coverage_gaps([]))

    def test_a_stalled_camera_in_a_closed_ring_turns_only_its_arc_blind(self):
        # A four-camera ring that closes, but the aft (az=180) camera is not
        # delivering. Exactly the stern must go blind; the forward arc stays.
        mounts = [
            CameraMount(f"c{i}", mount_az_deg=i * 90.0, fov_deg=100.0, fov_ref=FOV_HORIZONTAL)
            for i in range(4)
        ]
        cams = [(m, StubDetector([], delivering=(m.mount_az_deg != 180.0))) for m in mounts]
        rig = MultiDetector(cams)
        live = rig.live_mounts()
        self.assertNotIn(180.0, [m.mount_az_deg for m in live])
        blind = coverage_gaps(live)
        self.assertTrue(any(start <= 180.0 <= end for start, end in blind), "the stern must be blind")
        self.assertFalse(any(start <= 0.0 <= end for start, end in blind), "the bow must stay covered")


if __name__ == "__main__":
    unittest.main()
