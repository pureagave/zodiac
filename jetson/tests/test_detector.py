import unittest

from zvision.detector import PRUNE_GRACE_FRAMES, DetectorTuning, FakeDetector, MotionDetector
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


class _Contour:
    """Stands in for an OpenCV contour: only area and bounding rect matter to
    ``MotionDetector.detect``."""

    def __init__(self, area: float, rect: tuple):
        self.area = area
        self.rect = rect


class _ScriptedCv2(_RecordingCv2):
    """Extends ``_RecordingCv2`` with a queue of contour lists, one per
    ``detect()`` call, so a test can script a whole timeline (blob present /
    absent, growing bbox, ...) without a real camera or OpenCV."""

    def __init__(self, contour_script):
        super().__init__()
        self._script = list(contour_script)

    def findContours(self, *_a, **_k):
        self.found_contours = True
        contours = self._script.pop(0) if self._script else []
        return contours, None

    def contourArea(self, c: _Contour) -> float:
        return c.area

    def boundingRect(self, c: _Contour) -> tuple:
        return c.rect


def _scripted_detector(contour_script, tuning=None):
    """A MotionDetector wired to a scripted contour timeline instead of a real
    camera, with a background mask that always reads as an ordinary
    (non-re-baselining) scene."""
    d = MotionDetector.__new__(MotionDetector)
    cv2 = _ScriptedCv2(contour_script)
    d._cv2 = cv2
    d._camera = _Cam()
    d._bg = _Bg(_Mask(0.03))
    d._rebaseline = ReBaselineGuard()
    tuning = tuning or DetectorTuning()
    d._min_area_frac = tuning.min_area_frac
    d._match_dist = tuning.match_dist
    d._tuning = tuning
    d._tracks = {}
    d._misses = {}
    d._prune_grace_frames = PRUNE_GRACE_FRAMES
    d._next_id = 1
    d._collision = CollisionEstimator(
        az_rate_thresh_dps=tuning.collision_az_rate_dps,
        min_size=tuning.collision_min_size,
    )
    d._recorder = None
    d._name = "test"
    d._fov = 160.0
    d._lens = LENS_EQUIDISTANT
    d._fov_ref = FOV_HORIZONTAL
    return d, cv2


def _centred_blob(bh: int, bw: int = 20) -> _Contour:
    """A blob dead-centre horizontally (frame width 160) at the top of a
    120-row frame, area comfortably clear of the min-area gate. Centred at
    cx_norm == 0.5 exactly, where pixel_to_bearing's u term is zero and the
    reported azimuth is exactly 0.0 regardless of vertical offset — an
    unambiguous constant-bearing contact for these tests."""
    x = 80 - bw // 2
    return _Contour(area=bw * bh, rect=(x, 0, bw, bh))


class RangeProxyWiringTest(unittest.TestCase):
    """P1 finding 1: detect() must pass the raw, unclamped bbox-height ratio
    as the collision estimator's range_proxy, not the clamped display/aim
    ``size`` — otherwise the flag goes dark exactly when a contact gets close
    enough to saturate ``size`` at near_h."""

    def test_collision_stays_true_through_the_saturated_frames(self):
        # bh ramps up to and past near_h (bh=108, h_norm=0.9) quickly, then
        # holds inside the saturated region for longer than window_s (0.5s):
        # size clamps to 1.0 from t=0.2 on, but the raw proxy (h_norm) keeps
        # climbing the whole time. By t=0.7+ the collision estimator's own
        # windowed reference sample is *also* inside the saturated region, so
        # a comparison on size alone would see zero delta there -- range_proxy
        # is the only thing keeping the flag alive that late in the approach.
        bh_by_frame = [60, 90, 108, 110, 112, 114, 116, 118, 119, 120]
        script = [[_centred_blob(bh)] for bh in bh_by_frame]
        d, _cv2 = _scripted_detector(script)

        results = []
        for i, bh in enumerate(bh_by_frame):
            t = round(i * 0.1, 1)
            contacts = d.detect(t)
            self.assertEqual(1, len(contacts))
            results.append((t, bh / 120.0, contacts[0].size, contacts[0].collision))

        # Sanity: size really did saturate at 1.0 well before the run ends,
        # and stayed there.
        saturated = [r for r in results if r[1] >= 0.9]
        self.assertGreaterEqual(len(saturated), 6, "need >window_s worth of saturated frames")
        self.assertTrue(all(r[2] == 1.0 for r in saturated), "size must clamp at 1.0")

        # The proof: collision stays True through the back half of the run,
        # by which point both the current frame and the window's own
        # reference sample are inside the saturated region -- size alone
        # would show a flat 1.0 there and the flag would go dark.
        late = [r for r in results if r[0] >= 0.7]
        self.assertTrue(late)
        self.assertTrue(
            all(r[3] for r in late),
            "collision must not go dark once size saturates near the closest range",
        )


