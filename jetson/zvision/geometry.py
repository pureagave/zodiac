"""Turn pixel-space detections into the vehicle-relative terms the HUD wants,
plus the constant-bearing collision rule. Pure math, no camera/model deps."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict


def bbox_to_rel_az(cx_norm: float, hfov_deg: float) -> float:
    """Map a detection's horizontal centre (0..1 across the frame) to bearing off
    the nose. Centre -> 0, left edge -> -hfov/2, right edge -> +hfov/2."""
    return (cx_norm - 0.5) * hfov_deg


def bbox_height_to_size(h_norm: float, far_h: float = 0.05, near_h: float = 0.9) -> float:
    """Proxy range from a person's bbox height as a fraction of frame height: a
    tall box (close) -> ~1, a tiny box (far) -> ~0. Linear between the calibrated
    far/near heights, clamped to [0, 1]."""
    if near_h <= far_h:
        return 0.0
    frac = (h_norm - far_h) / (near_h - far_h)
    return max(0.0, min(1.0, frac))


@dataclass
class _Track:
    az: float
    size: float
    t: float


@dataclass
class CollisionEstimator:
    """Flags a contact on the classic *constant bearing, decreasing range* rule:
    if a contact's bearing barely changes while it grows (closes), you're on an
    intercept course. Stateful per track id; call :meth:`update` once per contact
    per frame."""

    az_rate_thresh_dps: float = 3.0  # |d(az)/dt| below this reads as constant bearing
    min_size: float = 0.35           # ignore distant contacts entirely
    closing_eps: float = 0.0         # size must be strictly increasing to count
    _tracks: Dict[int, _Track] = field(default_factory=dict)

    def update(self, tid: int, az: float, size: float, t: float) -> bool:
        prev = self._tracks.get(tid)
        self._tracks[tid] = _Track(az, size, t)
        if prev is None:
            return False
        dt = t - prev.t
        if dt <= 0:
            return False
        az_rate = abs(az - prev.az) / dt
        closing = (size - prev.size) > self.closing_eps
        return size >= self.min_size and az_rate <= self.az_rate_thresh_dps and closing

    def forget(self, tid: int) -> None:
        self._tracks.pop(tid, None)
