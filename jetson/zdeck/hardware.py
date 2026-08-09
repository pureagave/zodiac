"""The real Stream Deck. Everything that imports the vendor library or PIL lives
here and nowhere else, so :mod:`zdeck.model` and the tests stay runnable on a
machine with neither — the CI box has neither.

Measured on the vehicle's unit: Stream Deck Mini, ``0fd9:0063``, firmware
3.03.002, six keys in a 2x3 layout, 80x80 BMP key images.
"""

from __future__ import annotations

from typing import List, Optional

from .model import PURPLE, KeyRender
from .surface import DeckNotPresent, KeyCallback

FONT_PATHS = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
)


class StreamDeckSurface:
    """A real deck. Construction does no I/O so it can be built before the
    device exists; :meth:`open` is what may fail, and the runner treats that as
    "not plugged in yet" rather than as a crash."""

    def __init__(self, brightness: int = 20) -> None:
        self._deck = None
        self._brightness = brightness
        self._cb: Optional[KeyCallback] = None
        self.draw_errors = 0
        self.close_errors = 0

    def open(self) -> None:
        from StreamDeck.DeviceManager import DeviceManager

        decks = DeviceManager().enumerate()
        if not decks:
            raise DeckNotPresent("no Stream Deck found")
        self._deck = decks[0]
        self._deck.open()
        self._deck.reset()
        self._deck.set_brightness(self._brightness)
        if self._cb:
            self._wire_callback()

    def close(self) -> None:
        if self._deck is None:
            return
        # reset() and close() get SEPARATE try blocks, and that is the whole
        # point. Sharing one meant a throwing reset() skipped close(), the
        # vendor library's non-daemon reader thread survived, and the process
        # hung at interpreter exit -- observed as a --once run sitting for four
        # minutes doing nothing. We are usually closing *because* the device is
        # already unhappy, so reset() failing is the expected case, not the
        # exotic one.
        try:
            self._deck.reset()
        except Exception:  # noqa: BLE001 - see comment above
            self.close_errors += 1
        try:
            self._deck.close()
        except Exception:  # noqa: BLE001 - nothing left to try
            self.close_errors += 1
        self._deck = None

    def key_count(self) -> int:
        return self._deck.key_count() if self._deck else 0

    def set_brightness(self, percent: int) -> None:
        self._brightness = percent
        if self._deck:
            self._deck.set_brightness(percent)

    def connected(self) -> bool:
        """Ask the transport, not the class.

        This used to call ``key_count()``, which returns the ``KEY_COUNT`` class
        constant and does no I/O whatsoever -- so it could never report a
        disconnect, the reconnect loop was unreachable, and a deck knocked off
        by a bump would have left the service green with dead keys. The library's
        real probe is ``connected()``.
        """
        if self._deck is None:
            return False
        try:
            return bool(self._deck.connected())
        # Anything thrown while asking means gone; the runner re-enumerates.
        except Exception:  # noqa: BLE001 - see rationale above
            return False

    def set_key_callback(self, cb: Optional[KeyCallback]) -> None:
        self._cb = cb
        if self._deck:
            self._wire_callback()

    def _wire_callback(self) -> None:
        cb = self._cb

        def _shim(_deck, key: int, state: bool) -> None:
            if cb:
                cb(key, bool(state))

        self._deck.set_key_callback(_shim)

    # -- rendering ------------------------------------------------------
    def draw(self, keys: List[KeyRender]) -> None:
        """Never raises. A draw that throws would propagate out of the key
        callback into the vendor library's reader thread and kill it -- input
        dead for good, while everything else still looked healthy."""
        if self._deck is None:
            return
        try:
            for i, k in enumerate(keys[: self._deck.key_count()]):
                self._deck.set_key_image(i, self._image(k))
        except Exception:  # noqa: BLE001 - see docstring
            self.draw_errors += 1

    def _font(self, size: int):
        from PIL import ImageFont

        for p in FONT_PATHS:
            try:
                return ImageFont.truetype(p, size)
            except OSError:
                continue
        return ImageFont.load_default()

    def _image(self, k: KeyRender):
        from PIL import ImageDraw
        from StreamDeck.ImageHelpers import PILHelper

        img = PILHelper.create_key_image(self._deck)
        d = ImageDraw.Draw(img)
        label_font = self._font(15)
        box = d.textbbox((0, 0), k.label, font=label_font)
        d.text(
            ((img.width - (box[2] - box[0])) / 2, 8),
            k.label,
            font=label_font,
            fill=k.colour,
        )
        if k.value:
            # Live values are always purple, whatever the label's colour --
            # the same separation the screens use between chrome and data.
            value_font = self._font(22)
            box = d.textbbox((0, 0), k.value, font=value_font)
            d.text(
                ((img.width - (box[2] - box[0])) / 2, img.height - 34),
                k.value,
                font=value_font,
                fill=PURPLE,
            )
        return PILHelper.to_native_key_format(self._deck, img)