class PruneGraceTest(unittest.TestCase):
    """P1 finding 2: a track unseen for a frame or two must keep its id and
    its collision baseline (Jetson-internal only -- it is never emitted while
    unseen), so intermittent detection does not both re-mint the id and wipe
    the estimator's history."""

    def test_id_and_collision_baseline_survive_alternating_dropout(self):
        # ON/OFF/ON/OFF... at 10 Hz: a genuine contact detected every other
        # frame, closing steadily on a constant bearing.
        bh_on = [30, 45, 60, 75, 90]
        script = []
        for bh in bh_on:
            script.append([_centred_blob(bh)])  # ON
            script.append([])  # OFF (missed blob)
        d, _cv2 = _scripted_detector(script)

        seen_ids = []
        collisions = []
        for i, bh in enumerate(bh_on):
            on_t = (2 * i) * 0.1
            off_t = (2 * i + 1) * 0.1
            contacts = d.detect(on_t)
            self.assertEqual(1, len(contacts), f"ON frame at t={on_t} must report the contact")
            seen_ids.append(contacts[0].id)
            collisions.append((on_t, contacts[0].collision))
            self.assertEqual([], d.detect(off_t), "OFF frame reports nothing")

        self.assertEqual(1, len(set(seen_ids)), "id must not churn across a 1-frame dropout")
        # Window warm-up: the flag can only become true once a >=0.5s-old
        # sample exists. With ON frames every 0.2s that's the 4th one (t=0.6).
        flagged_from_t06 = [c for t, c in collisions if t >= 0.6]
        self.assertTrue(flagged_from_t06)
        self.assertTrue(
            all(flagged_from_t06),
            "closing contact must flag once warmed up, despite the alternating dropout",
        )

    def test_a_gap_longer_than_the_grace_does_drop_the_track(self):
        # Establish a track, then miss it for PRUNE_GRACE_FRAMES + 1 frames in
        # a row -- one frame past what the grace tolerates.
        script = [[_centred_blob(40)], [_centred_blob(45)]]
        script += [[] for _ in range(PRUNE_GRACE_FRAMES + 1)]
        script.append([_centred_blob(50)])
        d, _cv2 = _scripted_detector(script)

        first = d.detect(0.0)[0].id
        second = d.detect(0.1)[0].id
        self.assertEqual(first, second, "must not have churned yet")

        t = 0.2
        for _ in range(PRUNE_GRACE_FRAMES + 1):
            self.assertEqual([], d.detect(t))
            t += 0.1

        reappeared = d.detect(t)
        self.assertEqual(1, len(reappeared))
        self.assertNotEqual(
            first,
            reappeared[0].id,
            "a gap longer than the grace must mint a fresh id, not carry forever",
        )
        self.assertNotIn(
            first,
            d._collision._tracks,
            "the collision baseline for the dropped track must have been forgotten",
        )
