import unittest

from zvision.detector import FakeDetector, build_detector


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


class BuildDetectorTest(unittest.TestCase):
    def test_fake_source_needs_no_hardware(self):
        det = build_detector("fake", hfov_deg=57.0, device="/dev/video0", width=160, height=120)
        self.assertIsInstance(det, FakeDetector)
        det.close()


if __name__ == "__main__":
    unittest.main()
