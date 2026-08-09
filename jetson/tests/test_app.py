"""End-to-end smoke of the runner over a loopback socket: `--once` must emit one
detection frame and then the guaranteed all-clear frame on exit (the "HUD doesn't
freeze" contract)."""

import contextlib
import io
import socket
import unittest
from unittest import mock

from zvision.app import _mounts_from_args, _parse_args, main
from zvision.geometry import FOV_DIAGONAL, LENS_EQUIDISTANT, LENS_RECTILINEAR
from zvision.threat_protocol import parse_frame
from zvision.tracker import TrackerConfig


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


class DmxRunTest(unittest.TestCase):
    """The runner's half of the tracker-light contract: the sink is actually
    driven each frame, and shutdown parks the head and blacks it out — so a
    service stop can't leave a spotlight frozen mid-sky on the last person it
    tracked."""

    @classmethod
    def setUpClass(cls):
        # One shared --once run: each loopback run costs a 2 s socket drain,
        # and both tests assert on the same captured sink.
        import zvision.dmx as dmx

        captured = {}
        real = dmx.build_sink

        def capturing(kind, universe=0, base_url=""):
            sink = real(kind, universe=universe, base_url=base_url)
            captured["sink"] = sink
            return sink

        with mock.patch.object(dmx, "build_sink", capturing):
            cls.rc, _ = _run_once(["--source", "fake", "--dmx", "fake", "--dmx-no-sound"])
        cls.sink = captured["sink"]

    def test_the_sink_is_driven_and_the_head_aims_at_the_scene(self):
        self.assertEqual(0, self.rc)
        self.assertGreaterEqual(self.sink.sends, 2)  # at least one aim + the park
        self.assertNotEqual(0, self.sink.frame[0])   # pan coarse: it pointed somewhere

    def test_the_channel_mode_flag_actually_reaches_the_fixture(self):
        # config_for_channel_mode is well covered in isolation, but nothing
        # proved the CLI flag reaches it: hardcoding `11` at the call site in
        # app.py passed the entire suite. On a 9-channel head that mutant sends
        # pan-fine to tilt, tilt to the colour wheel, and a dimmer write of 255
        # to ch8 -- which in 9-channel mode is the AUTO-PROGRAM channel, so the
        # head is handed to its internal show at full brightness.
        import zvision.dmx as dmx

        captured = {}
        real = dmx.build_sink

        def capturing(kind, universe=0, base_url=""):
            sink = real(kind, universe=universe, base_url=base_url)
            captured["sink"] = sink
            return sink

        with mock.patch.object(dmx, "build_sink", capturing):
            rc, _ = _run_once(
                ["--source", "fake", "--dmx", "fake", "--dmx-no-sound",
                 "--dmx-channels", "9"]
            )
        sink = captured["sink"]
        self.assertEqual(0, rc)
        self.assertGreaterEqual(sink.sends, 2)
        # 9-channel map is pan 1 / tilt 2 / dimmer 6 -- nothing else may be driven.
        self.assertLessEqual(set(sink.last_channels), {1, 2, 6})
        # ch8 (auto programs) and ch9 (mode select / motor reset) stay untouched.
        self.assertEqual(0, sink.frame[7])
        self.assertEqual(0, sink.frame[8])

    def test_exit_parks_the_head_and_blacks_it_out(self):
        # The master dimmer must end dark: mid-run it was 255 (the fake scene
        # has a live contact), so a nonzero here means the park-on-exit frame
        # never went out. Channel comes from the config — hardcoding it is how
        # this test previously agreed with the colour-wheel wiring bug.
        dimmer = TrackerConfig().dimmer_channel
        self.assertEqual(0, self.sink.frame[dimmer - 1])
        self.assertEqual(0, self.sink.last_channels.get(dimmer))


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


class CheckCatchesStartupHazardsTest(unittest.TestCase):
    """Everything here used to pass --check and then fail live — either as a
    Restart=always crash loop (bad --iface-ip died inside inet_aton with a
    traceback) or as something quieter and worse: a healthy-looking service
    broadcasting to nobody, or at an unusable rate."""

    def _check(self, extra):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            rc = main(["--check"] + extra)
        return rc, err.getvalue()

    def test_a_malformed_iface_ip_fails_check_instead_of_crash_looping(self):
        rc, err = self._check(["--iface-ip", "192.168.0.999"])
        self.assertEqual(2, rc)
        self.assertIn("iface-ip", err)

    def test_a_typoed_group_fails_check_instead_of_broadcasting_to_nobody(self):
        # A bad group address is only rejected per-send, and sends swallow
        # OSError — the frame counter ticks while zero targets receive.
        rc, err = self._check(["--group", "239.7.7.300"])
        self.assertEqual(2, rc)
        self.assertIn("group", err)

    def test_a_zero_or_negative_rate_is_rejected(self):
        self.assertEqual(2, self._check(["--hz", "0"])[0])
        self.assertEqual(2, self._check(["--hz", "-5"])[0])

    def test_an_infinite_rate_is_rejected(self):
        # "inf" parses as a float; the period becomes 0 and the loop floods
        # the vehicle network flat out.
        self.assertEqual(2, self._check(["--hz", "inf"])[0])

    def test_an_out_of_range_port_is_rejected(self):
        self.assertEqual(2, self._check(["--port", "70000"])[0])

    def test_nan_on_the_legacy_flags_is_rejected(self):
        # The legacy single-camera path bypasses parse_camera_spec, so it must
        # go through the same validation gate.
        self.assertEqual(2, self._check(["--hfov", "nan"])[0])
        self.assertEqual(2, self._check(["--far-h", "nan"])[0])

    def test_a_nan_dmx_calibration_is_rejected_when_dmx_is_on(self):
        # A NaN pan-center aims the head at NaN, which parks it at 0 forever
        # while every status line looks configured.
        self.assertEqual(2, self._check(["--dmx", "fake", "--dmx-pan-center", "nan"])[0])
        self.assertEqual(0, self._check(["--dmx", "fake", "--dmx-pan-center", "270"])[0])

    def test_the_live_path_gets_the_same_loud_error_not_a_traceback(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            rc = main(["--once", "--iface-ip", "not-an-ip"])
        self.assertEqual(2, rc)
        self.assertIn("iface-ip", err.getvalue())


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
