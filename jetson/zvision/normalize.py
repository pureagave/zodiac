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
import time
from typing import Dict, List, Optional, Set, Tuple

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


def image_rows(actual_rows: int, expected_rows: int) -> int:
    """How many rows of a thermal frame are *image*, as opposed to appended
    Lepton telemetry.

    Both 2026-08-07 outcomes live in this one comparison. The taller Y16 mode
    returns 122 rows for a 120-row sensor — the last two are telemetry, and
    left in place they are a permanent band of false motion along the bottom
    edge. But a mode that returns exactly the sensor height has no telemetry
    to crop, and the first fix cropped unconditionally, silently discarding
    two real image rows. So: crop only what exceeds the sensor height, and
    never shorten a frame that is already at or under it."""
    return expected_rows if actual_rows > expected_rows else actual_rows


def _mint_id(
    next_id: int,
    tracks: Dict[int, Tuple[float, float]],
    claimed: Set[int],
    id_limit: int = TRACK_ID_LIMIT,
) -> Tuple[int, int]:
    """Mint a fresh track id, wrapping within [1, id_limit): 0 is reserved for
    "ad-hoc, not a stable track".

    Skips any id that is still alive — in ``tracks`` from previous frames, or
    already ``claimed`` this frame. After the counter laps (a night of
    churning blobs passes 1000 easily), ``next_id`` can land on a track that
    has been held *continuously* the whole way round — say a stationary
    person the DMX light is latched onto. Handing their id to a new blob
    splices two people into one track: the collision estimator sees a
    teleporting bearing (suppressing a real constant-bearing alarm), and the
    light believes it is holding one person while being fed another.

    Returns ``(track_id, next_free_id)``.
    """
    tid = next_id
    for _ in range(id_limit):
        if tid not in tracks and tid not in claimed:
            break
        tid += 1
        if tid >= id_limit:
            tid = 1
    following = tid + 1
    if following >= id_limit:
        following = 1
    return tid, following


def associate_tracks(
    centroids: List[Tuple[float, float]],
    tracks: Dict[int, Tuple[float, float]],
    match_dist: float,
    next_id: int,
    id_limit: int = TRACK_ID_LIMIT,
) -> Tuple[List[int], int]:
    """Match every blob in this frame to an existing track, or mint a new id.

    Returns ``(ids, next_free_id)`` with ``ids`` aligned to ``centroids``.

    This is a single GLOBAL-nearest pass over the whole frame, not a per-blob
    greedy one. Every (blob, track) pair within ``match_dist`` is a candidate;
    candidates are resolved closest-first, so the physically closest pair
    always wins the match — regardless of which blob a camera's contour scan
    happens to return first. A per-blob greedy pass can let an earlier blob
    grab a track that a later blob is actually closer to, which teleports
    that track's bearing (masking a real constant-bearing collision) and
    re-mints an id for the blob it should have kept.

    Nothing within ``match_dist`` of any (still-unclaimed) track is a
    genuinely new contact and mints a fresh id, in blob order. Stable ids
    matter beyond tidiness: the collision estimator measures bearing *rate*
    per id, and the tracker light latches onto one. Churn there makes a
    stationary person look like a stream of new contacts, and makes the light
    jump between them.
    """
    candidates: List[Tuple[float, int, int]] = []
    for blob_idx, (cx, cy) in enumerate(centroids):
        for tid, (px, py) in tracks.items():
            d = math.hypot(cx - px, cy - py)
            if d < match_dist:
                candidates.append((d, blob_idx, tid))
    candidates.sort()

    resolved: Dict[int, int] = {}
    claimed: Set[int] = set()
    for _d, blob_idx, tid in candidates:
        if blob_idx in resolved or tid in claimed:
            continue
        resolved[blob_idx] = tid
        claimed.add(tid)

    ids: List[int] = []
    for blob_idx in range(len(centroids)):
        if blob_idx not in resolved:
            tid, next_id = _mint_id(next_id, tracks, claimed, id_limit)
            claimed.add(tid)
            resolved[blob_idx] = tid
        ids.append(resolved[blob_idx])

    return ids, next_id


# A flat-field correction re-baselines every pixel at once. Measured on the real
# board 2026-08-08: each FFC moved **100% of pixels** by more than 40 counts,
# stepping the frame median by 128-232 counts, roughly every 3 minutes. A
# background subtractor reads that as movement everywhere.
#
# A person is a few percent of the frame even at close range, so the two are
# nowhere near each other and the threshold does not need to be delicate.
REBASELINE_FG_FRACTION = 0.5

# MOG2 needs a few frames to absorb the new baseline; the frames immediately
# after a step still carry large residual foreground.
REBASELINE_SETTLE_FRAMES = 3


