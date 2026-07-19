"""Detectors turn the current sensor frame(s) into a list of contacts.

``FakeDetector`` needs no hardware (pure stdlib) and stands in until the camera
arrives. ``MotionDetector`` runs on a real UVC camera the moment it's plugged in
— background-subtraction blobs, no trained model required for bring-up. The
trained thermal/RGB model drops in behind the same ``detect`` signature later.
"""

from __future__ import annotations

import math
from typing import List, Optional, Protocol

from .geometry import CollisionEstimator, bbox_height_to_size, bbox_to_rel_az
from .threat import DriverThreat


class Detector(Protocol):
    def detect(self, t: float) -> List[DriverThreat]:
        """Contacts for time ``t`` (monotonic seconds since start)."""
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
        hfov_deg: float = 57.0,
        min_area_frac: float = 0.004,
        match_dist: float = 0.15,
    ) -> None:
        import cv2  # local import: only the real path needs OpenCV

        self._cv2 = cv2
        self._camera = camera
        self._hfov = hfov_deg
        self._min_area_frac = min_area_frac
        self._match_dist = match_dist
        self._bg = cv2.createBackgroundSubtractorMOG2(detectShadows=False)
        self._collision = CollisionEstimator()
        self._next_id = 1
        # id -> (cx_norm, cy_norm) of last sighting, for nearest-centroid matching
        self._tracks: dict[int, tuple[float, float]] = {}

    def detect(self, t: float) -> List[DriverThreat]:
        cv2 = self._cv2
        frame = self._camera.read()
        if frame is None:
            return []
        h, w = frame.shape[:2]
        mask = self._bg.apply(frame)
        _, mask = cv2.threshold(mask, 200, 255, cv2.THRESH_BINARY)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, self._kernel())
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        min_area = self._min_area_frac * w * h
        seen: dict[int, tuple[float, float]] = {}
        out: List[DriverThreat] = []
        for c in contours:
            if cv2.contourArea(c) < min_area:
                continue
            x, y, bw, bh = cv2.boundingRect(c)
            cx_norm = (x + bw / 2.0) / w
            cy_norm = (y + bh / 2.0) / h
            tid = self._assign_id(cx_norm, cy_norm, seen)
            seen[tid] = (cx_norm, cy_norm)
            az = bbox_to_rel_az(cx_norm, self._hfov)
            size = bbox_height_to_size(bh / h)
            collision = self._collision.update(tid, az, size, t)
            out.append(DriverThreat(rel_az_deg=az, size=size, collision=collision, id=tid))
        self._tracks = seen
        return out

    def _assign_id(self, cx: float, cy: float, seen: dict) -> int:
        best_id, best_d = None, self._match_dist
        for tid, (px, py) in self._tracks.items():
            if tid in seen:
                continue
            d = math.hypot(cx - px, cy - py)
            if d < best_d:
                best_id, best_d = tid, d
        if best_id is not None:
            return best_id
        tid = self._next_id
        self._next_id += 1
        return tid

    def _kernel(self):
        return self._cv2.getStructuringElement(self._cv2.MORPH_ELLIPSE, (5, 5))

    def close(self) -> None:
        closer = getattr(self._camera, "close", None)
        if closer:
            closer()


def build_detector(
    source: str,
    hfov_deg: float,
    device: str,
    width: int,
    height: int,
) -> Detector:
    """Factory: ``fake`` needs nothing; ``thermal``/``rgb`` open a UVC camera and
    wrap it in the motion detector. Camera/cv2 imports stay lazy."""
    if source == "fake":
        return FakeDetector()
    from .capture import UvcCamera

    camera = UvcCamera(device, width=width, height=height)
    return MotionDetector(camera, hfov_deg=hfov_deg)


# Keep the optional-return import referenced for type-checkers without requiring
# it at runtime on the stdlib-only path.
_ = Optional
