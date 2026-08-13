import contextlib
import http.server
import io
import socketserver
import threading
import time
import unittest
import urllib.parse

from zvision.dmx import DMX_UNIVERSE_SIZE, FakeDmxSink, OlaDmxSink, ThreadedDmxSink, build_sink


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

    def test_channel_zero_does_not_alias_onto_the_last_channel(self):
        # A mutation review proved the test above blind: relax the guard to
        # `0 <= ch` and frame[ch-1] becomes frame[-1], so channel 0 silently
        # writes channel 512 -- and both assertions above still pass. On this
        # fixture the high channels are the auto-program and motor-reset slots,
        # so a stray write there is the difference between a spotlight and a
        # head that resets itself mid-track.
        sink = FakeDmxSink()
        sink.send({0: 200})
        self.assertEqual(0, sink.frame[511])
        self.assertTrue(all(b == 0 for b in sink.frame))

    def test_channel_512_is_inside_the_universe(self):
        # The other half of the boundary: tightening `ch <= 512` to `<` would
        # silently drop a legal channel, and nothing would have noticed.
        sink = FakeDmxSink()
        sink.send({512: 7})
        self.assertEqual(7, sink.frame[511])

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

    def test_the_outage_message_does_not_claim_the_light_is_dark(self):
        # In the ThreadedDmxSink era the last thing olad received before it
        # wedged can still be lit -- the head freezes in the fixture's frame
        # rather than going dark. The old wording asserted darkness; it wasn't
        # true and it isn't now.
        sink = OlaDmxSink(base_url="http://127.0.0.1:0")
        with contextlib.redirect_stderr(io.StringIO()) as err:
            sink.send({1: 255})
        text = err.getvalue()
        self.assertNotIn("light is dark", text)
        self.assertIn("last frame", text)
        self.assertIn("DMX", text)  # keeps the existing count-based assertions valid


class ThreadedDmxSinkTest(unittest.TestCase):
    """The load-bearing fix: decouple the hot detect->broadcast loop from a
    slow/wedged olad. Every assertion here is event-gated, not sleep-as-sync,
    except the deliberately-bounded slow-inner timing check below."""

    def test_send_does_not_block_on_a_slow_inner(self):
        # A synchronous fallback (send() calling inner.send() directly) would
        # make this loop take num_sends * block_for; the threaded version
        # should return almost immediately regardless of how slow inner is.
        block_for = 0.3
        calls = []

        class _SlowSink:
            def send(self, channels):
                calls.append(dict(channels))
                time.sleep(block_for)

            def close(self):
                pass

        sink = ThreadedDmxSink(_SlowSink())
        try:
            start = time.monotonic()
            for i in range(5):
                sink.send({1: i})
            elapsed = time.monotonic() - start
            self.assertLess(elapsed, block_for, "send() blocked on a slow inner")
        finally:
            sink.close()

    def test_bursts_coalesce_to_the_newest_frame(self):
        # Frame A is picked up by the worker and held there; B and C are
        # queued while it's busy, so they must collapse to one slot -- the
        # worker should see A, then C, and never B.
        started = threading.Event()
        release = threading.Event()
        received = []

        class _GatedSink:
            def __init__(self):
                self.calls = 0

            def send(self, channels):
                self.calls += 1
                received.append(dict(channels))
                if self.calls == 1:
                    started.set()
                    release.wait(timeout=2.0)

            def close(self):
                pass

        sink = ThreadedDmxSink(_GatedSink())
        sink.send({1: 1})  # A: worker takes this and blocks in _GatedSink.send
        self.assertTrue(started.wait(timeout=2.0), "worker never started on frame A")
        sink.send({1: 2})  # B: queued behind the blocked worker
        sink.send({1: 3})  # C: coalesces with B in the one-slot mailbox
        release.set()  # let the worker finish A, then drain the mailbox (-> C)
        sink.close()
        self.assertEqual([{1: 1}, {1: 3}], received, "B should have been coalesced away")
        self.assertGreaterEqual(sink.dropped, 1)

    def test_close_flushes_a_pending_frame_before_returning(self):
        # This is the exit-park path: app.py calls send(park_frame) then
        # close() back-to-back, and close() must not return until that frame
        # has actually reached inner.
        inner = FakeDmxSink()
        sink = ThreadedDmxSink(inner)
        sink.send({1: 200})
        sink.close()
        self.assertEqual(200, inner.frame[0])
        self.assertEqual(1, inner.sends)

    def test_close_also_closes_the_inner_sink(self):
        class _TrackingSink(FakeDmxSink):
            def __init__(self):
                super().__init__()
                self.closed = False

            def close(self):
                self.closed = True

        inner = _TrackingSink()
        ThreadedDmxSink(inner).close()
        self.assertTrue(inner.closed)

    def test_frame_and_sends_proxy_to_the_inner(self):
        inner = FakeDmxSink()
        sink = ThreadedDmxSink(inner)
        sink.send({1: 77})
        sink.close()
        self.assertIs(sink.frame, inner.frame)
        self.assertEqual(inner.sends, sink.sends)

    def test_errors_proxies_to_the_inner(self):
        # .errors only exists on the real transport (OlaDmxSink); production
        # only wraps that one (see app.py), so exercise it here.
        inner = OlaDmxSink(base_url="http://127.0.0.1:0")  # nothing listens
        sink = ThreadedDmxSink(inner)
        with contextlib.redirect_stderr(io.StringIO()):
            sink.send({1: 1})
            sink.close()
        self.assertEqual(inner.errors, sink.errors)
        self.assertGreater(sink.errors, 0)


class BuildSinkTest(unittest.TestCase):
    def test_fake_sink(self):
        self.assertIsInstance(build_sink("fake"), FakeDmxSink)

    def test_unknown_kind_raises(self):
        with self.assertRaises(ValueError):
            build_sink("laser")


if __name__ == "__main__":
    unittest.main()
