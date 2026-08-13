"""The runner, against a fake panel and a fake light. Covers the things the pure
model cannot reach: that a press actually reaches the wire, that a lighting
failure does not take the panel down, and that no path leaves the head hot."""

import unittest

from zdeck.app import DeckRunner, _parse, run_loop
from zdeck.model import BLACKOUT, COLOUR, DIM_UP, KEY_COUNT, LAMP
from zdeck.surface import FakeSurface
from zvision.dmx import FakeDmxSink
from zvision.tracker import TrackerConfig


class _AngrySink(FakeDmxSink):
    """A light that always fails to send — olad down, dongle unplugged."""

    def send(self, channels):
        raise OSError("connection refused")


class _FlakySink(FakeDmxSink):
    """Fails once, then recovers."""

    def __init__(self):
        super().__init__()
        self.attempts = 0

    def send(self, channels):
        self.attempts += 1
        if self.attempts == 1:
            raise OSError("transient")
        super().send(channels)


def _runner(sink=None, killer=None):
    surface = FakeSurface()
    r = DeckRunner(surface, sink or FakeDmxSink(), killer=killer or (lambda: True))
    r.start()
    return r, surface


class StartupTest(unittest.TestCase):
    def test_start_opens_sets_brightness_and_paints_once(self):
        r, s = _runner()
        self.assertTrue(s.opened)
        self.assertEqual(20, s.brightness)
        self.assertEqual(1, len(s.drawn), "panel should be painted before any press")
        self.assertEqual(KEY_COUNT, len(s.last_drawn))

    def test_brightness_starts_low_not_full(self):
        # A Stream Deck at default brightness is a small floodlight in a dark
        # cab, and this vehicle's whole design language is about not wrecking
        # the driver's dark adaptation.
        r, s = _runner()
        self.assertLessEqual(s.brightness, 30)

    def test_the_head_starts_dark(self):
        r, s = _runner()
        dimmer = TrackerConfig().dimmer_channel
        self.assertEqual(0, r.sink.frame[dimmer - 1])


class PressTest(unittest.TestCase):
    def test_a_press_reaches_the_wire(self):
        r, s = _runner()
        s.press(LAMP)
        dimmer = TrackerConfig().dimmer_channel
        self.assertEqual(255, r.sink.frame[dimmer - 1])

    def test_release_does_not_double_the_action(self):
        # FakeSurface.press() fires down AND up. Acting on both would toggle
        # LAMP twice and leave the light where it started.
        r, s = _runner()
        s.press(LAMP)
        self.assertEqual(1, r.presses)
        self.assertTrue(r.model.lit)

    def test_each_press_repaints_the_panel(self):
        r, s = _runner()
        before = len(s.drawn)
        s.press(COLOUR)
        self.assertEqual(before + 1, len(s.drawn))

    def test_the_panel_reflects_what_was_sent(self):
        r, s = _runner()
        s.press(LAMP)
        dimmer = TrackerConfig().dimmer_channel
        self.assertEqual("ON", s.last_drawn[LAMP].value)
        self.assertGreater(r.sink.frame[dimmer - 1], 0)

    def test_blackout_after_dimming_up_ends_dark(self):
        r, s = _runner()
        for _ in range(4):
            s.press(DIM_UP)
        self.assertTrue(r.model.lit)
        s.press(BLACKOUT)
        dimmer = TrackerConfig().dimmer_channel
        self.assertEqual(0, r.sink.frame[dimmer - 1])


class FailureTest(unittest.TestCase):
    def test_a_dead_light_does_not_take_the_panel_down(self):
        # The deck is the operator's only physical control. If olad dies the
        # keys must keep responding, or they lose the light AND the ability to
        # do anything about it.
        r, s = _runner(_AngrySink())
        s.press(LAMP)
        s.press(COLOUR)
        self.assertEqual(2, r.presses)
        self.assertEqual(3, len(s.drawn))  # initial + one per press
        self.assertGreater(r.errors, 0)

    def test_the_panel_keeps_working_after_a_transient_send_failure(self):
        r, s = _runner(_FlakySink())
        s.press(LAMP)          # this one fails
        s.press(DIM_UP)        # this one must still go out
        self.assertEqual(1, r.errors)
        self.assertGreater(r.sink.sends, 0)

    def test_park_blacks_out_on_the_way_down(self):
        r, s = _runner()
        s.press(LAMP)
        r.park()
        dimmer = TrackerConfig().dimmer_channel
        self.assertEqual(0, r.sink.frame[dimmer - 1])
        self.assertFalse(r.model.lit)

    def test_park_survives_a_dead_light(self):
        # Exit path must not raise, or systemd sees a crash instead of a clean
        # stop and the real reason is lost.
        r, s = _runner(_AngrySink())
        s.press(LAMP)
        r.park()  # must not raise
        self.assertGreater(r.errors, 0)