class ReBaselineGuard:
    """Drops detections across a sensor re-baseline, so an FFC is not reported
    as a scene full of people.

    This is what the overnight empty-room run was really measuring: 421,309
    frames produced 104 frames with contacts and **10 phantom collision flags**,
    arriving in short bursts rather than uniformly — the signature of a discrete
    sensor event, not noise. One false ``! BRAKE !`` every 45 minutes is exactly
    the alarm fatigue that teaches a driver to ignore the real one.

    Deliberately *suppression*, not correction: there is no way to recover what
    the scene was doing during the step, and inventing contacts is worse than
    briefly having none. The cost is a ~0.4 s blind window every few minutes,
    against a hazard that takes far longer than that to become dangerous.
    """

    def __init__(
        self,
        fg_fraction: float = REBASELINE_FG_FRACTION,
        settle_frames: int = REBASELINE_SETTLE_FRAMES,
    ) -> None:
        self._fg_fraction = fg_fraction
        self._settle_frames = settle_frames
        self._settling = 0

    def suppress(self, fg_fraction: float) -> bool:
        """True when this frame's detections should be discarded.

        ``fg_fraction`` is the share of the frame the background subtractor
        called foreground, which is free — the mask is already computed.
        """
        if fg_fraction != fg_fraction:  # NaN, from an empty or corrupt frame
            return True
        if fg_fraction >= self._fg_fraction:
            self._settling = self._settle_frames
            return True
        if self._settling > 0:
            self._settling -= 1
            return True
        return False

    @property
    def settling(self) -> bool:
        """Whether the guard is still riding out a step — for logging."""
        return self._settling > 0


# A V4L2 handle opened before its USB device is streaming-ready wedges in
# ``select() timeout`` forever and never self-heals. Measured 2026-08-13, the
# Jetson cold-boot race: zvision came up at boot with the thermal blind and
# **stayed blind for two hours**, throttling the whole (synchronous) rig loop and
# emitting false all-clear, while a fresh open of the exact same device streamed
# instantly. Reopening the handle once the device is ready is the recovery.
DEFAULT_STALL_SECS = 6.0

# How recently a camera must have delivered a frame to still count as *covering*
# its arc for blind-arc signalling (RES-P2-1). A camera silent for longer than
# this is treated as blind — its arc goes onto the wire as unwatched, not
# all-clear. 3.0 s meets the spec's "<= ~3 s" target and sits under the stall
# guard's 6 s reopen interval; the 2 s READ_TIMEOUT_MS in capture.py keeps the
# synchronous loop ticking, so a wedged camera still trips this bound. It must
# stay well above the thermal's worst honest frame gap (~111 ms at 9 fps plus FFC
# settle) or a healthy camera would flicker blind.
LIVE_WINDOW_SECS = 3.0


class CameraStallGuard:
    """Decides when a camera that has stopped delivering frames should be
    reopened. Pure — no cv2/numpy — on an injectable clock, so the reopen cadence
    is unit-tested without hardware; the array plumbing that actually reopens the
    handle stays in :mod:`zvision.capture`.

    :meth:`note` is called once per frame read with whether a frame arrived and
    returns ``True`` when the caller should reopen. A healthy camera (a frame
    within ``stall_secs``) never reopens. A stalled one reopens at most once per
    ``stall_secs`` — resetting the clock on a reopen *is* the backoff, so a
    genuinely dead camera is retried on a fixed cadence rather than every frame.
    A reopen is never triggered on the very first observation: a healthy camera's
    first frame can lag its warm-up, and reopening across that would be churn.

    :meth:`delivering` answers the separate liveness question the blind-arc
    signal needs: has a frame actually arrived within a window? It reads
    ``_last_frame``, which advances **only** on a real frame — deliberately
    distinct from ``_last_ok``, which :meth:`note` resets on every reopen
    backoff. Reading ``_last_ok`` for liveness would make a genuinely dead
    camera look alive for ``stall_secs`` after each reopen attempt, which is the
    exact "false all-clear" this signalling exists to kill.
    """

    def __init__(self, stall_secs: float = DEFAULT_STALL_SECS, clock=None) -> None:
        self._stall = stall_secs
        self._clock = clock or time.monotonic
        self._last_ok: Optional[float] = None
        # Advances ONLY on a delivered frame — never on a reopen. See
        # :meth:`delivering`; do not fold this back onto ``_last_ok``.
        self._last_frame: Optional[float] = None

    def note(self, frame_ok: bool) -> bool:
        now = self._clock()
        if frame_ok:
            self._last_ok = now
            self._last_frame = now
            return False
        if self._last_ok is None:
            # First-ever observation is a failure: start the clock, grace the
            # warm-up, don't reopen yet.
            self._last_ok = now
            return False
        if now - self._last_ok >= self._stall:
            self._last_ok = now  # backoff: the next reopen is another stall away
            return True
        return False

    def delivering(self, window_secs: float = LIVE_WINDOW_SECS) -> bool:
        """True iff a frame was delivered within ``window_secs``.

        A camera that has never delivered is not delivering. A camera whose last
        real frame is older than the window is not delivering, no matter how
        recently a reopen fired — that is the whole point of tracking
        ``_last_frame`` separately from ``_last_ok``."""
        if self._last_frame is None:
            return False
        return self._clock() - self._last_frame <= window_secs
