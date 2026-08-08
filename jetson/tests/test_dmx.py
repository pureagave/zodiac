import contextlib
import http.server
import io
import socketserver
import threading
import unittest
import urllib.parse

from zvision.dmx import DMX_UNIVERSE_SIZE, FakeDmxSink, OlaDmxSink, build_sink


class _QuickBindHTTPServer(http.server.HTTPServer):
    """HTTPServer.server_bind does a reverse-DNS getfqdn() of the bind address,
    which can stall for many seconds on a machine with odd DNS — a loopback
    test server doesn't need a name at all."""

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name = "localhost"
        self.server_port = self.socket.getsockname()[1]


class _CapturingOlad:
    """A real loopback HTTP server standing in for olad, so the tests pin the
    actual bytes on the wire — the same way the broadcaster tests use a real
    UDP socket. olad's API is fixed and remote; if our request drifts from it
    (endpoint, parameter names, universe payload), olad ignores us and the
    head just never moves, with nothing anywhere saying why."""

    def __init__(self):
        captured = self.captured = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - http.server API
                length = int(self.headers.get("Content-Length", 0))
                captured.append((self.path, self.rfile.read(length).decode("ascii")))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"ok")

            def log_message(self, *args):
                pass

        self.server = _QuickBindHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1.0)


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

    def test_float_values_truncate_toward_zero(self):
        # The tracker's slewed dimmer/pan can be fractional; _merge int()-truncates.
        sink = FakeDmxSink()
        sink.send({1: 127.9})
        self.assertEqual(127, sink.frame[0])  # truncated, not rounded to 128


class OlaWireContractTest(unittest.TestCase):
    """olad's HTTP API is the other side of a contract we can't change — like
    the ZTHREAT frame, but for the light. These pin our half of it."""

    def setUp(self):
        self.olad = _CapturingOlad()
        self.addCleanup(self.olad.close)

    def test_the_universe_is_posted_to_set_dmx_with_olads_parameter_names(self):
        sink = OlaDmxSink(universe=3, base_url=self.olad.url)
        sink.send({1: 128, 5: 255})
        self.assertEqual(1, len(self.olad.captured))
        path, body = self.olad.captured[0]
        self.assertEqual("/set_dmx", path)
        form = urllib.parse.parse_qs(body)
        self.assertEqual(["3"], form["u"])
        self.assertEqual(1, sink.sends)
        self.assertEqual(0, sink.errors)

    def test_the_full_512_channel_frame_rides_every_send(self):
        # olad replaces the whole universe per request; sending only the
        # changed channels would zero every channel the tracker didn't touch.
        sink = OlaDmxSink(universe=0, base_url=self.olad.url)
        sink.send({1: 128})
        _, body = self.olad.captured[0]
        values = urllib.parse.parse_qs(body)["d"][0].split(",")
        self.assertEqual(DMX_UNIVERSE_SIZE, len(values))
        self.assertEqual("128", values[0])
        self.assertTrue(all(v == "0" for v in values[1:]))

    def test_channels_hold_their_value_across_sends(self):
        sink = OlaDmxSink(universe=0, base_url=self.olad.url)
        sink.send({1: 100})
        sink.send({3: 50})  # doesn't mention channel 1
        _, body = self.olad.captured[1]
        values = urllib.parse.parse_qs(body)["d"][0].split(",")
        self.assertEqual("100", values[0])
        self.assertEqual("50", values[2])


class OlaDmxSinkTest(unittest.TestCase):
    def test_a_dead_olad_is_reported_once_not_silently_swallowed(self):
        # The OLA layer has already failed this project once with zero
        # symptoms (plugin config written where olad never reads it). A dead
        # olad must leave at least one line in the journal — but only one per
        # outage, not one per frame at 10 Hz all night.
        sink = OlaDmxSink(base_url="http://127.0.0.1:0")
        with contextlib.redirect_stderr(io.StringIO()) as err:
            sink.send({1: 255})
            sink.send({1: 254})
        self.assertEqual(2, sink.errors)
        self.assertEqual(1, err.getvalue().count("DMX"))

    def test_a_recovered_then_dead_olad_is_news_again(self):
        olad = _CapturingOlad()
        self.addCleanup(olad.close)
        sink = OlaDmxSink(base_url="http://127.0.0.1:0")
        with contextlib.redirect_stderr(io.StringIO()) as err:
            sink.send({1: 1})           # outage: reported
            sink.base_url = olad.url
            sink.send({1: 2})           # recovery: quiet
            sink.base_url = "http://127.0.0.1:0"
            sink.send({1: 3})           # second outage: reported again
        self.assertEqual(2, err.getvalue().count("DMX"))

    def test_unreachable_olad_is_swallowed_and_counted(self):
        # Port 0 is never a listening olad, so urlopen raises — the sink must
        # swallow it (a lighting glitch can't take down the HUD broadcaster),
        # count the error, leave sends at 0, and stash the message.
        sink = OlaDmxSink(base_url="http://127.0.0.1:0")
        with contextlib.redirect_stderr(io.StringIO()):
            sink.send({1: 255})  # must not raise
        self.assertEqual(1, sink.errors)
        self.assertEqual(0, sink.sends)
        self.assertIsNotNone(sink._last_error)
        # The frame is still merged locally even though the transmit failed.
        self.assertEqual(255, sink.frame[0])


class BuildSinkTest(unittest.TestCase):
    def test_fake_sink(self):
        self.assertIsInstance(build_sink("fake"), FakeDmxSink)

    def test_unknown_kind_raises(self):
        with self.assertRaises(ValueError):
            build_sink("laser")


if __name__ == "__main__":
    unittest.main()