class KillTargetsTheConfiguredUniverseTest(unittest.TestCase):
    """BLACKOUT must park the universe the sink is actually lighting, not
    always universe 0 @ localhost -- see zvision/dmxpark.park and the
    --dmx-universe/--dmx-url flags this runner is supposed to forward."""

    def test_deckrunner_default_killer_parks_the_configured_universe(self):
        import zdeck.app as app_module

        captured = {}

        def fake_park(**kwargs):
            captured.update(kwargs)
            return True

        original = app_module.dmx_park
        app_module.dmx_park = fake_park
        try:
            runner = DeckRunner(FakeSurface(), FakeDmxSink(), killer=None,
                                 universe=7, base_url="http://10.0.0.9:9090")
            runner.kill()
        finally:
            app_module.dmx_park = original
        self.assertEqual(7, captured.get("universe"))
        self.assertEqual("http://10.0.0.9:9090", captured.get("base_url"))

    def test_run_loop_forwards_universe_and_base_url_to_the_default_killer(self):
        import zdeck.app as app_module

        captured = {}

        def fake_park(**kwargs):
            captured.update(kwargs)
            return True

        original = app_module.dmx_park
        app_module.dmx_park = fake_park
        try:
            run_loop(lambda: FakeSurface(), FakeDmxSink(), lambda: True,
                     once=True, killer=None, universe=5, base_url="http://h:1",
                     sleep=lambda _s: None)
        finally:
            app_module.dmx_park = original
        self.assertEqual(5, captured.get("universe"))
        self.assertEqual("http://h:1", captured.get("base_url"))


