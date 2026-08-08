"""Turn pixel-space detections into the vehicle-relative terms the HUD wants,
plus the constant-bearing collision rule. Pure math, no camera/model deps."""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, Tuple

# Lens projection models: how a ray at angle t off the optical axis lands at
# image radius r (in units where the horizontal half-width is r=1).
LENS_RECTILINEAR = "rectilinear"   # r = tan(t)      — ordinary lens (the RGB IMX462s)
LENS_EQUIDISTANT = "equidistant"   # r = t           — the standard fisheye (f-theta) fit
LENS_EQUISOLID = "equisolid"       # r = 2 sin(t/2)  — many wide "full-frame" fisheyes
LENS_LINEAR = "linear"             # az = (cx-0.5)*hfov, no y coupling — the legacy map
LENS_MODELS = (LENS_RECTILINEAR, LENS_EQUIDISTANT, LENS_EQUISOLID, LENS_LINEAR)

# Which frame dimension the quoted FOV number describes. Datasheets are
# inconsistent about this and it matters a lot on a wide lens: 160° *horizontal*
# across a square sensor implies a ~226° diagonal (physically impossible), while
# 160° *diagonal* on the same sensor is only ~113° horizontal.
FOV_HORIZONTAL = "h"
FOV_DIAGONAL = "d"
FOV_REFS = (FOV_HORIZONTAL, FOV_DIAGONAL)

# tan() explodes at 90°: a rectilinear lens can never actually reach a 180° FOV,
# so clamp the configured value rather than emitting infinities.
_MAX_RECTILINEAR_HFOV = 179.0


def wrap180(delta: float) -> float:
    """Fold an angle into (-180, 180] — the same range ``atan2`` yields, so a
    rear-mounted camera's dead-centre contact lands on a stable +180 (dead
    astern) instead of flipping to -180. Used both for az-rate (shortest signed
    difference) and to keep merged full-circle bearings canonical."""
    wrapped = ((delta + 180.0) % 360.0) - 180.0
    return 180.0 if wrapped == -180.0 else wrapped


def _wrap180(delta: float) -> float:  # back-compat alias for the older name
    return wrap180(delta)


def _theta_from_radius(r: float, half_fov_rad: float, lens: str) -> float:
    """Invert the projection: image radius (1 == the horizontal half-FOV edge)
    back to the ray's angle off the optical axis, in radians."""
    if lens == LENS_RECTILINEAR:
        return math.atan(r * math.tan(half_fov_rad))
    if lens == LENS_EQUISOLID:
        return 2.0 * math.asin(max(-1.0, min(1.0, r * math.sin(half_fov_rad / 2.0))))
    return r * half_fov_rad  # equidistant (f-theta)


def pixel_to_bearing(
    cx_norm: float,
    cy_norm: float = 0.5,
    fov_deg: float = 160.0,
    aspect: float = 1.0,
    lens: str = LENS_EQUIDISTANT,
    fov_ref: str = FOV_HORIZONTAL,
) -> Tuple[float, float]:
    """Unproject a normalised image point to (azimuth, elevation) in degrees off
    the camera's optical axis — azimuth +right, elevation +up.

    ``aspect`` is frame height / width, so square pixels stay square: the radial
    projection is isotropic, and a point's *vertical* offset changes its azimuth
    too. That coupling is the part the old flat ``(cx - 0.5) * hfov`` map got
    wrong on a wide lens — near the corners of a 160° fisheye a contact's true
    bearing is nothing like what the flat map reports, because part of its
    off-axis angle is vertical. (Along the horizontal centreline an equidistant
    fisheye *is* linear in x, which is why the flat map survived on the narrow
    forward Lepton; the real errors are off-centreline contacts, rectilinear
    lenses, and — the big one — running a 160° lens with ``fov_deg=57``.)

    ``fov_ref`` says whether ``fov_deg`` is measured across the frame width or
    its diagonal; on a wide lens over a near-square sensor the two differ by
    tens of degrees.
    """
    if not (math.isfinite(cx_norm) and math.isfinite(cy_norm)):
        return 0.0, 0.0
    if not math.isfinite(fov_deg) or fov_deg <= 0.0:
        return 0.0, 0.0
    if lens == LENS_LINEAR:
        return (cx_norm - 0.5) * fov_deg, 0.0
    if lens == LENS_RECTILINEAR:
        fov_deg = min(fov_deg, _MAX_RECTILINEAR_HFOV)

    # Image-plane coords in half-width units: u right, v down.
    if not (math.isfinite(aspect) and aspect > 0):
        aspect = 1.0
    u = (cx_norm - 0.5) * 2.0
    v = (cy_norm - 0.5) * 2.0 * aspect
    r = math.hypot(u, v)
    if r == 0.0:
        return 0.0, 0.0
    # r is in half-width units; normalise so r_edge == 1 at whichever frame
    # dimension the quoted FOV refers to.
    r_edge = math.hypot(1.0, aspect) if fov_ref == FOV_DIAGONAL else 1.0
    theta = min(math.pi, _theta_from_radius(r / r_edge, math.radians(fov_deg) / 2.0, lens))
    # Ray direction in camera frame: +x right, +y down, +z forward.
    scale = math.sin(theta) / r
    dx, dy, dz = u * scale, v * scale, math.cos(theta)
    az = math.degrees(math.atan2(dx, dz))
    el = math.degrees(math.atan2(-dy, math.hypot(dx, dz)))
    return az, el


def bbox_to_rel_az(
    cx_norm: float,
    fov_deg: float,
    cy_norm: float = 0.5,
    aspect: float = 1.0,
    lens: str = LENS_EQUIDISTANT,
    fov_ref: str = FOV_HORIZONTAL,
) -> float:
    """Bearing off the optical axis for a detection centred at ``cx_norm`` (0..1
    across the frame). Thin wrapper over :func:`pixel_to_bearing` for callers
    that only need azimuth."""
    return pixel_to_bearing(cx_norm, cy_norm, fov_deg, aspect, lens, fov_ref)[0]


def bbox_height_to_size(h_norm: float, far_h: float = 0.05, near_h: float = 0.9) -> float:
    """Proxy range from a person's bbox height as a fraction of frame height: a
    tall box (close) -> ~1, a tiny box (far) -> ~0. Linear between the calibrated
    far/near heights, clamped to [0, 1]."""
    # `not (a > b)` rather than `a <= b`: they differ exactly on NaN, and a NaN
    # calibration used to slip past this guard and make every contact size 1.0
    # (min/max with NaN silently picks the limit) — reported touching the car.
    if not (near_h > far_h):
        return 0.0
    frac = (h_norm - far_h) / (near_h - far_h)
    if not math.isfinite(frac):
        return 0.0
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
        if prev is None:
            self._tracks[tid] = _Track(az, size, t)
            return False
        dt = t - prev.t
        if dt <= 0:
            # Out-of-order or duplicate frame: ignore it and KEEP the baseline,
            # so a replayed sample can't poison the next legitimate delta.
            return False
        self._tracks[tid] = _Track(az, size, t)
        az_rate = abs(wrap180(az - prev.az)) / dt
        closing = (size - prev.size) > self.closing_eps
        return size >= self.min_size and az_rate <= self.az_rate_thresh_dps and closing

    def forget(self, tid: int) -> None:
        self._tracks.pop(tid, None)
