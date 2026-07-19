"""Wire format for the thermal-threat channel — byte-compatible with the tablet's
Kotlin ``ThreatProtocol``.

One UDP datagram = one frame: the literal ``ZTHREAT`` header, then a
``;``-separated contact per entry ``id:relAzDeg:size:collision`` (collision
0/1)::

    ZTHREAT;1:-12.0:0.300:0;2:4.5:0.900:1

A frame with no contacts is just ``ZTHREAT`` (-> empty list), which is how
"all clear" is signalled. The parser here mirrors the Kotlin one field-for-field,
so the round-trip test in this repo also guards the tablet's contract.
"""

from __future__ import annotations

from typing import List, Optional

from .threat import DriverThreat

HEADER = "ZTHREAT"
_FRAME_SEP = ";"
_FIELD_SEP = ":"
_FIELDS_PER_CONTACT = 4


def _fmt(value: float, decimals: int) -> str:
    return f"{value:.{decimals}f}"


def format_frame(threats: List[DriverThreat]) -> str:
    """Serialise contacts to one wire frame. An empty list yields the bare
    header -> "all clear". Floats are fixed-precision ASCII the tablet's
    ``toFloatOrNull`` parses without loss of meaning (0.1 deg / 0.001 size)."""
    parts = [HEADER]
    for t in threats:
        col = 1 if t.collision else 0
        parts.append(
            f"{t.id}{_FIELD_SEP}{_fmt(t.rel_az_deg, 1)}"
            f"{_FIELD_SEP}{_fmt(t.size, 3)}{_FIELD_SEP}{col}"
        )
    return _FRAME_SEP.join(parts)


def parse_frame(line: str) -> Optional[List[DriverThreat]]:
    """Inverse of :func:`format_frame`; ``None`` if the line isn't a ZTHREAT
    frame. Malformed contacts are skipped (not fatal), exactly like the tablet's
    ``mapNotNull`` parser."""
    parts = line.strip().split(_FRAME_SEP)
    if not parts or parts[0] != HEADER:
        return None
    out: List[DriverThreat] = []
    for entry in parts[1:]:
        f = entry.split(_FIELD_SEP)
        if len(f) < _FIELDS_PER_CONTACT:
            continue
        try:
            tid = int(f[0])
            az = float(f[1])
            size = float(f[2])
        except ValueError:
            continue
        out.append(
            DriverThreat(rel_az_deg=az, size=size, collision=f[3].strip() == "1", id=tid)
        )
    return out
