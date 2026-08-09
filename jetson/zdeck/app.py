"""Runner: wire a panel to the light and keep it alive for a fortnight.

Three properties matter more than features.

**It must never wedge.** A missing or unplugged deck is the normal state, not an
error — the runner waits and re-enumerates. This rig loses USB devices over
bumps.

**It must never leave the head hot.** Every exit path parks, *including losing
the deck*: while the panel is gone the operator has no kill control, so the beam
must not be left burning until it comes back.

**The kill must be trustworthy.** BLACKOUT does not go through the ordinary sink,
which swallows its own failures — it uses ``dmxpark.park()``, which retries and
reports. A panel that says dark over a live head is the one lie that matters.
"""

from __future__ import annotations

import argparse
import signal
import sys
import time
from dataclasses import replace
from typing import Callable, Optional

from zvision.dmx import build_sink
from zvision.dmxpark import park as dmx_park

from .model import BLACKOUT, KEY_COUNT, DeckConfig, DeckModel
from .surface import DeckNotPresent, DeckSurface


class DeckRunner:
    """Owns the model, the panel and the light. Testable against a
    ``FakeSurface`` and a ``FakeDmxSink`` — no hardware anywhere in here."""

    def __init__(self, surface: DeckSurface, sink, cfg: Optional[DeckConfig] = None,
                 brightness: int = 20, killer: Optional[Callable[[], bool]] = None,
                 universe: int = 0, base_url: str = "http://127.0.0.1:9090") -> None:
        self.surface = surface
        self.sink = sink
        self.model = DeckModel(cfg=cfg or DeckConfig())
        self.brightness = brightness
        # Injectable so tests can make the kill fail without a network.
        self._killer = killer or (lambda: dmx_park(universe=universe, base_url=base_url))
        self.presses = 0
        self.errors = 0
        self.kills = 0

    def start(self) -> None:
        self.surface.open()
        self.surface.set_brightness(self.brightness)
        self.surface.set_key_callback(self.on_key)
        self.publish()

    def on_key(self, key: int, pressed: bool) -> None:
        """Act on press, ignore release. Acting on both would double every
        action and leave LAMP exactly where it started."""
        if not pressed:
            return
        self.presses += 1
        if key == BLACKOUT:
            self.kill()
            return
        self.model = self.model.press(key)
        self.publish()

    def kill(self) -> bool:
        """Blackout, the trustworthy way.

        Goes via ``dmxpark`` rather than the sink because ``OlaDmxSink`` swallows
        its send failures by design — which is right for a tracker frame and
        wrong for a kill. If it does not land, the panel says so in red instead
        of showing a calm BLACKOUT over a beam that is still on someone.
        """
        self.kills += 1
        self.model = self.model.press(BLACKOUT)
        ok = False
        try:
            ok = bool(self._killer())
        except Exception:  # noqa: BLE001 - a kill that raises is not a kill
            ok = False
        if not ok:
            self.errors += 1
        self.model = replace(self.model, fault=not ok)
        # Keep the sink's own frame in step, so a later ordinary press does not
        # resurrect the pre-kill dimmer from its cached universe.
        try:
            self.sink.send(self.model.channels())
        except Exception:  # noqa: BLE001 - already reported via fault
            pass
        self.surface.draw(self.model.render())
        return ok

    def publish(self) -> None:
        """Send the light, then redraw. DMX first: the panel must never claim a
        state the light was not told to enter."""
        frame = self.model.frame()
        try:
            self.sink.send(frame.channels)
        # A lighting failure must not take the panel down; it is the operator's
        # only physical control and has to stay usable.
        except Exception:  # noqa: BLE001 - see rationale above
            self.errors += 1
            self.model = replace(self.model, fault=True)
            frame = self.model.frame()
        self.surface.draw(frame.keys)

    def park(self) -> bool:
        """Blackout on the way out, whatever happened. Detaches the key callback
        first so a press queued in the reader thread cannot re-light the head
        after we have parked it."""
        try:
            self.surface.set_key_callback(None)
        except Exception:  # noqa: BLE001 - going down anyway
            pass
        return self.kill()


def run_loop(surface_factory: Callable[[], DeckSurface], sink, running,
             reconnect_secs: float = 3.0, brightness: int = 20,
             once: bool = False, killer=None, sleep=time.sleep) -> int:
    """Own a deck for as long as there is one, then wait for the next.

    Extracted from ``main`` so the paths that actually happen on a vehicle —
    unplug, replug, signal during the reconnect wait — are reachable from tests.
    """
    runner = None
    surface = None
    try:
        while running():
            surface = surface_factory()
            try:
                runner = DeckRunner(surface, sink, brightness=brightness, killer=killer)
                runner.start()
                print(f"zdeck: deck online, {KEY_COUNT} keys", flush=True)
            # No deck is the normal state on a vehicle, not a failure: wait and
            # look again rather than exiting into a systemd restart loop. Note
            # this catches ONLY DeckNotPresent -- a bug must not be able to
            # disguise itself as an unplugged cable.
            except DeckNotPresent as exc:
                print(f"zdeck: no deck ({exc}); retrying", file=sys.stderr, flush=True)
                surface.close()
                runner = None
                sleep(reconnect_secs)
                continue
            if once:
                break
            while running() and surface.connected():
                sleep(0.25)
            # Losing the panel means losing the kill control, so the head must
            # not be left burning while we wait for it to come back.
            print("zdeck: deck went away, parking the head", file=sys.stderr, flush=True)
            runner.park()
            surface.close()
            runner = None
    finally:
        if runner is not None:
            runner.park()
        if surface is not None:
            # Always close: leaving the device open on exit trips a libusb
            # teardown assertion, which is a crash rather than a clean stop.
            surface.close()
    return 0


def _parse(argv=None):
    p = argparse.ArgumentParser(prog="python3 -m zdeck", description="Stream Deck light control")
    p.add_argument("--dmx", choices=["none", "fake", "ola"], default="ola")
    p.add_argument("--dmx-universe", type=int, default=0)
    p.add_argument("--dmx-url", default="http://127.0.0.1:9090")
    p.add_argument("--brightness", type=int, default=20,
                   help="deck backlight 0-100; low on purpose, it sits in the cab at night")
    p.add_argument("--reconnect-secs", type=float, default=3.0)
    p.add_argument("--once", action="store_true", help="start, publish one frame, exit")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = _parse(argv)
    if not 0 <= args.brightness <= 100:
        raise ValueError(f"--brightness must be 0..100, got {args.brightness}")
    if args.reconnect_secs <= 0:
        raise ValueError(f"--reconnect-secs must be positive, got {args.reconnect_secs}")
    from .hardware import StreamDeckSurface

    sink = build_sink("fake" if args.dmx == "none" else args.dmx,
                      universe=args.dmx_universe, base_url=args.dmx_url)
    go = {"run": True}

    def _stop(*_):
        go["run"] = False

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    try:
        return run_loop(
            lambda: StreamDeckSurface(brightness=args.brightness),
            sink, lambda: go["run"],
            reconnect_secs=args.reconnect_secs, brightness=args.brightness,
            once=args.once,
            killer=(None if args.dmx == "ola"
                    else (lambda: True)),  # no olad to park against on fake/none
        )
    finally:
        try:
            sink.close()
        except Exception:  # noqa: BLE001 - shutting down
            pass


if __name__ == "__main__":
    raise SystemExit(main())
