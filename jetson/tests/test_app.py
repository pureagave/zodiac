"""End-to-end smoke of the runner over a loopback socket: `--once` must emit one
detection frame and then the guaranteed all-clear frame on exit (the "HUD doesn't
freeze" contract)."""

import contextlib
import io
import socket
import unittest

from zvision.app import _mounts_from_args, _parse_args, main
from zvision.geometry import FOV_DIAGONAL, LENS_EQUIDISTANT, LENS_RECTILINEAR
from zvision.threat_protocol import parse_frame


def _run_once(extra_args):
    """Run one `--once` cycle against a bound loopback socket and return the
    parsed frames it emitted."""
    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("127.0.0.1", 0))
    rx.settimeout(2.0)
    port = rx.getsockname()[1]
    # rx is already bound, so datagrams main() sends are buffered for us to
    # read after it returns — no threading needed.
    rc = main(
        ["--once", "--group", "127.0.0.1", "--broadcast", "127.0.0.1", "--port", str(port)]
        + extra_args
    )
    frames = []
    try:
        while True:
            data, _ = rx.recvfrom(4096)
            frames.append(parse_frame(data.decode("ascii")))
    except socket.timeout:
        pass
    finally:
        rx.close()
    return rc, frames


class OnceRunTest(unittest.TestCase):
    def test_once_emits_one_frame_then_all_clear(self):
        rc, frames = _run_once(["--source", "fake"])
        self.assertEqual(0, rc)
        self.assertGreaterEqual(len(frames), 2)
        self.assertEqual(3, len(frames[0]))  # first frame: the 3 fake contacts
        self.assertIn([], frames)            # the exit all-clear frame is present


class RigCliTest(unittest.TestCase):
    def test_three_camera_rig_runs_end_to_end(self):
        rc, frames = _run_once(
            [
                "--camera", "fake:az=0:fov=160",
                "--camera", "fake:az=120:fov=90",
                "--camera", "fake:az=-120:fov=90",
            ]
        )
        self.assertEqual(0, rc)
        # Three fake cameras x 3 contacts, minus whatever the overlap dedup
        # collapsed — but strictly more than one camera's worth.
        self.assertGreater(len(frames[0]), 3)

    def test_rig_bearings_reach_around_the_vehicle(self):
        _, frames = _run_once(["--camera", "fake:az=150", "--camera", "fake:az=-150"])
        self.assertTrue(any(abs(t.rel_az_deg) > 90.0 for t in frames[0]))

    def test_a_bad_camera_spec_fails_loudly_without_broadcasting(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            rc = main(["--once", "--camera", "fake:azimuth=90"])
        self.assertEqual(2, rc)
        self.assertIn("azimuth", err.getvalue())

    def test_exits_rather_than_broadcasting_all_clear_while_blind(self):
        # Every camera failed to open: a confident empty frame would tell the
        # HUD "nobody around" when we simply can't see.
        with contextlib.redirect_stderr(io.StringIO()) as err:
            rc = main(["--once", "--camera", "rgb:/dev/video99"])
        self.assertEqual(3, rc)
        self.assertIn("no cameras opened", err.getvalue())

    def test_verbose_rig_banner_reports_the_blind_arc(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            _run_once(["-v", "--camera", "fake:az=0:fov=160"])
        self.assertIn("blind:", out.getvalue())


class CheckModeTest(unittest.TestCase):
    """`--check` is the guard against a bad line in /etc/default/zvision. The
    service runs Restart=always, so an unvalidated typo becomes a crash loop —
    which is a miserable thing to debug from a laptop in the dust."""

    def test_valid_config_passes_and_prints_the_resolved_rig(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            rc = main(["--check", "--camera", "fake:az=0:fov=160", "--camera", "fake:az=180:fov=200"])
        self.assertEqual(0, rc)
        text = out.getvalue()
        self.assertIn("config OK", text)
        self.assertIn("blind:", text)
        self.assertIn("tuning:", text)

    def test_check_does_not_touch_the_network(self):
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx.bind(("127.0.0.1", 0))
        rx.settimeout(0.4)
        port = rx.getsockname()[1]
        with contextlib.redirect_stdout(io.StringIO()):
            rc = main(["--check", "--group", "127.0.0.1", "--broadcast", "127.0.0.1", "--port", str(port)])
        self.assertEqual(0, rc)
        with self.assertRaises(socket.timeout):
            rx.recvfrom(4096)  # nothing was broadcast
        rx.close()

    def test_bad_spec_fails_check_before_anything_starts(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            rc = main(["--check", "--camera", "rgb:az=90:minarae=0.02"])
        self.assertEqual(2, rc)
        self.assertIn("minarae", err.getvalue())

    def test_inverted_range_calibration_fails_check(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            rc = main(["--check", "--far-h", "0.9", "--near-h", "0.1"])
        self.assertEqual(2, rc)
        self.assertIn("near-h", err.getvalue())


class TuningFlagsTest(unittest.TestCase):
    def test_global_flags_become_the_rig_default(self):
        mounts = _mounts_from_args(
            _parse_args(["--min-area", "0.02", "--collision-az-rate", "7", "--camera", "fake:az=0"])
        )
        self.assertEqual(0.02, mounts[0].tuning.min_area_frac)
        self.assertEqual(7.0, mounts[0].tuning.collision_az_rate_dps)

    def test_per_camera_spec_wins_over_the_global_flag(self):
        mounts = _mounts_from_args(
            _parse_args(["--min-area", "0.02", "--camera", "fake:az=0:minarea=0.005", "--camera", "fake:az=180"])
        )
        self.assertEqual(0.005, mounts[0].tuning.min_area_frac)  # overridden
        self.assertEqual(0.02, mounts[1].tuning.min_area_frac)   # inherited

    def test_tuning_flags_apply_on_the_legacy_single_camera_path(self):
        mounts = _mounts_from_args(_parse_args(["--source", "thermal", "--near-h", "0.7"]))
        self.assertEqual(0.7, mounts[0].tuning.near_h)


class MountsFromArgsTest(unittest.TestCase):
    def test_legacy_single_camera_defaults_to_the_ultra_wide(self):
        mounts = _mounts_from_args(_parse_args(["--source", "thermal"]))
        self.assertEqual(1, len(mounts))
        self.assertEqual(160.0, mounts[0].fov_deg)      # not the old 57
        self.assertEqual(LENS_EQUIDISTANT, mounts[0].lens)
        self.assertEqual(0.0, mounts[0].mount_az_deg)

    def test_legacy_flags_still_configure_the_single_camera(self):
        mounts = _mounts_from_args(
            _parse_args(["--source", "rgb", "--hfov", "90", "--lens", "rectilinear", "--fov-ref", "d"])
        )
        self.assertEqual(90.0, mounts[0].fov_deg)
        self.assertEqual(LENS_RECTILINEAR, mounts[0].lens)
        self.assertEqual(FOV_DIAGONAL, mounts[0].fov_ref)

    def test_camera_flags_override_the_legacy_single_camera(self):
        mounts = _mounts_from_args(
            _parse_args(["--source", "thermal", "--camera", "rgb:az=90", "--camera", "rgb:az=-90"])
        )
        self.assertEqual([90.0, -90.0], [m.mount_az_deg for m in mounts])


if __name__ == "__main__":
    unittest.main()
