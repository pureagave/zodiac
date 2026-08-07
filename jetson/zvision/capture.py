"""UVC camera capture. Thin cv2 wrapper so both the Lepton (thermal, over
PureThermal/UVC) and a visible USB webcam open through the same V4L2 path — the
Jetson sees both as ``/dev/videoN``. cv2/numpy are imported lazily so nothing
here is needed on the stdlib-only fake path."""

from __future__ import annotations

from typing import Optional

from .normalize import stretch_window


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
        # Force V4L2. Without it OpenCV may pick another backend (obsensor on
        # this Jetson) which fails outright with "Camera index out of range"
        # for a perfectly good /dev/videoN.
        self._cap = cv2.VideoCapture(index, cv2.CAP_V4L2)
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

    Normalisation has to make a non-radiometric sensor usable — absolute counts
    are arbitrary and drift, and flat-field correction re-baselines the whole
    image every few minutes — **without destabilising the background model
    downstream**, which is the harder half.

    A naive per-frame percentile stretch does the first and ruins the second.
    Measured on this board: frame-to-frame output brightness swung **37.8 of
    255 levels**, because a hot object entering raises the 99th percentile and
    the stretch then darkens everything else to compensate. Background
    subtraction sees a ~15% global shift every frame and a real hand cannot
    compete with it — detection collapsed to one weak blob in 22 seconds of
    waving.

    The split that works — measured against live frames of a *static* scene,
    where every output level of movement is pure noise:

    ==============================================  =====================
    strategy                                        background swing
    ==============================================  =====================
    per-frame percentile endpoints (naive)          10.9 levels
    smoothed centre **and** scale                   128.7 levels
    **per-frame median centre + smoothed scale**    **6.0 levels**
    ==============================================  =====================

    * The **centre tracks per frame**, using the *median*. It has to be
      per-frame because it is cancelling real sensor drift — smoothing it
      leaves the mapping behind the data and was ten times worse than doing
      nothing. The median specifically, because a hot object barely moves it
      (it is a small fraction of pixels) whereas it *defines* a percentile
      endpoint.
    * The **scale is smoothed** across frames. This is what stops a hand
      entering the field from inflating the range and darkening everything
      else — the failure that made the naive version useless.

    Result: hot things read as bright *outliers* against a background that
    holds still, which is what MOG2 needs to see.
    """

    TELEMETRY_ROWS = 2
    NATIVE_FPS = 9.0

    # Applies to the *scale* only. Slow enough that a person crossing the field
    # cannot drag the contrast with them; the centre is never smoothed.
    EMA_ALPHA = 0.05

    # Never amplify beyond this. A thermally uniform scene has almost no spread,
    # and without a floor the stretch multiplies the sensor's own noise up into
    # visible structure that background subtraction dutifully reports as
    # contacts. Measured on this sensor: typical frame-to-frame wobble ~3 counts,
    # and a flat room gave a p2-p98 spread of only 51 counts — a
    # contrast-to-noise ratio of 1.4. At that gain, noise became ~7.5 output
    # levels and produced false contacts in bursts.
    #
    # 200 counts keeps typical noise under ~2 output levels, while sitting far
    # below the ~600 counts a person actually produces — so real contacts are
    # unaffected and a featureless scene simply renders flat, which is honest.
    MIN_SPREAD_COUNTS = 200.0

    def __init__(
        self,
        device: str = "/dev/video0",
        width: int = 160,
        height: int = 120,
        low_pct: float = 2.0,
        high_pct: float = 98.0,
    ) -> None:
        import cv2
        import numpy as np

        self._cv2 = cv2
        self._np = np
        self._low, self._high = low_pct, high_pct
        self._rows = height
        # Smoothed contrast scale; None until the first frame. The centre is
        # deliberately NOT held here — it is recomputed every frame.
        self._spread_ema: Optional[float] = None
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
        # Median, not a percentile endpoint: a hot object barely moves it, so
        # the mapping doesn't lurch when someone walks into frame.
        centre = float(np.median(f))
        spread = float(np.percentile(f, self._high) - np.percentile(f, self._low))
        spread = max(spread, 1.0)  # a flat frame (mid-FFC) must not divide by ~0

        # The decision itself lives in zvision.normalize so it can be unit
        # tested without numpy — this is where both 2026-08-07 bugs were.
        lo, scale, self._spread_ema = stretch_window(
            centre, spread, self._spread_ema,
            alpha=self.EMA_ALPHA, min_spread=self.MIN_SPREAD_COUNTS,
        )
        stretched = np.clip((f - lo) / (2.0 * scale) * 255.0, 0, 255).astype(np.uint8)
        return self._cv2.cvtColor(stretched, self._cv2.COLOR_GRAY2BGR)

    def close(self) -> None:
        cap = getattr(self, "_cap", None)
        if cap is not None:
            cap.release()

    def close(self) -> None:
        cap = getattr(self, "_cap", None)
        if cap is not None:
            cap.release()
