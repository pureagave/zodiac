import socket
import time
import threading
import unittest

from zvision.tracker import Tracker, TrackerConfig
from zvision.audio_bus import AudioLevel, ZaudListener, parse_zaud


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


class StaleAudioTest(unittest.TestCase):
    """The consumer is a light. A latched stale frame doesn't just show old
    data — it pins the idle head at whatever brightness the last frame implied,
    all night, while looking like a working sound show."""

    def _listener(self, clock):
        lis = ZaudListener.__new__(ZaudListener)
        lis._latest = None
        lis._latest_at = 0.0
        lis._lock = threading.Lock()
        lis._now = clock
        return lis

    def test_a_fresh_level_is_returned(self):
        t = [100.0]
        lis = self._listener(lambda: t[0])
        with lis._lock:
            lis._latest = AudioLevel(rms=0.4, peak=0.6, beat=False)
            lis._latest_at = t[0]
        self.assertIsNotNone(lis.latest(max_age_s=2.0))

    def test_a_stale_level_is_withheld(self):
        t = [100.0]
        lis = self._listener(lambda: t[0])
        with lis._lock:
            lis._latest = AudioLevel(rms=0.9, peak=1.0, beat=True)
            lis._latest_at = t[0]
        t[0] = 105.0  # feed died five seconds ago
        self.assertIsNone(lis.latest(max_age_s=2.0), "a dead feed must not keep driving the light")

    def test_a_latched_beat_cannot_pin_the_head_on_forever(self):
        # The specific failure: last frame carried beat=1, so _sound_dimmer
        # would return beat_dimmer (full brightness) for the rest of the night.
        t = [0.0]
        lis = self._listener(lambda: t[0])
        with lis._lock:
            lis._latest = AudioLevel(rms=0.1, peak=0.2, beat=True)
            lis._latest_at = 0.0
        t[0] = 3600.0
        self.assertIsNone(lis.latest(max_age_s=2.0))

    def test_age_check_can_be_disabled(self):
        t = [0.0]
        lis = self._listener(lambda: t[0])
        with lis._lock:
            lis._latest = AudioLevel(rms=0.4, peak=0.6, beat=False)
            lis._latest_at = 0.0
        t[0] = 9999.0
        self.assertIsNotNone(lis.latest(max_age_s=0.0))

    def test_nothing_received_yet_is_still_none(self):
        self.assertIsNone(self._listener(lambda: 0.0).latest())


class IdleLightFallbackTest(unittest.TestCase):
    """What the tracker does with the withheld level: fall back to the idle
    dimmer, which reads honestly as 'no music'."""

    def test_no_audio_means_the_head_blacks_out_when_idle(self):
        cfg = TrackerConfig(dimmer_idle=0)
        t = Tracker(cfg)
        self.assertEqual(0, t.update([], 0.1, None).dimmer)

    def test_a_live_beat_still_flashes(self):
        cfg = TrackerConfig(beat_dimmer=255)
        t = Tracker(cfg)
        got = t.update([], 0.1, AudioLevel(rms=0.2, peak=0.4, beat=True)).dimmer
        self.assertEqual(255, got)


if __name__ == "__main__":
    unittest.main()
