"""The panel itself, behind a Protocol so the whole loop runs with no hardware.

``FakeSurface`` records what it was asked to draw and lets a test inject presses,
which is enough to prove key mapping, rendering and reconnect logic in CI —
where neither the StreamDeck library nor a deck exists. Same split as
``zvision.dmx``: fake in the standard library, real device isolated elsewhere.
"""

from __future__ import annotations

from typing import Callable, List, Optional, Protocol

from .model import KeyRender

KeyCallback = Callable[[int, bool], None]


class DeckNotPresent(RuntimeError):
    """No panel is plugged in. The *only* condition the runner retries quietly.

    Everything else -- a missing dependency, a typo, a broken import -- is a
    programming error and must be loud. Lumping them together turned a stale
    checkout into an infinite "no deck; retrying" loop that named the wrong
    problem, which is exactly how a real fault hides on a vehicle.
    """


class DeckSurface(Protocol):
    """Minimum a physical panel must offer. Deliberately small: brightness,
    drawing keys, and reporting presses."""

    def open(self) -> None: ...
    def close(self) -> None: ...
    def key_count(self) -> int: ...
    def set_brightness(self, percent: int) -> None: ...
    def draw(self, keys: List[KeyRender]) -> None: ...
    def set_key_callback(self, cb: Optional[KeyCallback]) -> None: ...
    def connected(self) -> bool: ...


class FakeSurface:
    """In-memory panel. ``drawn`` holds every frame it was asked to render, so a
    test can assert on what the operator would actually have seen."""

    def __init__(self, key_count: int = 6) -> None:
        self._count = key_count
        self.drawn: List[List[KeyRender]] = []
        self.brightness: Optional[int] = None
        self.opened = False
        self.closed = False
        self._cb: Optional[KeyCallback] = None
        self._connected = True

    # -- DeckSurface ----------------------------------------------------
    def open(self) -> None:
        self.opened = True

    def close(self) -> None:
        self.closed = True

    def key_count(self) -> int:
        return self._count

    def set_brightness(self, percent: int) -> None:
        self.brightness = percent

    def draw(self, keys: List[KeyRender]) -> None:
        self.drawn.append(list(keys))

    def set_key_callback(self, cb: Optional[KeyCallback]) -> None:
        self._cb = cb

    def connected(self) -> bool:
        return self._connected

    # -- test helpers ---------------------------------------------------
    def press(self, key: int) -> None:
        """Simulate a press-and-release."""
        if self._cb:
            self._cb(key, True)
            self._cb(key, False)

    def unplug(self) -> None:
        self._connected = False

    @property
    def last_drawn(self) -> List[KeyRender]:
        return self.drawn[-1] if self.drawn else []
