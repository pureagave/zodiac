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

    def test_verbose_rig_banner_reports_the_blind_arc(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            _run_once(["-v", "--camera", "fake:az=0:fov=160"])
        self.assertIn("blind:", out.getvalue())


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
