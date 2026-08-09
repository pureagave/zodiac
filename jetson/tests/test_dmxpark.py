"""The crash fail-safe. This is the last code that runs before a hot universe
would be left on the wire, so its failure modes matter more than its happy path."""

import unittest
from unittest import mock

from zvision.dmxpark import DMX_UNIVERSE_SIZE, main, park


class _Captured:
    """Stand-in for urlopen that records the request and can fail on demand."""

    def __init__(self, fail_times=0, exc=OSError("connection refused")):
        self.calls = []
        self.fail_times = fail_times
        self.exc = exc

    def __call__(self, url, data=None, timeout=None):
        self.calls.append((url, data, timeout))
        if len(self.calls) <= self.fail_times:
            raise self.exc
        return mock.MagicMock(__enter__=mock.MagicMock(), __exit__=mock.MagicMock())


def _fields(body):
    import urllib.parse

    return dict(urllib.parse.parse_qsl(body.decode("ascii")))


class ParkPayloadTest(unittest.TestCase):
    def test_it_zeroes_every_one_of_the_512_slots(self):
        # Not just the dimmer: the dimmer is ch8 in 11-channel mode and ch6 in
        # 9-channel, and a dimmer-only blackout would leave the auto-program
        # channels set. Zeroing everything is correct in either personality.
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            self.assertTrue(park())
        values = _fields(cap.calls[0][1])["d"].split(",")
        self.assertEqual(DMX_UNIVERSE_SIZE, len(values))
        self.assertTrue(all(v == "0" for v in values))

    def test_it_posts_to_the_requested_universe_and_url(self):
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            park(universe=3, base_url="http://10.0.0.9:9090/")
        url, body, _ = cap.calls[0]
        self.assertEqual("http://10.0.0.9:9090/set_dmx", url)
        self.assertEqual("3", _fields(body)["u"])


class ParkResilienceTest(unittest.TestCase):
    def test_it_retries_before_giving_up(self):
        # It runs while the machine is tearing a crashed process down; one
        # refused connection is not evidence that olad is gone.
        cap = _Captured(fail_times=2)
        with mock.patch("urllib.request.urlopen", cap):
            self.assertTrue(park(retries=3, pause=0.0))
        self.assertEqual(3, len(cap.calls))

    def test_it_reports_failure_rather_than_raising(self):
        # A fail-safe that throws on the way out is not one -- an exception here
        # would propagate into systemd's ExecStopPost and tell nobody anything.
        cap = _Captured(fail_times=99)
        with mock.patch("urllib.request.urlopen", cap), \
                mock.patch("sys.stderr"):
            self.assertFalse(park(retries=2, pause=0.0))
        self.assertEqual(2, len(cap.calls))

    def test_a_single_attempt_is_honoured_even_if_retries_is_nonsense(self):
        cap = _Captured(fail_times=99)
        with mock.patch("urllib.request.urlopen", cap), mock.patch("sys.stderr"):
            park(retries=0, pause=0.0)
        self.assertEqual(1, len(cap.calls))


class ParkCliTest(unittest.TestCase):
    def test_exit_code_is_zero_on_success_and_one_on_failure(self):
        # The unit file prefixes the command with '-' so systemd ignores this,
        # but an operator running it by hand as a kill needs the truth.
        with mock.patch("urllib.request.urlopen", _Captured()):
            self.assertEqual(0, main(["--quiet"]))
        with mock.patch("urllib.request.urlopen", _Captured(fail_times=99)), \
                mock.patch("sys.stderr"):
            self.assertEqual(1, main(["--quiet", "--retries", "1"]))


if __name__ == "__main__":
    unittest.main()
