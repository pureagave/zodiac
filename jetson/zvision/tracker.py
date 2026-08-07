"""Point a moving-head light at a detected contact.

The tracker is a downstream consumer of the *same* ``DriverThreat`` list that the
HUD broadcaster already gets each frame (see ``app.py``) — the Jetson has already
computed every contact's bearing, so aiming a light is just mapping that azimuth
(and a size→elevation proxy) onto the fixture's pan/tilt DMX channels.

Pure math + a little state machine, no DMX/OLA deps — the transmit side lives in
``dmx.py`` behind a ``DmxSink`` Protocol, mirroring how ``detector.py`` keeps cv2
out of the fake path. That split lets the whole select→map→slew pipeline be
proven with ``--dmx fake`` before the real dongle and fixture ever arrive.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Dict, List, Optional

from .threat import DriverThreat

if TYPE_CHECKING:  # runtime-free: keep the socket-backed audio_bus out of this pure module
    from .audio_bus import AudioLevel

DMX_MAX = 255
FULL_TURN_DEG = 360.0
DMX16_MAX = 65535


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def _lerp(a: float, b: float, frac: float) -> float:
    return a + (b - a) * frac


def _approach(current: float, target: float, max_step: float) -> float:
    """Move ``current`` toward ``target`` by at most ``max_step`` (>=0). A slew
    limiter: this is what keeps the head *following* smoothly instead of snapping,
    which matters for a light people are watching. ``max_step`` <= 0 holds
    position; a huge step (``--once``) snaps straight to target."""
    if max_step <= 0:
        return current
    delta = target - current
    if abs(delta) <= max_step:
        return target
    return current + (max_step if delta > 0 else -max_step)


def deg_to_dmx16(deg: float, range_deg: float) -> tuple:
    """Fixture angle -> (coarse, fine) DMX bytes across its full mechanical range.
    16-bit so a slow tracker slews smoothly; callers drop ``fine`` for 8-bit-only
    fixtures. ``range_deg`` <= 0 is treated as a dead axis (parks at 0). A
    non-finite ``deg`` (the park sentinel is NaN, and ``round(nan)`` raises)
    also parks at 0 rather than crashing the frame."""
    if range_deg <= 0 or not math.isfinite(deg):
        return 0, 0
    v = round(_clamp(deg / range_deg, 0.0, 1.0) * DMX16_MAX)
    return (v >> 8) & 0xFF, v & 0xFF



def nearest_equivalent_pan(
    target_deg: float,
    current_deg: float,
    pan_range_deg: float,
) -> float:
    """Pick the mechanically-equivalent pan angle closest to where the head is.

    A fixture with more than 360 degrees of travel can reach the same physical
    direction at more than one pan value — on a 540 head, 89 and 449 point the
    same way. Naively mapping bearing to pan therefore makes the *stern seam*
    catastrophic: a contact walking across dead astern goes +179 to -179, two
    degrees of real motion, but pan jumps 449 to 91 and the slew limiter drags
    the beam 358 degrees the wrong way round the vehicle — several seconds
    sweeping across everyone (and the driver) to arrive two degrees from where
    it started, with the light pointing anywhere but at the contact.

    So consider the +/-360 equivalents too and take whichever is nearest the
    current position, provided it is inside the fixture's travel.
    """
    best = target_deg
    for candidate in (target_deg - FULL_TURN_DEG, target_deg, target_deg + FULL_TURN_DEG):
        if 0.0 <= candidate <= pan_range_deg and abs(candidate - current_deg) < abs(best - current_deg):
            best = candidate
    return best


def select_best(threats: List[DriverThreat]) -> Optional[DriverThreat]:
    """Stateless pick: a collision contact always wins (that's the one the driver
    most needs lit), otherwise the nearest by ``size``. ``None`` when the scene is
    empty. The stateful :class:`Tracker` adds hysteresis on top of this."""
    if not threats:
        return None
    collisions = [t for t in threats if t.collision]
    pool = collisions or threats
    return max(pool, key=lambda t: t.size)


@dataclass(frozen=True)
class TrackerConfig:
    """Fixture wiring + az→pan/size→tilt calibration + slew limits.

    Defaults describe a generic 540°/270° moving head with 16-bit pan/tilt and a
    master dimmer on the first four channels. Calibrate ``pan_center_deg`` /
    ``pan_gain`` once on the vehicle (aim it dead-ahead, then at a known bearing)."""

    # DMX channels, 1-based. Set a *_fine to None for an 8-bit-only fixture, and
    # dimmer_channel to None if the head has no master dimmer.
    pan_channel: int = 1
    pan_fine_channel: Optional[int] = 2
    tilt_channel: int = 3
    tilt_fine_channel: Optional[int] = 4
    dimmer_channel: Optional[int] = 5

    # Mechanical spans that DMX full-scale covers.
    pan_range_deg: float = 540.0
    tilt_range_deg: float = 270.0

    # az (deg, +right) -> pan (fixture deg): pan = center + az*gain, then clamped.
    # gain ~1.0 when the head is mounted axis-aligned with the camera; negate to
    # flip a mirror-imaged fixture.
    pan_center_deg: float = 270.0
    pan_gain: float = 1.0

    # size (0 far .. 1 near) -> tilt (fixture deg). An elevated head tilts further
    # down for a closer contact; a coarse range→elevation proxy until we have real
    # vertical from the detector. Equal far/near = a fixed tilt.
    tilt_far_deg: float = 135.0
    tilt_near_deg: float = 160.0

    # Slew ceilings (fixture deg/s) — the head never moves faster than these.
    pan_slew_dps: float = 120.0
    tilt_slew_dps: float = 90.0

    # Dimmer levels (0..255). Collision can burn brighter/strobe on a separate
    # channel later; for now it just pins the master to full.
    dimmer_track: int = 255
    dimmer_collision: int = 255
    dimmer_idle: int = 0

    # Sound-reactive light show when idle (no target): the beacon's $ZAUD rms
    # drives the master dimmer and a detected beat flashes it, so the head pulses
    # to the music between detections. A live target always overrides this with
    # the full track dimmer — reactivity never compromises tracking.
    sound_reactive: bool = True
    sound_gain: float = 4.0 # rms(0..1) * gain -> brightness fraction, then clamped
    sound_silence: float = 0.03 # below this scaled level, stay dark (true quiet)
    beat_dimmer: int = 255 # flash to this on a detected beat

    # Hysteresis: while following a contact, only switch to a different one when
    # it's at least this much nearer (by size). Stops the head ping-ponging
    # between two contacts at similar range. Collisions bypass this.
    switch_margin: float = 0.15

    # Where the head rests (and blacks out) with nothing to track. Defaults derive
    # from the aim center / far tilt in __post_init__ when left at NaN sentinels.
    park_pan_deg: float = float("nan")
    park_tilt_deg: float = float("nan")


@dataclass(frozen=True)
class TrackerFrame:
    """One update's result: the DMX channel writes plus the human-readable aim,
    so the runner can log what the light is doing (and tests can assert on it)."""

    channels: Dict[int, int]
    target_id: Optional[int]
    pan_deg: float
    tilt_deg: float
    dimmer: int


@dataclass
class Tracker:
    """Stateful follower: pick a contact (with hysteresis), slew pan/tilt toward
    its mapped position, drive the dimmer. Call :meth:`update` once per frame."""

    cfg: TrackerConfig = field(default_factory=TrackerConfig)
    _pan_deg: float = field(init=False)
    _tilt_deg: float = field(init=False)
    _dimmer: int = field(init=False, default=0)
    _current_id: Optional[int] = field(init=False, default=None)

    def __post_init__(self) -> None:
        park_pan = self.cfg.park_pan_deg
        park_tilt = self.cfg.park_tilt_deg
        self._pan_deg = self.cfg.pan_center_deg if park_pan != park_pan else park_pan
        self._tilt_deg = self.cfg.tilt_far_deg if park_tilt != park_tilt else park_tilt

    def update(
        self,
        threats: List[DriverThreat],
        dt: float,
        audio: "Optional[AudioLevel]" = None,
    ) -> TrackerFrame:
        target = self._pick(threats)
        if target is None:
            # Nobody to follow: hold aim (a random idle sweep would read as a
            # false detection). Pulse to the music if we have a sound feed,
            # otherwise black out the head.
            self._dimmer = (
                self._sound_dimmer(audio)
                if audio is not None and self.cfg.sound_reactive
                else self.cfg.dimmer_idle
            )
        else:
            tgt_pan = _clamp(
                self.cfg.pan_center_deg + target.rel_az_deg * self.cfg.pan_gain,
                0.0,
                self.cfg.pan_range_deg,
            )
            # Take the short way round the stern seam — see nearest_equivalent_pan.
            tgt_pan = nearest_equivalent_pan(tgt_pan, self._pan_deg, self.cfg.pan_range_deg)
            tgt_tilt = _clamp(
                _lerp(self.cfg.tilt_far_deg, self.cfg.tilt_near_deg, _clamp(target.size, 0.0, 1.0)),
                0.0,
                self.cfg.tilt_range_deg,
            )
            self._pan_deg = _approach(self._pan_deg, tgt_pan, self.cfg.pan_slew_dps * dt)
            self._tilt_deg = _approach(self._tilt_deg, tgt_tilt, self.cfg.tilt_slew_dps * dt)
            self._dimmer = self.cfg.dimmer_collision if target.collision else self.cfg.dimmer_track
        return self._frame(target.id if target else None)

    def park(self) -> TrackerFrame:
        """Snap to the rest position and black out — for a clean shutdown so the
        head doesn't freeze mid-sky pointing at the last contact."""
        park_pan = self.cfg.park_pan_deg
        park_tilt = self.cfg.park_tilt_deg
        self._pan_deg = self.cfg.pan_center_deg if park_pan != park_pan else park_pan
        self._tilt_deg = self.cfg.tilt_far_deg if park_tilt != park_tilt else park_tilt
        self._dimmer = self.cfg.dimmer_idle
        self._current_id = None
        return self._frame(None)

    def _sound_dimmer(self, audio: "AudioLevel") -> int:
        """Master-dimmer level for the idle sound show: a detected beat flashes to
        [beat_dimmer]; otherwise rms scales the brightness, going fully dark below
        the silence floor so a quiet room isn't a dimly-lit head."""
        if audio.beat:
            return self.cfg.beat_dimmer
        level = audio.rms * self.cfg.sound_gain
        if level < self.cfg.sound_silence:
            return 0
        return int(_clamp(level, 0.0, 1.0) * DMX_MAX)

    def _pick(self, threats: List[DriverThreat]) -> Optional[DriverThreat]:
        if not threats:
            self._current_id = None
            return None
        collisions = [t for t in threats if t.collision]
        if collisions:
            best = max(collisions, key=lambda t: t.size)
            self._current_id = best.id
            return best
        best = max(threats, key=lambda t: t.size)
        # Stick to the contact we're already on unless a different one is clearly
        # nearer. Ad-hoc id 0 contacts never latch (they aren't stable ids).
        held = next((t for t in threats if self._current_id and t.id == self._current_id), None)
        if held is not None and best.id != held.id and best.size < held.size + self.cfg.switch_margin:
            best = held
        self._current_id = best.id
        return best

    def _frame(self, target_id: Optional[int]) -> TrackerFrame:
        ch: Dict[int, int] = {}
        pc, pf = deg_to_dmx16(self._pan_deg, self.cfg.pan_range_deg)
        ch[self.cfg.pan_channel] = pc
        if self.cfg.pan_fine_channel:
            ch[self.cfg.pan_fine_channel] = pf
        tc, tf = deg_to_dmx16(self._tilt_deg, self.cfg.tilt_range_deg)
        ch[self.cfg.tilt_channel] = tc
        if self.cfg.tilt_fine_channel:
            ch[self.cfg.tilt_fine_channel] = tf
        dimmer = int(_clamp(self._dimmer, 0, DMX_MAX))
        if self.cfg.dimmer_channel:
            ch[self.cfg.dimmer_channel] = dimmer
        return TrackerFrame(
            channels=ch,
            target_id=target_id,
            pan_deg=self._pan_deg,
            tilt_deg=self._tilt_deg,
            dimmer=dimmer,
        )
