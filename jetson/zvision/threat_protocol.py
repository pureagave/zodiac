"""Wire format for the thermal-threat channel — the mirror of the tablet's
Kotlin ``ThreatProtocol``.

One UDP datagram = one frame: the literal ``ZTHREAT`` header, then a
``;``-separated contact per entry ``id:relAzDeg:size:collision`` (collision
0/1)::

    ZTHREAT;1:-12.0:0.300:0;2:4.5:0.900:1

A frame with no contacts is just ``ZTHREAT`` (-> empty list), which is how
"all clear" is signalled.

**This is one half of a cross-language contract.** The other half is the app's
``core/vision/ThreatProtocol.kt``. Neither file is authoritative: the shared
truth is the checked-in corpus at ``protocol/threat-protocol-golden.json``,
which both test suites read. Change the format here and the corpus test fails
until the corpus and the Kotlin side agree — which is the point. Before that
corpus existed the two implementations had silently drifted apart in ten
measured ways (see SYNC.md 2026-08-10).
"""

from __future__ import annotations

import math
import re
import struct
from typing import List, Optional

from .threat import DriverThreat

HEADER = "ZTHREAT"
_FRAME_SEP = ";"
_FIELD_SEP = ":"
_FIELDS_PER_CONTACT = 4

# Bearings are full-circle: the rig fuses cameras all the way around the vehicle
# (see rig.py), so ±180 is the real limit — anything outside it is garbage, not a
# rear contact. Size is a 0..1 range and gets clamped. Cap the contact count so a
# crowded scene can't build a frame that IP-fragments (fragmented multicast over
# lossy WiFi ~never arrives).
MAX_ABS_AZ_DEG = 180.0
MAX_CONTACTS = 32
_AZ_DECIMALS = 1
_SIZE_DECIMALS = 3

# The numeric grammar is stated explicitly rather than delegated to ``int()`` and
# ``float()``, because a wire format must not inherit the host language's parser
# quirks. Python's ``int()`` accepts underscores, surrounding whitespace and
# unbounded magnitudes (a 4000-digit track id parsed fine here and was dropped by
# the tablet); Kotlin's float parser accepts Java source syntax like ``5.0f`` and
# hex floats like ``0x1p3``, which became a live contact bearing 8° on the
# driver's HUD while this side rejected it. ``[0-9]`` rather than ``\d`` because
# Python's ``\d`` also matches Unicode digits and Java's does not. These bounds
# are what the producer actually emits: 9 integer digits keeps an id inside the
# tablet's Int32 and keeps every value far below 32-bit float range, so no parsed
# number here can be NaN or infinite — nor finite here and infinite there, which
# is how a size of 3.5e38 used to survive on this side and vanish on that one.
_ID_PATTERN = re.compile(r"-?[0-9]{1,9}")
_NUMBER_PATTERN = re.compile(r"-?[0-9]{1,9}(\.[0-9]{1,6})?")

# Framing whitespace only — a UDP payload routinely carries a trailing CRLF.
# Spelled out because Python's ``strip()`` and Kotlin's ``trim()`` disagree about
# which Unicode code points count, and the wire contract cannot depend on that.
_FRAME_WHITESPACE = " \t\n\r\v\f"


def _fmt(value: float, decimals: int) -> str:
    return f"{value:.{decimals}f}"


def _f32(value: float) -> float:
    """Round a Python float (64-bit) to what the tablet would hold in a 32-bit
    ``Float``. The consumer's precision is the contract's precision: parsed at
    64 bits, ``180.000001`` is off the circle and gets dropped here, while the
    tablet rounds it to exactly 180.0 and draws the contact. Mirroring the
    narrower type is what makes both sides agree on the range and clamp
    boundaries — and lets the golden corpus state one exact expected value
    instead of a tolerance."""
    return struct.unpack("<f", struct.pack("<f", value))[0]


def format_frame(threats: List[DriverThreat]) -> str:
    """Serialise contacts to one wire frame. An empty list yields the bare
    header -> "all clear". Floats are fixed-precision ASCII (0.1 deg / 0.001
    size). Mirrors ``ThreatProtocol.format`` on the tablet."""
    capped = threats
    if len(capped) > MAX_CONTACTS:
        # Keep the most important contacts so the frame stays under one MTU:
        # collisions first, then nearest.
        capped = sorted(capped, key=lambda t: (t.collision, t.size), reverse=True)[:MAX_CONTACTS]
    parts = [HEADER]
    for t in capped:
        if not (math.isfinite(t.rel_az_deg) and math.isfinite(t.size)):
            # A NaN from a buggy detector would serialise as the literal
            # string "nan", which every parser (the tablet's and ours) drops —
            # so the contact vanishes either way. But a malformed field must
            # not leave this box relying on the consumer's guard being there;
            # the producer keeps its own frames well-formed.
            continue
        col = 1 if t.collision else 0
        parts.append(
            f"{t.id}{_FIELD_SEP}{_fmt(t.rel_az_deg, _AZ_DECIMALS)}"
            f"{_FIELD_SEP}{_fmt(t.size, _SIZE_DECIMALS)}{_FIELD_SEP}{col}"
        )
    return _FRAME_SEP.join(parts)


def parse_frame(line: str) -> Optional[List[DriverThreat]]:
    """Inverse of :func:`format_frame`; ``None`` if the line isn't a ZTHREAT
    frame. Malformed contacts are skipped (not fatal), exactly like the tablet's
    ``mapNotNull`` parser."""
    parts = line.strip(_FRAME_WHITESPACE).split(_FRAME_SEP)
    if not parts or parts[0] != HEADER:
        return None
    out: List[DriverThreat] = []
    for entry in parts[1:]:
        f = entry.split(_FIELD_SEP)
        if len(f) < _FIELDS_PER_CONTACT:
            continue
        if not _ID_PATTERN.fullmatch(f[0]):
            continue
        if not _NUMBER_PATTERN.fullmatch(f[1]) or not _NUMBER_PATTERN.fullmatch(f[2]):
            continue
        az = _f32(float(f[1]))
        if az < -MAX_ABS_AZ_DEG or az > MAX_ABS_AZ_DEG:  # off the circle — garbage
            continue
        out.append(
            DriverThreat(
                rel_az_deg=az,
                size=min(1.0, max(0.0, _f32(float(f[2])))),
                # A malformed flag must never cost the driver a contact: the body
                # still gets drawn, just not in collision red. Anything that is
                # not exactly "1" is false.
                collision=f[3] == "1",
                id=int(f[0]),
            )
        )
        if len(out) >= MAX_CONTACTS:
            break
    return out
