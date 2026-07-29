import unittest

from zvision.dmx import FakeDmxSink, build_sink


class FakeDmxSinkTest(unittest.TestCase):
    def test_send_writes_one_based_channels(self):
        sink = FakeDmxSink()
        sink.send({1: 128, 5: 255})
        self.assertEqual(128, sink.frame[0])  # channel 1 -> index 0
        self.assertEqual(255, sink.frame[4])  # channel 5 -> index 4
        self.assertEqual(1, sink.sends)

    def test_untouched_channels_hold_their_last_value(self):
        sink = FakeDmxSink()
        sink.send({1: 100})
        sink.send({3: 50})  # doesn't mention ch 1
        self.assertEqual(100, sink.frame[0])  # still holds
        self.assertEqual(50, sink.frame[2])

    def test_values_clamp_to_byte_range(self):
        sink = FakeDmxSink()
        sink.send({1: 999, 2: -5})
        self.assertEqual(255, sink.frame[0])
        self.assertEqual(0, sink.frame[1])

    def test_out_of_range_channels_are_skipped(self):
        sink = FakeDmxSink()
        sink.send({0: 200, 513: 200, 1: 42})  # 0 and 513 are invalid
        self.assertEqual(42, sink.frame[0])
        self.assertEqual(512, len(sink.frame))  # frame stays exactly one universe


class BuildSinkTest(unittest.TestCase):
    def test_fake_sink(self):
        self.assertIsInstance(build_sink("fake"), FakeDmxSink)

    def test_unknown_kind_raises(self):
        with self.assertRaises(ValueError):
            build_sink("laser")


if __name__ == "__main__":
    unittest.main()
