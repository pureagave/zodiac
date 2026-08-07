"""Listen for the Sensor Hub's ``$ZAUD`` sound-level frames on the fleet bus and
keep the latest reading, so the DMX tracker light can pulse to the music.

The beacon broadcasts ``$ZAUD,rms,peak,beat*cs`` (~15 Hz) on the telemetry group
(239.7.7.10:10110) — the same NMEA-style framing the tablet parses. This is the
Python consumer: a background UDP thread that joins the group, parses each frame,
and exposes the most recent :class:`AudioLevel`. Pure ``parse_zaud`` is split out
so it's unit-testable without a socket.
"""

from __future__ import annotations

import math
import socket
import struct
import threading
import time
from dataclasses import dataclass
from typing import Optional

from . import fleet_bus


@dataclass(frozen=True)
class AudioLevel:
    """Normalized loudness + beat flag — the Python mirror of the tablet's
    ``AudioLevel``. ``rms``/``peak`` are 0..1; ``beat`` marks a detected onset."""

    rms: float
    peak: float
    beat: bool


def parse_zaud(line: str) -> Optional[AudioLevel]:
    """Parse one ``$ZAUD,rms,peak,beat*cs`` sentence, or ``None`` if it isn't a
    valid ZAUD frame (wrong type, bad checksum, garbage/non-finite fields). XOR
    checksum over the body, mirroring the beacon/tablet contract."""
    line = line.strip()
    if not line.startswith("$") or "*" not in line:
        return None
    body, _, cs = line[1:].partition("*")
    calc = 0
    for ch in body:
        calc ^= ord(ch)
    try:
        if int(cs.strip(), 16) != calc:
            return None
    except ValueError:
        return None
    fields = body.split(",")
    if not fields or fields[0] != "ZAUD":
        return None
    try:
        rms = float(fields[1])
        peak = float(fields[2])
        beat = int(fields[3])
    except (IndexError, ValueError):
        return None
    if not (math.isfinite(rms) and math.isfinite(peak)) or rms < 0.0 or peak < 0.0:
        return None
    return AudioLevel(rms=rms, peak=peak, beat=beat != 0)


#: How long a $ZAUD frame stays usable. The beacon sends at ~15 Hz, so this is
#: generous — it is catching a dead feed, not a dropped packet.
DEFAULT_MAX_AGE_S = 2.0


class ZaudListener:
    """Background thread that receives ``$ZAUD`` off the fleet bus and holds the
    latest :class:`AudioLevel`. Non-blocking to the caller: the DMX loop just
    calls :meth:`latest` each frame. Joins the telemetry multicast group and also
    receives the beacon's subnet-broadcast copy (bound to the port on all NICs)."""

    #: Overridable for tests; monotonic so it cannot go backwards.
    _now = staticmethod(time.monotonic)

    def __init__(
        self,
        group: str = fleet_bus.TELEMETRY_GROUP,
        port: int = fleet_bus.TELEMETRY_PORT,
    ) -> None:
        self._group = group
        self._port = port
        self._latest: Optional[AudioLevel] = None
        self._latest_at: float = 0.0
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._sock: Optional[socket.socket] = None

    def start(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", self._port))
        # Join the multicast group on the default interface; the socket also gets
        # the subnet-broadcast copy since it's bound to the port on all NICs.
        mreq = struct.pack("4sl", socket.inet_aton(self._group), socket.INADDR_ANY)
        # runCatching-equivalent: a host with no multicast route still receives
        # the broadcast leg, so a failed join must not stop the listener.
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        except OSError:
            pass
        sock.settimeout(0.5)  # so close() unwinds the loop promptly
        self._sock = sock
        self._running = True
        self._thread = threading.Thread(target=self._run, name="zaud-listener", daemon=True)
        self._thread.start()

    def _run(self) -> None:
        sock = self._sock
        if sock is None:
            return
        while self._running:
            try:
                data, _ = sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            for raw in data.decode("ascii", "ignore").split("\n"):
                level = parse_zaud(raw)
                if level is not None:
                    with self._lock:
                        self._latest = level
                        self._latest_at = self._now()

    def latest(self, max_age_s: float = DEFAULT_MAX_AGE_S) -> Optional[AudioLevel]:
        """The most recent level, or ``None`` if it has gone stale.

        Age matters because the consumer is a light. Without it, a beacon that
        stops broadcasting mid-set leaves the last frame latched forever — and
        if that frame happened to carry ``beat=1``, the idle head pins at full
        brightness all night, looking like a working sound show while actually
        masking a dead audio feed. Falling back to ``None`` lets the tracker
        drop to its idle dimmer, which reads honestly as "no music".

        ``max_age_s <= 0`` disables the check.
        """
        with self._lock:
            level = self._latest
            at = self._latest_at
        if level is None:
            return None
        if max_age_s > 0 and (self._now() - at) > max_age_s:
            return None
        return level

    def close(self) -> None:
        self._running = False
        if self._sock is not None:
            self._sock.close()
        if self._thread is not None:
            self._thread.join(timeout=1.0)
