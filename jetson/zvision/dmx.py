"""Transmit a DMX universe. The tracker (``tracker.py``) computes channel values;
these sinks put them on the wire — kept behind a ``DmxSink`` Protocol so the pure
mapping code never imports any DMX transport, exactly like ``detector.py`` keeps
cv2 out of the fake path.

Two sinks:
  * ``FakeDmxSink``  — standard library only. Holds the 512-byte frame in memory
                       so ``--dmx fake`` proves the whole select→map→slew loop
                       (and lets tests assert on channel values) with no hardware.
  * ``OlaDmxSink``   — posts the frame to a running ``olad`` over its built-in
                       JSON/HTTP API (default 127.0.0.1:9090). Deliberately uses
                       stdlib ``urllib`` rather than the ``ola`` Python bindings:
                       no extra dep, and no inverting our loop into OLA's event
                       loop. ``olad`` owns the USB→DMX512 dongle (``ftdidmx``
                       plugin) and the timing.

Both keep a persistent 512-channel frame and merge each :meth:`send` into it, so
channels the tracker doesn't touch hold their last value instead of dropping to 0.
"""

from __future__ import annotations

import sys
import threading
from typing import Dict, Optional, Protocol

DMX_UNIVERSE_SIZE = 512


class DmxSink(Protocol):
    def send(self, channels: Dict[int, int]) -> None:
        """Merge 1-based channel->value writes into the universe and transmit."""
        ...

    def close(self) -> None:
        ...


def _merge(frame: bytearray, channels: Dict[int, int]) -> None:
    """Write 1-based channel numbers into a 0-indexed 512-byte frame, clamping to
    valid ranges and silently skipping out-of-range channels."""
    for ch, val in channels.items():
        if 1 <= ch <= DMX_UNIVERSE_SIZE:
            frame[ch - 1] = max(0, min(255, int(val)))


class FakeDmxSink:
    """In-memory sink: no transport, just the current frame + a send counter. The
    ``--dmx fake`` path and the unit tests read ``frame`` / ``last_channels``."""

    def __init__(self, universe: int = 0) -> None:
        self.universe = universe
        self.frame = bytearray(DMX_UNIVERSE_SIZE)
        self.last_channels: Dict[int, int] = {}
        self.sends = 0

    def send(self, channels: Dict[int, int]) -> None:
        _merge(self.frame, channels)
        self.last_channels = dict(channels)
        self.sends += 1

    def close(self) -> None:
        pass


class OlaDmxSink:
    """Streams the universe to ``olad`` via HTTP ``POST /set_dmx`` (u=<universe>,
    d=<comma-separated 512 values>). A send failure (olad down, dongle unplugged)
    is counted and swallowed — a lighting glitch must never take down the threat
    broadcaster the HUD depends on."""

    def __init__(self, universe: int = 0, base_url: str = "http://127.0.0.1:9090") -> None:
        self.universe = universe
        self.base_url = base_url.rstrip("/")
        self.frame = bytearray(DMX_UNIVERSE_SIZE)
        self.sends = 0
        self.errors = 0
        self._last_error: Optional[str] = None
        self._reported = False

    def send(self, channels: Dict[int, int]) -> None:
        import urllib.parse
        import urllib.request

        _merge(self.frame, channels)
        data = urllib.parse.urlencode(
            {"u": self.universe, "d": ",".join(str(b) for b in self.frame)}
        ).encode("ascii")
        try:
            with urllib.request.urlopen(f"{self.base_url}/set_dmx", data=data, timeout=1.0):
                self.sends += 1
                self._reported = False  # re-arm: a later outage is news again
        # olad reachability is an IO boundary: keep tracking even if lighting drops.
        except Exception as exc:  # noqa: BLE001 - see rationale above
            self.errors += 1
            self._last_error = str(exc)
            # Swallowed, but not silently: the OLA layer has already failed
            # this project once with zero symptoms (plugin config written where
            # olad never reads it), and a dead head at night just looks off.
            # One line per outage — reported once, quiet until it recovers.
            if not self._reported:
                self._reported = True
                print(
                    f"zvision: DMX to {self.base_url} failing ({exc}); "
                    "head may be holding its last frame",
                    file=sys.stderr,
                    flush=True,
                )

    def close(self) -> None:
        pass


class ThreadedDmxSink:
    """Wraps a :class:`DmxSink` so the hot detect->broadcast loop never blocks on
    a wedged transport.

    ``OlaDmxSink.send`` burns up to its full 1.0s ``urlopen`` timeout against an
    olad that accepts a connection but never replies, and it runs synchronously
    inside ``app.py``'s single per-frame loop -- so a stuck POST drops the 10 Hz
    ZTHREAT broadcast the driver's HUD depends on to ~1 Hz. That violates the
    sink's own contract: "a lighting glitch must never take down the threat
    broadcaster."

    A single-slot latest-frame mailbox (guarded by a ``Condition``) plus a
    daemon worker thread that drains it: :meth:`send` copies the channels into
    the slot, notifies, and returns immediately. The worker calls
    ``inner.send()`` off the hot path -- ``inner`` keeps its own timeout and
    error counting, this wrapper only takes it out of the frame loop. If the
    worker falls behind (inner is slow), a burst of sends COALESCES to the
    newest frame rather than queuing every one; ``dropped`` counts how many
    were discarded that way.
    """

    def __init__(self, inner: "DmxSink") -> None:
        self._inner = inner
        self._cond = threading.Condition()
        self._pending: Optional[Dict[int, int]] = None
        self._closed = False
        self.dropped = 0
        self._thread = threading.Thread(target=self._run, daemon=True, name="dmx-sink")
        self._thread.start()

    @property
    def frame(self) -> bytearray:
        return self._inner.frame

    @property
    def sends(self) -> int:
        return self._inner.sends

    @property
    def errors(self) -> int:
        return self._inner.errors

    def send(self, channels: Dict[int, int]) -> None:
        with self._cond:
            if self._pending is not None:
                self.dropped += 1  # a still-unsent frame is about to be overwritten
            self._pending = dict(channels)
            self._cond.notify()

    def _run(self) -> None:
        while True:
            with self._cond:
                while self._pending is None and not self._closed:
                    self._cond.wait()
                if self._pending is None:  # closed, nothing left to flush
                    return
                channels = self._pending
                self._pending = None
            self._inner.send(channels)

    def close(self) -> None:
        """Signal shutdown, let the worker flush any still-pending frame (so an
        exit-time park actually reaches ``inner``), then join with a bound --
        a wedged olad must not be able to hang the whole process on the way
        out."""
        with self._cond:
            self._closed = True
            self._cond.notify()
        self._thread.join(timeout=2.0)
        self._inner.close()


def build_sink(kind: str, universe: int = 0, base_url: str = "http://127.0.0.1:9090") -> DmxSink:
    """Factory mirroring ``build_detector``: ``fake`` needs nothing, ``ola`` talks
    to a local ``olad``."""
    if kind == "fake":
        return FakeDmxSink(universe=universe)
    if kind == "ola":
        return OlaDmxSink(universe=universe, base_url=base_url)
    raise ValueError(f"unknown DMX sink {kind!r} (want 'fake' or 'ola')")
