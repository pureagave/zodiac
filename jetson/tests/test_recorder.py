"""Frame recorder: subsampling, the weak-label index, the byte budget, and the
rule that overrides all of it — recording must never break detection."""

import json
import os
import shutil
import tempfile
import unittest

from zvision.recorder import FrameRecorder, RecorderConfig, summarize


class _FakeCv2:
    """Stands in for OpenCV so the recorder is testable without the real thing.
    Encodes to a deterministic byte blob whose length tracks the frame size."""

    IMWRITE_JPEG_QUALITY = 1

    def __init__(self, fail=False):
        self.fail = fail
        self.calls = []

    def imencode(self, ext, frame, params=None):
        self.calls.append(ext)
        if self.fail:
            return False, None
        return True, _Buf(b"x" * (frame.size))


class _Buf:
    def __init__(self, data):
        self._d = data

    def tobytes(self):
        return self._d


class _Frame:
    """Minimal ndarray stand-in: .shape and .size are all the recorder reads."""

    def __init__(self, w, h):
        self.shape = (h, w, 3)
        self.size = w * h


class _RecorderCase(unittest.TestCase):
    """Temp dir per test, and every recorder closed so the suite stays free of
    ResourceWarnings — a noisy suite is one people stop reading."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self._open = []

    def tearDown(self):
        for r in self._open:
            r.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _rec(self, cv2=None, tmp=None, **kw):
        r = FrameRecorder(RecorderConfig(directory=tmp or self.tmp, **kw))
        r._cv2 = cv2 or _FakeCv2()  # pre-inject so no real OpenCV import happens
        self._open.append(r)
        return r


class RecordingTest(_RecorderCase):

    def test_writes_a_frame_and_an_index_line(self):
        r = self._rec()
        self.assertTrue(r.record("thermal", 0.0, _Frame(64, 64), [(1, 2, 3, 4)]))
        r.close()
        with open(os.path.join(self.tmp, "index.jsonl"), encoding="utf-8") as fh:
            row = json.loads(fh.readline())
        self.assertEqual("thermal", row["camera"])
        self.assertEqual([[1, 2, 3, 4]], row["boxes"])
        self.assertTrue(os.path.exists(os.path.join(self.tmp, row["file"])))

    def test_subsamples_to_the_configured_rate(self):
        r = self._rec(hz=1.0)
        self.assertTrue(r.record("c", 0.0, _Frame(64, 64), []))
        self.assertFalse(r.record("c", 0.4, _Frame(64, 64), []))  # too soon
        self.assertFalse(r.record("c", 0.9, _Frame(64, 64), []))
        self.assertTrue(r.record("c", 1.0, _Frame(64, 64), []))   # due again
        self.assertEqual(2, r.frames_written)

    def test_each_camera_keeps_its_own_schedule(self):
        # A rig polls cameras in turn; one must not starve another.
        r = self._rec(hz=1.0)
        self.assertTrue(r.record("a", 0.0, _Frame(64, 64), []))
        self.assertTrue(r.record("b", 0.0, _Frame(64, 64), []))
        self.assertEqual(2, r.frames_written)

    def test_hz_zero_records_nothing(self):
        r = self._rec(hz=0.0)
        self.assertFalse(r.record("c", 0.0, _Frame(64, 64), []))
        self.assertEqual(0, r.frames_written)

    def test_thermal_sized_frames_go_to_png_and_rgb_to_jpeg(self):
        cv2 = _FakeCv2()
        r = self._rec(cv2=cv2, hz=1000.0)
        r.record("thermal", 0.0, _Frame(120, 120), [])   # small -> lossless
        r.record("rgb", 0.0, _Frame(1920, 1080), [])     # big -> lossy
        self.assertEqual([".png", ".jpg"], cv2.calls)

    def test_byte_budget_stops_recording(self):
        r = self._rec(hz=1000.0, max_mb=1)
        # Each 1000x1000 frame encodes to ~1 MB in the fake, so the second
        # attempt is over budget.
        for i in range(6):
            r.record("c", float(i), _Frame(1000, 1000), [])
        self.assertLess(r.frames_written, 6)
        self.assertGreater(r.frames_written, 0)

    def test_budget_is_shared_across_cameras(self):
        r = self._rec(hz=1000.0, max_mb=1)
        for i in range(6):
            r.record(f"cam{i}", float(i), _Frame(1000, 1000), [])
        self.assertLess(r.frames_written, 6)


class NeverBreaksDetectionTest(_RecorderCase):
    """The HUD and the tracker light are live outputs. A recording failure has
    to degrade to 'we stopped recording', never to an exception reaching the
    detect loop."""

    def test_encode_failure_is_swallowed(self):
        r = self._rec(cv2=_FakeCv2(fail=True))
        self.assertFalse(r.record("c", 0.0, _Frame(64, 64), []))  # no raise

    def test_write_failure_is_swallowed_and_disables_recording(self):
        r = self._rec()
        r.record("c", 0.0, _Frame(64, 64), [])
        shutil.rmtree(self.tmp)          # yank the disk out from under it
        os.makedirs(self.tmp)
        r.cfg = RecorderConfig(directory="/proc/nonexistent-zvision", hz=1000.0)
        self.assertFalse(r.record("c", 5.0, _Frame(64, 64), []))  # no raise

    def test_unwritable_directory_disables_cleanly_at_construction(self):
        r = FrameRecorder(RecorderConfig(directory="/proc/nope/zvision"))
        self.assertFalse(r.record("c", 0.0, _Frame(64, 64), []))
        self.assertEqual(0, r.frames_written)
        r.close()

    def test_close_is_idempotent(self):
        r = self._rec()
        r.close()
        r.close()  # must not raise

    def test_summarize_handles_no_recorder(self):
        self.assertEqual("", summarize(None))


if __name__ == "__main__":
    unittest.main()
