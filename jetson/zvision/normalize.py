"""The scalar decisions behind thermal normalisation and track association.

Both of these live inside classes that need OpenCV and numpy, which the jetson
test suite deliberately doesn't have (it is stdlib-only so it runs anywhere).
But neither *decision* needs arrays — they are small pieces of arithmetic
wrapped in array plumbing, and they are precisely where the bugs were.

Two real failures on 2026-08-07 came from this arithmetic, and neither was
catchable without hardware because neither had a test:

* smoothing the stretch **centre** as well as the scale, which left the mapping
  trailing the data and made background subtraction useless;
* applying **no floor to the gain**, so a thermally flat scene was amplified
  until sensor noise became contacts.

Pulling the arithmetic out here means both are now unit-testable, and the array
code that remains in :mod:`zvision.capture` is only plumbing.
"""

from __future__ import annotations

import math
from typing import Dict, Optional, Tuple

# Smoothing applied to the contrast scale. Deliberately only the scale — see
# :func:`stretch_window`.
DEFAULT_EMA_ALPHA = 0.05

# Per-camera track ids are namespaced into blocks of this size by the rig
# (``rig.ID_STRIDE``), which composes a global id as ``camera * STRIDE + local``.
# That composition only works if local ids stay strictly inside the block, so
# minting wraps here rather than counting up forever.
#
# Without the wrap, a long run aliases silently: local id 1000 becomes global 0,
# the "ad-hoc, never latch" sentinel — so the DMX light refuses to hold that
# person — and local id 1001 collides with a still-live id 1, letting the
# tracker believe it is holding one contact while being handed another at a
# different bearing. A night's worth of blobs passes 1000 easily.
#
# Reuse after a full lap is harmless: 999 distinct tracks have come and gone in
# between, so the original is long dead.
TRACK_ID_LIMIT = 1000

# Minimum contrast, in raw sensor counts, that the stretch will assume. Derived
# on the real Lepton: typical frame-to-frame noise ~3 counts, and keeping that
# under ~2 output levels of 255 requires a span of at least ~190.
DEFAULT_MIN_SPREAD = 200.0


def stretch_window(
    centre: float,
    spread: float,
    spread_ema: Optional[float],
    alpha: float = DEFAULT_EMA_ALPHA,
    min_spread: float = DEFAULT_MIN_SPREAD,
) -> Tuple[float, float, float]:
    """Decide the 8-bit mapping window for one thermal frame.

    Returns ``(lo, scale, new_spread_ema)`` such that a pixel is rendered as
    ``(value - lo) / (2 * scale) * 255``.

    ``centre`` is this frame's median and is used **as given, unsmoothed** —
    it is cancelling real sensor drift (including a flat-field correction
    re-baselining the whole image), so any lag leaves the mapping behind the
    data. ``spread`` is this frame's inter-percentile contrast and *is*
    smoothed, so a hot object entering cannot inflate the range and darken
    everything else.

    ``scale`` never falls below ``min_spread``: below that the scene has no
    real thermal structure and the stretch would only be magnifying noise.
    """
    if not math.isfinite(centre):
        centre = 0.0
    spread = max(spread, 1.0) if math.isfinite(spread) else 1.0

    if spread_ema is None or not math.isfinite(spread_ema):
        new_ema = spread
    else:
        new_ema = spread_ema + alpha * (spread - spread_ema)

    scale = max(new_ema, min_spread)
    return centre - scale, scale, new_ema


def assign_track_id(
    cx: float,
    cy: float,
    tracks: Dict[int, Tuple[float, float]],
    seen: Dict[int, Tuple[float, float]],
    match_dist: float,
    next_id: int,
    id_limit: int = TRACK_ID_LIMIT,
) -> Tuple[int, int]:
    """Match a blob at ``(cx, cy)`` to an existing track, or mint a new id.

    Returns ``(track_id, next_free_id)``.

    Nearest-centroid association in normalised frame coordinates. A track
    already claimed this frame (present in ``seen``) is skipped, so two blobs
    cannot collapse onto one id — which would make a single contact appear to
    teleport between two people. Nothing within ``match_dist`` means a genuinely
    new contact and a fresh id.

    Stable ids matter beyond tidiness: the collision estimator measures bearing
    *rate* per id, and the tracker light latches onto one. Churn there makes a
    stationary person look like a stream of new contacts, and makes the light
    jump between them.
    """
    best_id: Optional[int] = None
    best_d = match_dist
    for tid, (px, py) in tracks.items():
        if tid in seen:
            continue
        d = math.hypot(cx - px, cy - py)
        if d < best_d:
            best_id, best_d = tid, d
    if best_id is not None:
        return best_id, next_id
    # Wrap within [1, id_limit): 0 is reserved for "ad-hoc, not a stable track".
    following = next_id + 1
    if following >= id_limit:
        following = 1
    return next_id, following
