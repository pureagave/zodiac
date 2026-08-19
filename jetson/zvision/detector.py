"""Detectors turn the current sensor frame(s) into a list of contacts.

``FakeDetector`` needs no hardware (pure stdlib) and stands in until the camera
arrives. ``MotionDetector`` runs on a real UVC camera the moment it's plugged in
— background-subtraction blobs, no trained model required for bring-up. The
trained thermal/RGB model drops in behind the same ``detect`` signature later.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List, Optional, Protocol

from .geometry import (
    FOV_DIAGONAL,
    LENS_EQUIDISTANT,
    CollisionEstimator,
    bbox_height_to_size,
    pixel_to_bearing,
)
from .normalize import LIVE_WINDOW_SECS, ReBaselineGuard, associate_tracks
from .threat import DriverThreat

# A missed blob (a frame where the background subtractor just doesn't find
# it) does not by itself mean the person is gone. Carrying a track for a
# couple of frames past its last sighting means a reappearing blob re-inherits
# its id instead of re-minting one, and the collision estimator's baseline
# survives the gap instead of restarting as a first-sighting. It is
# Jetson-internal bookkeeping only: an unseen track is never emitted as a
# contact, so nothing is coasted onto the driver's HUD.
PRUNE_GRACE_FRAMES = 2


@dataclass(frozen=True)
class DetectorTuning:
    """The numbers you can only get right in the field.

    Every one of these depends on how the camera actually ends up mounted and
    what it's actually looking at — how high off the ground, how far away people
    walk, how much contrast a body has against 100 °F playa at 2am. None of it
    is knowable from a bench. They are grouped here, and surfaced all the way out
    to the ``--camera`` spec, so tuning on-site is one config line and a service
    restart rather than editing Python by headlamp."""

    # Blob must cover this fraction of the frame to count. Raise it if dust,
    # heat shimmer or flapping shade-cloth are generating phantom contacts;
    # lower it if real people are being missed at distance.
    min_area_frac: float = 0.004

    # Nearest-centroid track association, in normalised frame widths. Raise it
    # if ids churn as people walk; lower it if two people passing swap ids.
    match_dist: float = 0.15

    # bbox height (as a fraction of frame height) -> the 0..1 "size" the HUD
    # draws and the tracker aims by. This is the range proxy, and it is pure
    # guesswork until real bodies stand at real distances in front of the real
    # mount. Expect to change these first.
    far_h: float = 0.05
    near_h: float = 0.9

    # Constant-bearing collision rule. Lower az-rate = stricter (fewer, more
    # confident alarms); min_size sets how close a contact must be before it can
    # trip at all. These decide how twitchy "! BRAKE !" is on the driver's HUD.
    collision_az_rate_dps: float = 3.0
    collision_min_size: float = 0.35


class Detector(Protocol):
    def detect(self, t: float) -> List[DriverThreat]:
        """Contacts for time ``t`` (monotonic seconds since start)."""
        ...

    def delivering(self, window_secs: float = LIVE_WINDOW_SECS) -> bool:
        """Whether this detector's camera is currently delivering frames, so
        the rig can tell a covering camera from a blind one (RES-P2-1). A
        detector with no real camera (the fake) is always delivering."""
        ...

    def close(self) -> None:
        ...


class FakeDetector:
    """Deterministic synthetic contacts as a function of wall-clock seconds — the
    Python mirror of the tablet's ``FakeThreatSource.demo``. Three contacts:
    one sweeping across the distance, one parked mid-range to the left, and one
    dead ahead closing on a constant bearing that trips the collision flag once
    it's near enough. Lets you watch the HUD come alive before any camera exists.
    """

    COLLISION_SIZE = 0.55

    def detect(self, t: float) -> List[DriverThreat]:
        sweep_az = 40.0 * math.sin(t * 0.5)
        far_sweeper = DriverThreat(rel_az_deg=sweep_az, size=0.25, collision=False, id=1)
        parked = DriverThreat(rel_az_deg=-22.0, size=0.45, collision=False, id=2)
        closing = 0.2 + (t * 0.05) % 0.8  # ramps 0.2..1.0 then wraps
        incoming = DriverThreat(
            rel_az_deg=3.0,
            size=closing,
            collision=closing >= self.COLLISION_SIZE,
            id=3,
        )
        return [far_sweeper, parked, incoming]

    def delivering(self, window_secs: float = LIVE_WINDOW_SECS) -> bool:
        # No hardware to stall: the synthetic source always "sees" its arc.
        return True

    def close(self) -> None:
        pass


class MotionDetector:
    """Camera-backed bring-up detector. Background subtraction -> contours ->
    bounding boxes -> vehicle-relative contacts, with a nearest-centroid tracker
    for stable ids and the constant-bearing collision rule. Crude but *real*:
    plug in a UVC camera and moving warm bodies show up on the HUD with no
    trained model. Requires cv2 (imported lazily so ``FakeDetector`` stays
    dependency-free)."""

    def __init__(
        self,
        camera,
        fov_deg: float = 160.0,
        lens: str = LENS_EQUIDISTANT,
        fov_ref: str = FOV_DIAGONAL,
        tuning: Optional[DetectorTuning] = None,
        recorder=None,
        name: str = "cam",
    ) -> None:
        import cv2  # local import: only the real path needs OpenCV

        self._cv2 = cv2
        self._camera = camera
        self._recorder = recorder
        self._name = name
        self._fov = fov_deg
        self._lens = lens
        self._fov_ref = fov_ref
        self._tuning = tuning or DetectorTuning()
        self._min_area_frac = self._tuning.min_area_frac
        self._match_dist = self._tuning.match_dist
        self._bg = cv2.createBackgroundSubtractorMOG2(detectShadows=False)
        self._rebaseline = ReBaselineGuard()
        self._collision = CollisionEstimator(
            az_rate_thresh_dps=self._tuning.collision_az_rate_dps,
            min_size=self._tuning.collision_min_size,
        )
        self._next_id = 1
        # id -> (cx_norm, cy_norm) of last sighting, for nearest-centroid matching.
        # Includes tracks carried through a short unseen grace (see
        # PRUNE_GRACE_FRAMES) so a reappearing blob re-matches instead of
        # re-minting.
        self._tracks: dict[int, tuple[float, float]] = {}
        # id -> consecutive frames missed, for the prune grace. Absent means 0.
        self._misses: dict[int, int] = {}
        self._prune_grace_frames = PRUNE_GRACE_FRAMES

    def detect(self, t: float) -> List[DriverThreat]:
        cv2 = self._cv2
        frame = self._camera.read()
        if frame is None:
            return []
        h, w = frame.shape[:2]
        mask = self._bg.apply(frame)
        _, mask = cv2.threshold(mask, 200, 255, cv2.THRESH_BINARY)
        # A flat-field correction steps every pixel at once and the subtractor
        # calls the whole frame foreground. Report nothing rather than a scene
        # full of people — see ReBaselineGuard.
        if self._rebaseline.suppress(float(mask.mean()) / 255.0):
            return []
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, self._kernel())
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        min_area = self._min_area_frac * w * h
        aspect = h / w if w else 1.0

        # Pass 1: every blob that clears the area gate, in contour order.
        blobs: List[tuple] = []  # (rect, cx_norm, cy_norm)
        for c in contours:
            if cv2.contourArea(c) < min_area:
                continue
            x, y, bw, bh = cv2.boundingRect(c)
            cx_norm = (x + bw / 2.0) / w
            cy_norm = (y + bh / 2.0) / h
            blobs.append(((x, y, bw, bh), cx_norm, cy_norm))

        # One global-nearest association pass for the whole frame at once —
        # see zvision.normalize.associate_tracks for why this must not be a
        # per-blob greedy loop.
        centroids = [(cx, cy) for _rect, cx, cy in blobs]
        ids, self._next_id = associate_tracks(
            centroids, self._tracks, self._match_dist, self._next_id
        )

        # Pass 2: contacts, and the per-track state (collision baseline, last
        # centroid) that only a resolved id makes meaningful.
        seen: dict[int, tuple[float, float]] = {}
        out: List[DriverThreat] = []
        rects: List[tuple] = []
        for (rect, cx_norm, cy_norm), tid in zip(blobs, ids):
            rects.append(rect)
            seen[tid] = (cx_norm, cy_norm)
            bh = rect[3]
            # Aim at the contact's feet-to-head centre through the real lens
            # model: on a wide fisheye the vertical offset shifts azimuth too.
            az, _el = pixel_to_bearing(
                cx_norm, cy_norm, self._fov, aspect, self._lens, self._fov_ref
            )
            h_norm = bh / h
            size = bbox_height_to_size(h_norm, self._tuning.far_h, self._tuning.near_h)
            # size is the clamped [0, 1] display/aim value; h_norm is the raw,
            # unclamped closing signal — size saturates at near_h while a
            # person can keep filling more of the frame right up to the car.
            collision = self._collision.update(tid, az, size, t, range_proxy=h_norm)
            out.append(DriverThreat(rel_az_deg=az, size=size, collision=collision, id=tid))

        # Carry tracks unseen this frame for a short grace instead of pruning
        # them immediately: a single missed blob must not re-mint the id or
        # wipe the collision baseline on the very next detection. Unseen
        # tracks never appear in `out`, so nothing is coasted onto the HUD.
        next_tracks = dict(seen)
        misses: dict[int, int] = {}
        for tid, pos in self._tracks.items():
            if tid in seen:
                continue
            count = self._misses.get(tid, 0) + 1
            if count <= self._prune_grace_frames:
                next_tracks[tid] = pos
                misses[tid] = count
            else:
                self._collision.forget(tid)
        self._tracks = next_tracks
        self._misses = misses

        # Frame + its pixel boxes together, which is what an annotator needs.
        # The recorder swallows its own failures; detection never depends on it.
        if self._recorder is not None:
            self._recorder.record(self._name, t, frame, rects)
        return out

    def _kernel(self):
        return self._cv2.getStructuringElement(self._cv2.MORPH_ELLIPSE, (5, 5))

    def delivering(self, window_secs: float = LIVE_WINDOW_SECS) -> bool:
        # Delegate to the camera's own frame-delivery signal. A camera that
        # predates this method (or a test stub without one) is assumed to be
        # delivering, so a missing signal never silently blinds a real arc.
        query = getattr(self._camera, "delivering", None)
        return query(window_secs) if callable(query) else True

    def close(self) -> None:
        closer = getattr(self._camera, "close", None)
        if closer:
            closer()


# Keep the optional-return import referenced for type-checkers without requiring
# it at runtime on the stdlib-only path.
_ = Optional
