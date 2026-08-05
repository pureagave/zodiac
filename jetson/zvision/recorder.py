"""Dump frames + weak labels to disk so every test drive becomes training data.

``DETECTOR.md`` is blunt about the bottleneck: the big GPU is useless without
*our own* footage. A COCO-pretrained model already finds people in RGB, but
nothing off the shelf has seen 120x120 ultra-wide LWIR from a mutant vehicle at
2am, and no amount of compute substitutes for never having recorded it. Frames
can only be captured while the rig is on the car — compute can be rented any
time. So recording is the schedule-critical half, and it has to exist *before*
the first drive, not after.

Two things get written per recorded frame:

* the image itself, under ``<dir>/<camera>/<seconds>.<ext>``
* a line in ``<dir>/index.jsonl`` carrying the **pixel** boxes the motion
  detector found

Those boxes are weak labels — motion blobs, not truth. But an annotator
correcting existing boxes is enormously faster than one drawing from scratch,
and it means a night of driving arrives pre-segmented into "something moved
here" instead of as thousands of unsorted stills.

Design constraint that overrides everything else: **recording must never break
detection.** The HUD and the tracker light are the live safety-adjacent outputs;
a full disk, a slow card or a bad codec must degrade to "we stopped recording",
never to a stalled or crashed detector. Every failure path here is swallowed,
counted, and reported once.
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

# A frame every this-many seconds by default. Consecutive frames at 10 Hz are
# nearly identical and mostly waste disk and annotator attention; 1 Hz over a
# few hours of driving is a genuinely varied dataset.
DEFAULT_RECORD_HZ = 1.0

# Stop before filling the boot device. The NVMe is 512 GB, but a full root
# filesystem takes the whole box down, which is a far worse outcome than a
# short dataset.
DEFAULT_MAX_MB = 20_000


@dataclass(frozen=True)
class RecorderConfig:
    directory: str
    hz: float = DEFAULT_RECORD_HZ
    jpeg_quality: int = 85
    max_mb: int = DEFAULT_MAX_MB


class FrameRecorder:
    """Subsampled frame + weak-label writer. One instance is shared by every
    camera in the rig so the byte budget is enforced across all of them."""

    def __init__(self, cfg: RecorderConfig) -> None:
        self.cfg = cfg
        self._next_due: Dict[str, float] = {}
        self._bytes = 0
        self._frames = 0
        self._stopped = False
        self._index = None
        self._cv2 = None
        try:
            os.makedirs(cfg.directory, exist_ok=True)
            self._index = open(os.path.join(cfg.directory, "index.jsonl"), "a", encoding="utf-8")
        except OSError as exc:  # unwritable path: warn once, never record
            self._fail(f"cannot open {cfg.directory!r}: {exc}")

    # -- internals ---------------------------------------------------------

    def _fail(self, why: str) -> None:
        if not self._stopped:
            self._stopped = True
            print(f"zvision: recording disabled — {why}", file=sys.stderr, flush=True)

    def _due(self, camera: str, t: float) -> bool:
        """True at most once per 1/hz seconds per camera. Each camera keeps its
        own schedule so a slow one can't starve the others."""
        if self.cfg.hz <= 0:
            return False
        due = self._next_due.get(camera)
        if due is None or t >= due:
            self._next_due[camera] = t + (1.0 / self.cfg.hz)
            return True
        return False

    def _encode(self, frame, path_stem: str) -> Optional[Tuple[str, bytes]]:
        cv2 = self._cv2
        # Thermal is small and low-contrast — JPEG artefacts there cost real
        # signal, and PNG of a 120x120 frame is tiny anyway. RGB is big and
        # tolerant, so it gets JPEG.
        h, w = frame.shape[:2]
        if w * h <= 128 * 128:
            ok, buf = cv2.imencode(".png", frame)
            return (path_stem + ".png", buf.tobytes()) if ok else None
        ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, self.cfg.jpeg_quality])
        return (path_stem + ".jpg", buf.tobytes()) if ok else None

    # -- public ------------------------------------------------------------

    @property
    def frames_written(self) -> int:
        return self._frames

    @property
    def megabytes_written(self) -> float:
        return self._bytes / 1_000_000.0

    def record(
        self,
        camera: str,
        t: float,
        frame,
        boxes: Sequence[Tuple[int, int, int, int]],
    ) -> bool:
        """Maybe write one frame for ``camera``. ``boxes`` are pixel
        ``(x, y, w, h)`` rects — deliberately *not* bearings, because an
        annotator needs image coordinates and az/size can be recomputed from
        them later. Returns whether anything was written."""
        if self._stopped or self._index is None:
            return False
        if not self._due(camera, t):
            return False
        if self._bytes >= self.cfg.max_mb * 1_000_000:
            self._fail(f"hit the {self.cfg.max_mb} MB budget after {self._frames} frames")
            return False
        if self._cv2 is None:
            try:
                import cv2

                self._cv2 = cv2
            except Exception as exc:  # noqa: BLE001 - no cv2, nothing to encode
                self._fail(f"OpenCV unavailable: {exc}")
                return False
        try:
            os.makedirs(os.path.join(self.cfg.directory, camera), exist_ok=True)
            stem = os.path.join(camera, f"{t:012.3f}")
            encoded = self._encode(frame, stem)
            if encoded is None:
                self._fail("frame encode failed")
                return False
            rel, blob = encoded
            with open(os.path.join(self.cfg.directory, rel), "wb") as fh:
                fh.write(blob)
            self._index.write(
                json.dumps(
                    {
                        "camera": camera,
                        "t": round(t, 3),
                        "file": rel,
                        "boxes": [list(map(int, b)) for b in boxes],
                    }
                )
                + "\n"
            )
            self._index.flush()  # a power cut mid-drive must not lose the index
            self._bytes += len(blob)
            self._frames += 1
            return True
        # Broad by design: disk full, read-only remount, a card pulled mid-run.
        # Detection continues regardless — that is the whole point.
        except Exception as exc:  # noqa: BLE001
            self._fail(f"write failed: {exc}")
            return False

    def close(self) -> None:
        if self._index is not None:
            try:
                self._index.close()
            except OSError:
                pass
            self._index = None


def summarize(recorder: Optional[FrameRecorder]) -> str:
    if recorder is None:
        return ""
    return f"{recorder.frames_written} frames / {recorder.megabytes_written:.1f} MB"


_ = List  # keep the typing import meaningful for checkers on the stdlib path
