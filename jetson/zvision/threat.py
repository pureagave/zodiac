from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class DriverThreat:
    """A thermal/visible contact in vehicle-relative terms — the exact mirror of
    the Kotlin ``DriverThreat`` the tablet HUD consumes.

    rel_az_deg: bearing off the vehicle's nose, negative left / positive right.
    size:       0.0 (far) -> 1.0 (near).
    collision:  constant-bearing / looming track — the only contact the night
                HUD is allowed to draw bright red.
    id:         stable track id so a contact can be followed frame to frame
                (0 for ad-hoc / test contacts).
    """

    rel_az_deg: float
    size: float
    collision: bool = False
    id: int = 0
