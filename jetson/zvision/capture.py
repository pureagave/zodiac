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

    def __init__(self, device: str = "/dev/video0", width: int = 160, height: int = 120) -> None:
        import cv2

        self._cv2 = cv2
        # Accept either "/dev/videoN" or a bare integer index.
        index: object = device
        if isinstance(device, str) and device.startswith("/dev/video"):
            index = int(device.rsplit("video", 1)[1])
        self._cap = cv2.VideoCapture(index)
        self._cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self._cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        if not self._cap.isOpened():
            raise RuntimeError(f"could not open camera {device!r}")

    def read(self) -> Optional["object"]:
        ok, frame = self._cap.read()
        return frame if ok else None

    def close(self) -> None:
        cap = getattr(self, "_cap", None)
        if cap is not None:
            cap.release()
