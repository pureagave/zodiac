import socket
import time
import unittest

from zvision.audio_bus import ZaudListener, parse_zaud


def zaud(rms: float, peak: float, beat: bool) -> str:
    body = f"ZAUD,{rms:.3f},{peak:.3f},{1 if beat else 0}"
    cs = 0
    for ch in body:
        cs ^= ord(ch)
    return f"${body}*{cs:02X}"


class ParseZaudTest(unittest.TestCase):
    def test_parses_a_valid_frame(self):
        a = parse_zaud(zaud(0.125, 0.8, True))
        self.assertIsNotNone(a)
        self.assertAlmostEqual(0.125, a.rms, places=3)
        self.assertAlmostEqual(0.8, a.peak, places=3)
        self.assertTrue(a.beat)

    def test_beat_zero_is_false(self):
        self.assertFalse(parse_zaud(zaud(0.1, 0.2, False)).beat)

    def test_rejects_bad_checksum(self):
        self.assertIsNone(parse_zaud("$ZAUD,0.1,0.2,0*00"))

    def test_rejects_other_sentence_types(self):
        # A valid HDT (correct checksum) is not a ZAUD frame.
        body = "GPHDT,90.0,T"
        cs = 0
        for ch in body:
            cs ^= ord(ch)
        self.assertIsNone(parse_zaud(f"${body}*{cs:02X}"))

    def test_rejects_garbage_and_non_finite(self):
        self.assertIsNone(parse_zaud("not a sentence"))
        self.assertIsNone(parse_zaud(zaud(0.1, 0.2, True).replace("0.100", "abc")))

    def test_rejects_negative_levels(self):
        # Hand-build a negative-rms frame with a valid checksum.
        body = "ZAUD,-0.100,0.200,0"
        cs = 0
        for ch in body:
            cs ^= ord(ch)
        self.assertIsNone(parse_zaud(f"${body}*{cs:02X}"))


class ZaudListenerTest(unittest.TestCase):
    def test_receives_a_frame_over_loopback(self):
        port = 10191
        listener = ZaudListener(port=port)
        listener.start()
        try:
            sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            got = None
            for _ in range(50):
                sender.sendto(zaud(0.5, 0.8, True).encode("ascii"), ("127.0.0.1", port))
                time.sleep(0.02)
                got = listener.latest()
                if got is not None:
                    break
            sender.close()
            self.assertIsNotNone(got, "listener should surface a $ZAUD frame")
            self.assertTrue(got.beat)
            self.assertAlmostEqual(0.5, got.rms, places=3)
        finally:
            listener.close()


if __name__ == "__main__":
    unittest.main()
