"""What the six keys mean, as pure state. No hardware, no DMX transport, no PIL.

Every press goes through :meth:`DeckModel.press`, which returns a
:class:`DeckFrame` holding both halves of the response: the DMX channels to
write, and how each key should now look. Keeping those together is deliberate —
the panel lying about the state of the light is its own kind of fault, and this
way the render can never drift from the channels that were actually sent.

Palette follows ``ui/concepts/ConceptTheme``, the same rules as the screens:
green for controls, purple for live data values, red for faults/kill only. No
amber, ever — which also rules out most of this fixture's colour wheel
(MOVING-HEAD.md §8.6).
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Dict, List, Optional, Tuple

from zvision.tracker import TrackerConfig

# ui/concepts/ConceptTheme
GREEN = "#00FF66"   # chrome / controls
PURPLE = "#C77DFF"  # live data values
RED = "#FF5555"     # faults and kill only
GREY = "#555555"    # a control that would currently do nothing

#: Colour-wheel slots that are ON PALETTE, measured on the real fixture with the
#: rig's camera (MOVING-HEAD.md §8.6). Values are slot centres, not arithmetic
#: midpoints of the DMX range. Roughly a third of this wheel is amber/yellow and
#: is deliberately absent: the palette bans amber on the screens, and a light
#: hanging off the same vehicle does not get an exemption.
ON_PALETTE_COLOURS: Tuple[Tuple[str, int], ...] = (
    ("WHITE", 0),
    ("BLUE", 34),
    ("GREEN", 128),
    ("PURPLE", 78),
)

#: ch5 values at or above this hand the wheel to its auto-spin program
#: (MOVING-HEAD.md §3.2). Nothing here may ever emit one.
COLOUR_AUTO_SPIN_FLOOR = 140

#: The amber/yellow bands of this fixture's colour wheel, measured with the rig's
#: camera (MOVING-HEAD.md §8.6) — roughly a third of the wheel. Amber is banned
#: across the project's screens and a light bolted to the same vehicle does not
#: get an exemption. Enforced in :meth:`DeckConfig.__post_init__` rather than
#: left as a convention, because "don't add an amber slot" is exactly the kind of
#: rule that survives right up until someone adds a slot.
AMBER_BANDS: Tuple[Tuple[int, int], ...] = ((40, 58), (90, 118))

BLACKOUT, LAMP, HOME, DIM_DOWN, DIM_UP, COLOUR = range(6)
KEY_COUNT = 6


@dataclass(frozen=True)
class KeyRender:
    """How one key should look. ``value`` is live data and is drawn in purple by
    the surface; ``label`` is a control name and takes ``colour``."""

    label: str
    value: str = ""
    colour: str = GREEN


@dataclass(frozen=True)
class DeckFrame:
    """One press's full response: what to send, and what the panel should show."""

    channels: Dict[int, int]
    keys: List[KeyRender]


@dataclass(frozen=True)
class DeckConfig:
    """Fixture wiring is borrowed from the tracker rather than restated, so the
    two can never disagree about which channel is the dimmer."""

    fixture: TrackerConfig = field(default_factory=TrackerConfig)
    dim_step: int = 32
    colours: Tuple[Tuple[str, int], ...] = ON_PALETTE_COLOURS
    #: Where HOME points, as a fraction of each axis's travel. Half of pan is
    #: mid-travel, which is where the head should be mounted (MOVING-HEAD.md
    #: §8.6b); half of tilt is dead vertical on this fixture (§8.6e).
    home_pan_frac: float = 0.5
    home_tilt_frac: float = 0.5

    def __post_init__(self) -> None:
        if not self.colours:
            raise ValueError("at least one colour slot is required")
        for name, value in self.colours:
            if not 0 <= value < COLOUR_AUTO_SPIN_FLOOR:
                raise ValueError(
                    f"colour {name!r} at ch5={value} is outside the selectable "
                    f"range 0..{COLOUR_AUTO_SPIN_FLOOR - 1} (140+ spins the wheel)"
                )
            for lo, hi in AMBER_BANDS:
                if lo <= value <= hi:
                    raise ValueError(
                        f"colour {name!r} at ch5={value} falls in the measured "
                        f"amber band {lo}-{hi}; amber is banned (ConceptTheme)"
                    )
        if self.dim_step <= 0:
            raise ValueError(f"dim_step must be positive, got {self.dim_step}")


def _clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


