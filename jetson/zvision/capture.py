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

    def close(self) -> None:
        cap = getattr(self, "_cap", None)
        if cap is not None:
            cap.release()
