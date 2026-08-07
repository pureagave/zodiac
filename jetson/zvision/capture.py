"""UVC camera capture. Thin cv2 wrapper so both the Lepton (thermal, over
PureThermal/UVC) and a visible USB webcam open through the same V4L2 path — the
Jetson sees both as ``/dev/videoN``. cv2/numpy are imported lazily so nothing
here is needed on the stdlib-only fake path."""

from __future__ import annotations

from typing import Optional


class UvcCamera:
    """Opens a V4L2 UVC device and yields frames. ``read()`` returns a BGR numpy
    array or ``None`` if a frame wasn't ready. Requires OpenCV; the import is
    deferred to construction so importing this module never drags in cv2."""

    def __init__(
        self,
        device: str = "/dev/video0",
        width: int = 160,
        height: int = 120,
        fourcc: str = "",
        fps: Optional[float] = None,
    ) -> None:
        import cv2

        self._cv2 = cv2
        # Accept either "/dev/videoN" or a bare integer index.
        index: object = device
        if isinstance(device, str) and device.startswith("/dev/video"):
            index = int(device.rsplit("video", 1)[1])
        self._cap = cv2.VideoCapture(index)
        # FOURCC must be set BEFORE the frame size — several V4L2 drivers reset
        # the negotiated size when the pixel format changes underneath them.
        #
        # This matters far more than it looks on a multi-camera rig: the kernel
        # reserves USB isochronous bandwidth from what the camera *declares*,
        # not what it sends, and uncompressed YUYV at 1080p30 declares roughly a
        # gigabit each. Ask for MJPG and several cameras coexist on one bus;
        # leave it at the driver default and the third one fails to stream.
        if fourcc:
            self._cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*fourcc))
        self._cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self._cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        if fps:
            self._cap.set(cv2.CAP_PROP_FPS, fps)
        if not self._cap.isOpened():
            raise RuntimeError(f"could not open camera {device!r}")

    def actual(self) -> dict:
        """What the driver actually gave us, which is often not what we asked
        for. Worth logging at start-up: a camera silently falling back to YUYV
        is exactly how a rig works on the bench and dies with all five plugged
        in."""
        cv2 = self._cv2
        raw = int(self._cap.get(cv2.CAP_PROP_FOURCC))
        code = "".join(chr((raw >> (8 * i)) & 0xFF) for i in range(4)) if raw else ""
        return {
            "fourcc": code.strip(),
            "width": int(self._cap.get(cv2.CAP_PROP_FRAME_WIDTH)),
            "height": int(self._cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
            "fps": float(self._cap.get(cv2.CAP_PROP_FPS)),
        }

    def read(self) -> Optional["object"]:
        ok, frame = self._cap.read()
        return frame if ok else None


class ThermalCamera:
    """A FLIR Lepton behind a PureThermal board, read as **raw Y16** and
    normalised here rather than trusting the camera's own gain.

    Measured on the real hardware (2026-08-07), because none of this was
    obvious from the datasheet:

    * The board's **8-bit output carries no usable contrast** — a real indoor
      frame came back with a standard deviation of **0.12 counts**, i.e. flat.
      The Lepton's AGC is reachable only through vendor UVC extension units,
      not standard V4L2 (``--list-ctrls`` shows a read-only brightness and
      nothing else). Feeding that to background subtraction detects nothing,
      ever. The same scene in **Y16 raw** shows hundreds of counts of real
      structure, and a hand reads **+100 to +360** above background.
    * The sensor is **160x120**, not the 120x120 we had assumed, and the Y16
      mode returns **122 rows** — the last two are Lepton *telemetry*, not
      image. Left in place they are a permanent band of false motion along the
      bottom edge, so they are cropped here.
    * Native rate is **9 fps**; asking for more just re-reads frames.

    Normalisation is per-frame percentile stretch, which is what makes a
    non-radiometric sensor usable: absolute counts are arbitrary and drift
    (a flat-field correction re-baselines the whole image every few minutes),
    so only *relative* structure within a frame is meaningful.
    """

    TELEMETRY_ROWS = 2
    NATIVE_FPS = 9.0

    def __init__(
        self,
        device: str = "/dev/video0",
        width: int = 160,
        height: int = 120,
        low_pct: float = 1.0,
        high_pct: float = 99.0,
    ) -> None:
        import cv2
        import numpy as np

        self._cv2 = cv2
        self._np = np
        self._low, self._high = low_pct, high_pct
        self._rows = height
        index: object = device
        if isinstance(device, str) and device.startswith("/dev/video"):
            index = int(device.rsplit("video", 1)[1])
        # Force V4L2 rather than letting OpenCV pick GStreamer. Measured
        # difference on the real board: GStreamer silently ignores
        # CAP_PROP_CONVERT_RGB ("unhandled property") and hands back an already
        # 8-bit-converted frame, std ~86. V4L2 delivers genuine uint16 raw,
        # std ~176 — the extra precision is the whole reason for reading Y16.
        self._cap = cv2.VideoCapture(index, cv2.CAP_V4L2)
        # Y16 before the frame size: the driver re-negotiates geometry when the
        # pixel format changes underneath it.
        self._cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc("Y", "1", "6", " "))
        self._cap.set(cv2.CAP_PROP_CONVERT_RGB, 0)  # hand us the raw 16-bit, unconverted
        self._cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self._cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        if not self._cap.isOpened():
            raise RuntimeError(f"could not open thermal camera {device!r}")

    def read(self) -> Optional["object"]:
        """One frame, telemetry cropped and stretched to 8-bit BGR so the rest
        of the pipeline sees an ordinary image."""
        np = self._np
        ok, frame = self._cap.read()
        if not ok or frame is None:
            return None
        f = np.asarray(frame)
        if f.ndim == 3:
            f = f[:, :, 0]
        # Telemetry rows only exist in the taller mode (122 for a 120-row
        # sensor). Cropping unconditionally eats real image rows — measured:
        # asking for 120 returns 120, and a blind crop left us with 118.
        if f.shape[0] > self._rows:
            f = f[: self._rows]
        f = f.astype(np.float32)
        lo = float(np.percentile(f, self._low))
        hi = float(np.percentile(f, self._high))
        span = max(hi - lo, 1.0)  # a flat frame (mid-FFC) must not divide by ~0
        stretched = np.clip((f - lo) / span * 255.0, 0, 255).astype(np.uint8)
        return self._cv2.cvtColor(stretched, self._cv2.COLOR_GRAY2BGR)

    def close(self) -> None:
        cap = getattr(self, "_cap", None)
        if cap is not None:
            cap.release()

    def close(self) -> None:
        cap = getattr(self, "_cap", None)
        if cap is not None:
            cap.release()