@dataclass(frozen=True)
class DeckModel:
    """Immutable. ``press`` returns a new model plus the frame it produced, so a
    caller cannot accidentally render one state while having sent another."""

    cfg: DeckConfig = field(default_factory=DeckConfig)
    dim: int = 0
    colour_index: int = 0
    pan: int = 128
    tilt: int = 128
    #: Set when the light link is failing. The panel is the operator's only
    #: feedback out here, and a confidently green panel over a dead link is
    #: worse than no panel.
    fault: bool = False

    # ---- derived -------------------------------------------------------
    @property
    def lit(self) -> bool:
        return self.dim > 0

    @property
    def colour_name(self) -> str:
        return self.cfg.colours[self.colour_index][0]

    @property
    def colour_value(self) -> int:
        return self.cfg.colours[self.colour_index][1]

    # ---- behaviour -----------------------------------------------------
    def press(self, key: int) -> "DeckModel":
        """Apply a key press. An out-of-range key is ignored rather than raised:
        this runs from a USB callback on a vehicle, and a spurious index must not
        take the service down."""
        if key == BLACKOUT:
            return replace(self, dim=0)
        if key == LAMP:
            return replace(self, dim=0 if self.lit else 255)
        if key == HOME:
            return replace(
                self,
                pan=_clamp(round(self.cfg.home_pan_frac * 255), 0, 255),
                tilt=_clamp(round(self.cfg.home_tilt_frac * 255), 0, 255),
            )
        if key == DIM_DOWN:
            return replace(self, dim=_clamp(self.dim - self.cfg.dim_step, 0, 255))
        if key == DIM_UP:
            return replace(self, dim=_clamp(self.dim + self.cfg.dim_step, 0, 255))
        if key == COLOUR:
            return replace(self, colour_index=(self.colour_index + 1) % len(self.cfg.colours))
        return self

    def channels(self) -> Dict[int, int]:
        """DMX writes for the current state.

        Channel numbers come from the fixture config, never from literals here.
        The auto-program and motor-reset channels are simply never present —
        same standing rule as the tracker, and on this fixture the reset is five
        seconds of 250+ away from a head that stops obeying anyone.
        """
        f = self.cfg.fixture
        ch: Dict[int, int] = {f.pan_channel: self.pan, f.tilt_channel: self.tilt}
        if f.pan_fine_channel:
            ch[f.pan_fine_channel] = 0
        if f.tilt_fine_channel:
            ch[f.tilt_fine_channel] = 0
        if f.colour_channel:
            ch[f.colour_channel] = self.colour_value
        if f.dimmer_channel:
            ch[f.dimmer_channel] = self.dim
        # Belt and braces on the rule that matters most: ch10 hands the head to
        # its internal programs and ch11 held at 250+ is a MOTOR RESET. If the
        # wiring above ever drifts onto one, fail loudly here rather than
        # discovering it as a head that stops obeying anyone.
        clash = set(ch) & set(f.forbidden_channels)
        if clash:
            raise ValueError(f"refusing to drive forbidden channel(s) {sorted(clash)}")
        return ch

    def render(self) -> List[KeyRender]:
        """How the six keys should look right now."""
        if self.fault:
            # The link is down: say so on the key the operator would reach for,
            # rather than rendering a calm panel over an uncontrolled head.
            return [
                KeyRender("DMX FAIL", "", RED),
                KeyRender("LAMP", "ON" if self.lit else "OFF", RED),
                KeyRender("HOME", "", GREEN),
                KeyRender("DIM -", str(self.dim), GREEN),
                KeyRender("DIM +", str(self.dim), GREEN),
                KeyRender("COLOUR", self.colour_name, GREEN),
            ]
        return [
            # A kill control that looks armed when there is nothing to kill is
            # noise; grey says pressing this would change nothing.
            KeyRender("BLACKOUT", "", RED if self.lit else GREY),
            KeyRender("LAMP", "ON" if self.lit else "OFF", GREEN),
            KeyRender("HOME", "", GREEN),
            KeyRender("DIM -", str(self.dim), GREEN),
            KeyRender("DIM +", str(self.dim), GREEN),
            KeyRender("COLOUR", self.colour_name, GREEN),
        ]

    def frame(self) -> DeckFrame:
        return DeckFrame(channels=self.channels(), keys=self.render())


def apply(model: DeckModel, key: int) -> Tuple[DeckModel, DeckFrame]:
    """Press a key and get back the new model and the frame it produced."""
    nxt = model.press(key)
    return nxt, nxt.frame()
