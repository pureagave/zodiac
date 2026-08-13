"""The crash fail-safe. This is the last code that runs before a hot universe
would be left on the wire, so its failure modes matter more than its happy path."""

import os
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


class FromArgsEnvTest(unittest.TestCase):
    """The crash fail-safe deriving its target from the service's own args
    string, so a UNIVERSE=1 deploy doesn't get zeroed on universe 0 while
    universe 1 stays hot. Every test clears the env var it sets."""

    ENV_VAR = "TEST_ZVISION_ARGS"

    def tearDown(self):
        os.environ.pop(self.ENV_VAR, None)

    def test_it_parks_the_universe_declared_in_the_args_string(self):
        os.environ[self.ENV_VAR] = (
            "--source thermal --dmx ola --dmx-universe 1 "
            "--dmx-url http://10.0.0.9:9090"
        )
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            rc = main(["--quiet", "--from-args-env", self.ENV_VAR, "--default-dmx", "none"])
        self.assertEqual(0, rc)
        self.assertEqual(1, len(cap.calls))
        url, body, _ = cap.calls[0]
        self.assertEqual("http://10.0.0.9:9090/set_dmx", url)
        self.assertEqual("1", _fields(body)["u"])

    def test_an_unset_var_falls_back_to_universe_zero_localhost(self):
        # No-regression guard: today's exact default behaviour.
        os.environ.pop(self.ENV_VAR, None)
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            main(["--quiet", "--from-args-env", self.ENV_VAR, "--default-dmx", "ola"])
        url, body, _ = cap.calls[0]
        self.assertEqual("http://127.0.0.1:9090/set_dmx", url)
        self.assertEqual("0", _fields(body)["u"])

    def test_an_empty_var_falls_back_to_universe_zero_localhost(self):
        os.environ[self.ENV_VAR] = ""
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            main(["--quiet", "--from-args-env", self.ENV_VAR, "--default-dmx", "ola"])
        url, body, _ = cap.calls[0]
        self.assertEqual("http://127.0.0.1:9090/set_dmx", url)
        self.assertEqual("0", _fields(body)["u"])

    def test_an_unparseable_var_falls_back_to_universe_zero_localhost(self):
        os.environ[self.ENV_VAR] = "--dmx-universe notanint"
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            main(["--quiet", "--from-args-env", self.ENV_VAR, "--default-dmx", "ola"])
        url, body, _ = cap.calls[0]
        self.assertEqual("http://127.0.0.1:9090/set_dmx", url)
        self.assertEqual("0", _fields(body)["u"])

    def test_a_non_ola_service_skips_the_park_entirely(self):
        # zvision --dmx none does not own the deck's universe -- its
        # ExecStopPost must not touch it (see DECK.md single-writer split).
        os.environ[self.ENV_VAR] = "--source thermal --hz 10"
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            rc = main(["--quiet", "--from-args-env", self.ENV_VAR, "--default-dmx", "none"])
        self.assertEqual(0, rc)
        self.assertEqual(0, len(cap.calls), "a non-owning service must not park")

    def test_an_ola_service_still_parks_when_dmx_is_explicit(self):
        # Ties the skip to being conditional, not blanket: the same env var
        # shape WITH --dmx ola present must still post.
        os.environ[self.ENV_VAR] = "--source thermal --dmx ola"
        cap = _Captured()
        with mock.patch("urllib.request.urlopen", cap):
            rc = main(["--quiet", "--from-args-env", self.ENV_VAR, "--default-dmx", "none"])
        self.assertEqual(0, rc)
        self.assertEqual(1, len(cap.calls))


if __name__ == "__main__":
    unittest.main()
