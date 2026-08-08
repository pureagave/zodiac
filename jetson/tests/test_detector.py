import unittest

from zvision.detector import DetectorTuning, FakeDetector, MotionDetector
from zvision.geometry import FOV_HORIZONTAL, LENS_EQUIDISTANT, CollisionEstimator
from zvision.normalize import ReBaselineGuard


class FakeDetectorTest(unittest.TestCase):
    def test_emits_three_contacts(self):
        self.assertEqual(3, len(FakeDetector().detect(0.0)))

    def test_is_deterministic_for_a_given_time(self):
        d = FakeDetector()
        self.assertEqual(d.detect(1.234), d.detect(1.234))

    def test_incoming_contact_trips_collision_once_near(self):
        d = FakeDetector()
        # Contact id=3 ramps in size; find a time where it's past the threshold.
        near = next(c for c in d.detect(8.0) if c.id == 3)
        self.assertTrue(near.size >= FakeDetector.COLLISION_SIZE)
        self.assertTrue(near.collision)

    def test_far_sweeper_stays_within_field(self):
        d = FakeDetector()
        for tenth in range(0, 200):
            sweeper = next(c for c in d.detect(tenth / 10.0) if c.id == 1)
            self.assertLessEqual(abs(sweeper.rel_az_deg), 40.0)


if __name__ == "__main__":
    unittest.main()


class _Mask:
    """Stands in for the foreground mask; only its coverage matters here."""

    def __init__(self, fg_fraction):
        self._fg = fg_fraction

    def mean(self):
        return self._fg * 255.0


class _Frame:
    shape = (120, 160)


class _RecordingCv2:
    """Enough of cv2 for detect() to run, recording whether contour extraction
    was reached — which is the thing under test."""

    RETR_EXTERNAL = 0
    CHAIN_APPROX_SIMPLE = 1
    THRESH_BINARY = 2
    MORPH_OPEN = 3
    MORPH_ELLIPSE = 4

    def __init__(self):
        self.found_contours = False

    def threshold(self, mask, *_a, **_k):
        return None, mask

    def morphologyEx(self, mask, *_a, **_k):
        return mask

    def getStructuringElement(self, *_a, **_k):
        return None

    def findContours(self, *_a, **_k):
        self.found_contours = True
        return [], None


class _Bg:
    def __init__(self, mask):
        self._mask = mask

    def apply(self, _frame):
        return self._mask


class _Cam:
    def read(self):
        return _Frame()


class ReBaselineWiringTest(unittest.TestCase):
    """The guard itself is covered in test_normalize; this covers that the
    detector actually *consults* it. Without this, the guard could be perfect
    and simply never called — which is exactly what a mutation showed before
    this test existed."""

    def _detector(self, fg_fraction):
        d = MotionDetector.__new__(MotionDetector)
        cv2 = _RecordingCv2()
        d._cv2 = cv2
        d._camera = _Cam()
        d._bg = _Bg(_Mask(fg_fraction))
        d._rebaseline = ReBaselineGuard()
        d._min_area_frac = 0.004
        d._match_dist = 0.15
        d._tuning = DetectorTuning()
        d._tracks = {}
        d._next_id = 1
        d._collision = CollisionEstimator()
        d._recorder = None
        d._name = "test"
        d._fov = 160.0
        d._lens = LENS_EQUIDISTANT
        d._fov_ref = FOV_HORIZONTAL
        return d, cv2

    def test_a_whole_frame_step_never_reaches_contour_extraction(self):
        # An FFC moves 100% of pixels. Reporting that as contacts would put a
        # crowd of phantom people on the driver's HUD.
        d, cv2 = self._detector(fg_fraction=1.0)
        self.assertEqual([], d.detect(0.0))
        self.assertFalse(cv2.found_contours, "the guard must short-circuit before contours")

    def test_an_ordinary_frame_is_detected_normally(self):
        d, cv2 = self._detector(fg_fraction=0.03)
        d.detect(0.0)
        self.assertTrue(cv2.found_contours, "a normal frame must not be suppressed")

    def test_the_frames_after_a_step_are_suppressed_too(self):
        d, cv2 = self._detector(fg_fraction=1.0)
        d.detect(0.0)
        d._bg = _Bg(_Mask(0.02))
        cv2.found_contours = False
        self.assertEqual([], d.detect(0.1), "still settling")
        self.assertFalse(cv2.found_contours)