class ArgsTest(unittest.TestCase):
    def test_dmx_defaults_to_ola_because_the_deck_is_useless_without_light(self):
        self.assertEqual("ola", _parse([]).dmx)

    def test_brightness_is_rejected_outside_zero_to_one_hundred(self):
        from zdeck.app import main
        for bad in ("-1", "101"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                main(["--brightness", bad, "--dmx", "fake", "--once"])

    def test_reconnect_delay_is_configurable(self):
        self.assertEqual(9.0, _parse(["--reconnect-secs", "9"]).reconnect_secs)


if __name__ == "__main__":
    unittest.main()


class KillTrustworthinessTest(unittest.TestCase):
    """BLACKOUT is the one key that must not lie. It deliberately bypasses the
    ordinary sink, which swallows its own send failures by design."""

    def test_blackout_uses_the_retrying_killer_not_the_sink(self):
        called = {"n": 0}

        def killer():
            called["n"] += 1
            return True

        r, s = _runner(killer=killer)
        s.press(BLACKOUT)
        self.assertEqual(1, called["n"], "BLACKOUT did not go through the kill path")

    def test_a_failed_kill_shows_a_fault_instead_of_a_calm_panel(self):
        # The dangerous case: beam live, operator hits BLACKOUT, the write does
        # not land. A panel showing a tidy grey BLACKOUT over a lit head is the
        # one lie that gets someone dazzled at 4am.
        r, s = _runner(killer=lambda: False)
        s.press(LAMP)
        s.press(BLACKOUT)
        self.assertTrue(r.model.fault)
        labels = [k.label for k in s.last_drawn]
        self.assertIn("DMX FAIL", labels)
        self.assertEqual("#FF5555", s.last_drawn[0].colour)

    def test_a_killer_that_raises_is_treated_as_a_failed_kill(self):
        def boom():
            raise OSError("olad gone")

        r, s = _runner(killer=boom)
        s.press(LAMP)
        s.press(BLACKOUT)   # must not propagate
        self.assertTrue(r.model.fault)

    def test_a_successful_kill_clears_a_previous_fault(self):
        outcomes = iter([False, True])
        r, s = _runner(killer=lambda: next(outcomes))
        s.press(BLACKOUT)
        self.assertTrue(r.model.fault)
        s.press(BLACKOUT)
        self.assertFalse(r.model.fault)


class ReconnectTest(unittest.TestCase):
    """The bump-and-power paths. None of this was reachable before the loop was
    extracted from main()."""

    def _loop(self, surfaces, sink=None, **kw):
        made = list(surfaces)
        sink = sink or FakeDmxSink()
        killed = {"n": 0}

        def killer():
            killed["n"] += 1
            return True

        def factory():
            return made.pop(0) if made else FakeSurface()

        ticks = {"n": 0}

        def running():
            ticks["n"] += 1
            return ticks["n"] <= kw.pop("ticks", 40)

        run_loop(factory, sink, running, reconnect_secs=0.0,
                 killer=killer, sleep=lambda _s: None, **kw)
        return sink, killed

    def test_losing_the_deck_parks_the_head_rather_than_leaving_it_hot(self):
        # While the panel is gone the operator has no kill control, so the beam
        # must not be left burning until it comes back.
        s = FakeSurface()
        sink = FakeDmxSink()
        killed = {"n": 0}

        def factory():
            return s

        state = {"n": 0}

        def running():
            state["n"] += 1
            return state["n"] < 6

        def killer():
            killed["n"] += 1
            return True

        run_loop(factory, sink, running, reconnect_secs=0.0,
                 killer=killer, sleep=lambda _s: s.unplug())
        self.assertGreater(killed["n"], 0, "deck went away without parking the head")

    def test_a_missing_deck_is_retried_not_fatal(self):
        from zdeck.surface import DeckNotPresent

        class Absent(FakeSurface):
            def open(self):
                raise DeckNotPresent("no Stream Deck found")

        sink = FakeDmxSink()
        tries = {"n": 0}

        def factory():
            tries["n"] += 1
            return Absent()

        state = {"n": 0}

        def running():
            state["n"] += 1
            return state["n"] < 8

        rc = run_loop(factory, sink, running, reconnect_secs=0.0,
                      killer=lambda: True, sleep=lambda _s: None)
        self.assertEqual(0, rc)
        self.assertGreater(tries["n"], 1, "did not retry after a missing deck")

    def test_the_surface_is_always_closed_on_exit(self):
        # Leaving the device open trips a libusb teardown assertion -- a crash
        # rather than a clean stop. Caught by smoke-testing on the real Jetson.
        s = FakeSurface()
        run_loop(lambda: s, FakeDmxSink(), lambda: True,
                 once=True, killer=lambda: True, sleep=lambda _s: None)
        self.assertTrue(s.closed)

    def test_park_detaches_the_callback_before_killing(self):
        # Otherwise a press already queued in the vendor reader thread can
        # re-light the head *after* the park.
        r, s = _runner()
        s.press(LAMP)
        r.park()
        s.press(LAMP)          # callback detached: must do nothing
        self.assertFalse(r.model.lit)


class SurfaceTeardownTest(unittest.TestCase):
    """StreamDeckSurface.close() against a stub device. Importable in CI because
    hardware.py keeps every vendor import inside a function."""

    class _StubDeck:
        def __init__(self, reset_raises=False):
            self.reset_raises = reset_raises
            self.closed = False
            self.was_reset = False

        def reset(self):
            self.was_reset = True
            if self.reset_raises:
                raise OSError("device busy")

        def close(self):
            self.closed = True

    def _surface(self, deck):
        from zdeck.hardware import StreamDeckSurface
        s = StreamDeckSurface()
        s._deck = deck
        return s

    def test_close_still_closes_when_reset_throws(self):
        # The hang: one shared try block meant a throwing reset() skipped
        # close(), the vendor reader thread survived, and the process never
        # exited. Found by smoke-testing on the real Jetson, not by a unit test.
        deck = self._StubDeck(reset_raises=True)
        self._surface(deck).close()
        self.assertTrue(deck.closed, "close() was skipped because reset() threw")

    def test_close_resets_then_closes_on_a_healthy_device(self):
        deck = self._StubDeck()
        self._surface(deck).close()
        self.assertTrue(deck.was_reset)
        self.assertTrue(deck.closed)

    def test_close_is_idempotent(self):
        deck = self._StubDeck()
        s = self._surface(deck)
        s.close()
        s.close()  # must not raise on an already-closed surface
        self.assertFalse(s.connected())


class FaultsAreNotDisguisedAsAMissingDeckTest(unittest.TestCase):
    def test_a_missing_deck_is_retried_quietly(self):
        from zdeck.surface import DeckNotPresent

        class Absent(FakeSurface):
            def open(self):
                raise DeckNotPresent("no Stream Deck found")

        state = {"n": 0}

        def running():
            state["n"] += 1
            return state["n"] < 6

        rc = run_loop(lambda: Absent(), FakeDmxSink(), running,
                      reconnect_secs=0.0, killer=lambda: True, sleep=lambda _s: None)
        self.assertEqual(0, rc)

    def test_a_programming_error_is_not_swallowed_as_a_missing_deck(self):
        # A stale checkout raised AttributeError inside open() and the runner
        # spun forever printing "no deck; retrying" -- naming the wrong problem
        # and hiding the real one. Bugs must be loud.
        class Broken(FakeSurface):
            def open(self):
                raise AttributeError("'TrackerConfig' object has no attribute 'colour_channel'")

        with self.assertRaises(AttributeError):
            run_loop(lambda: Broken(), FakeDmxSink(), lambda: True,
                     reconnect_secs=0.0, killer=lambda: True, sleep=lambda _s: None)
