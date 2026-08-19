"""Wire format for the low-rate camera-coverage channel — ``ZCOVER`` — the
mirror of the tablet's Kotlin ``CoverageProtocol``.

One UDP datagram = the set of bearing arcs a *currently delivering* camera can
see, so the DRIVER HUD can render a dead camera's arc as a **blind** sector
instead of a false all-clear (RES-P2-1). It rides the **same** threat bus as
``ZTHREAT`` (``239.7.7.20:10120``) through the existing
:class:`~zvision.broadcaster.ThreatBroadcaster` socket, so the tablets need no
new socket, group join or multicast lock.

    ``ZCOVER;start:end;start:end``   — degrees, 1 decimal, swept clockwise

``start`` is in ``[-180, 180]``; ``end`` is greater than ``start`` and within
one full turn of it, so a seam-straddling arc may report ``end > 180`` (matching
:func:`zvision.rig.covered_arcs`). A **bare** ``ZCOVER`` means *nothing is
covered* — the whole ring is blind. Covered arcs, not blind arcs, ride the wire
on purpose: the degenerate empty message then fails BLIND, never clear.

This is a cross-language contract, so — like ``ZTHREAT`` — it has a checked-in
golden corpus (``protocol/coverage-protocol-golden.json``) that both test suites
read. Unlike ``ZTHREAT`` it is *new*, so it gets the differential discipline
from day one without touching the ZTHREAT corpus at all. The numeric grammar,
framing whitespace and 32-bit-float rounding are shared verbatim with
:mod:`zvision.threat_protocol` so the two channels cannot drift in how they read
a number.
"""

from __future__ import annotations

import math
import time
from typing import List, Optional, Sequence, Tuple

# Reuse the ZTHREAT grammar rather than restating it: a wire format must not
# inherit the host language's numeric parser, and both channels must read a
# number identically. Imported (not redefined) so there is exactly one such
# grammar in the package. threat_protocol.py is not modified by this import.
from .threat_protocol import (
    _FRAME_WHITESPACE,
    _NUMBER_PATTERN,
    _f32,
    _fmt,
)

HEADER = "ZCOVER"
_ARC_SEP = ";"
_FIELD_SEP = ":"
_FIELDS_PER_ARC = 2
_COVER_DECIMALS = 1

# A start bearing is a real bearing off the nose; anything outside ±180 is
# garbage, not a rear arc. An arc must sweep forward (end > start) and cannot be
# longer than a full turn. Cap the arc count so a hostile/buggy datagram cannot
# build a frame that IP-fragments — and every rejected arc degrades toward LESS
# coverage (more blind), never more.
MAX_ABS_START_DEG = 180.0
MAX_SPAN_DEG = 360.0
MAX_ARCS = 16

# Default cadence for :class:`CoverageScheduler`: coverage changes only when a
# camera dies or recovers, so a steady 1 Hz heartbeat plus an immediate send on
# any change is ample — no need to churn it into every 10 Hz ZTHREAT frame.
DEFAULT_COVERAGE_PERIOD_SECS = 1.0


def format_coverage(arcs: Sequence[Tuple[float, float]]) -> str:
    """Serialise covered arcs to one ``ZCOVER`` frame. An empty sequence yields
    the bare header — *nothing covered*, i.e. the whole ring blind. Mirrors
    ``CoverageProtocol.format`` on the tablet."""
    capped = list(arcs)[:MAX_ARCS]
    parts = [HEADER]
    for start, end in capped:
        if not (math.isfinite(start) and math.isfinite(end)):
            # A NaN/inf arc would serialise as "nan"/"inf", which the parser
            # drops anyway — but the producer keeps its own frames well-formed
            # rather than leaning on the consumer's guard being there.
            continue
        parts.append(
            f"{_fmt(start, _COVER_DECIMALS)}{_FIELD_SEP}{_fmt(end, _COVER_DECIMALS)}"
        )
    return _ARC_SEP.join(parts)


def parse_coverage(line: str) -> Optional[List[Tuple[float, float]]]:
    """Inverse of :func:`format_coverage`; ``None`` if ``line`` is not a
    ``ZCOVER`` frame at all (so a ``ZTHREAT`` datagram on the shared port is
    cleanly ignored). A bare header parses to ``[]`` — a valid "nothing
    covered". Malformed arcs are skipped, never fatal, and every skip means less
    coverage (more blind), which is the safe direction."""
    parts = line.strip(_FRAME_WHITESPACE).split(_ARC_SEP)
    if not parts or parts[0] != HEADER:
        return None
    out: List[Tuple[float, float]] = []
    for entry in parts[1:]:
        f = entry.split(_FIELD_SEP)
        if len(f) != _FIELDS_PER_ARC:
            continue
        if not _NUMBER_PATTERN.fullmatch(f[0]) or not _NUMBER_PATTERN.fullmatch(f[1]):
            continue
        start = _f32(float(f[0]))
        end = _f32(float(f[1]))
        if start < -MAX_ABS_START_DEG or start > MAX_ABS_START_DEG:
            continue
        if not (end > start) or (end - start) > MAX_SPAN_DEG:
            continue
        out.append((start, end))
        if len(out) >= MAX_ARCS:
            break
    return out


class CoverageScheduler:
    """Decides when the coverage frame should be (re)sent — pure, on an
    injectable clock, so the cadence is unit-tested without a running loop.

    Sends immediately whenever the covered set **changes** (a camera died or
    recovered — the driver must learn at once), and otherwise at most once per
    ``period`` as a heartbeat so a tablet that just joined, or lost a datagram,
    re-learns the coverage within a second. The very first call always sends.
    Paired with :data:`LIVE_WINDOW_SECS` this bounds detect-to-wire at
    ``LIVE_WINDOW_SECS`` (a camera going blind) plus one loop tick."""

    _UNSET = object()

    def __init__(self, period: float = DEFAULT_COVERAGE_PERIOD_SECS, clock=None) -> None:
        self._period = period
        self._clock = clock or time.monotonic
        self._last_sent_at: Optional[float] = None
        self._last_covered: object = CoverageScheduler._UNSET

    def due(self, covered: Sequence[Tuple[float, float]]) -> bool:
        """Whether to send now, given the current covered set. Records the set
        and the send time when it returns True, so the caller just sends."""
        now = self._clock()
        snapshot = list(covered)
        changed = snapshot != self._last_covered
        elapsed = self._last_sent_at is None or (now - self._last_sent_at) >= self._period
        if changed or elapsed:
            self._last_sent_at = now
            self._last_covered = snapshot
            return True
        return False
