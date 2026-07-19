"""zvision — Zodiac vehicle edge-box (Jetson) software.

Runs on the roof-mounted Jetson Orin Nano. Turns thermal/RGB camera frames into
vehicle-relative *threat contacts* and broadcasts them on the fleet bus, where
every tablet's DRIVER night-HUD is already listening (see the Kotlin
`NetworkThreatSource` / `ThreatProtocol`). The wire format here is a byte-exact
mirror of that contract, so this box and the tablets need no shared code — just
the frozen protocol.

Two detector paths:
  * FakeDetector  — pure standard library, no hardware. Synthetic moving
                    contacts so the HUD can be exercised before any camera or
                    model exists.
  * MotionDetector — cv2-backed, runs on a real UVC camera the day it's plugged
                    in (background-subtraction blobs, no trained model needed
                    for bring-up).
"""

__version__ = "0.1.0"
