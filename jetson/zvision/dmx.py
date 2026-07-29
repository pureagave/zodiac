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
        # olad reachability is an IO boundary: keep tracking even if lighting drops.
        except Exception as exc:  # noqa: BLE001 - see rationale above
            self.errors += 1
            self._last_error = str(exc)

    def close(self) -> None:
        pass


def build_sink(kind: str, universe: int = 0, base_url: str = "http://127.0.0.1:9090") -> DmxSink:
    """Factory mirroring ``build_detector``: ``fake`` needs nothing, ``ola`` talks
    to a local ``olad``."""
    if kind == "fake":
        return FakeDmxSink(universe=universe)
    if kind == "ola":
        return OlaDmxSink(universe=universe, base_url=base_url)
    raise ValueError(f"unknown DMX sink {kind!r} (want 'fake' or 'ola')")
