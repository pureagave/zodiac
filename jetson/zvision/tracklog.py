"""Complete breadcrumb of everywhere Zodiac goes.

A passive recorder: it joins the fleet's NMEA multicast group — the same one
every tablet already listens to — and appends one row per fix to a daily CSV.
It sends nothing, and the beacon does not know it exists, so recording can
never affect navigation.

**It records everything, deliberately.** A full fourteen-day burn at 1 Hz is
about 66 MB raw and 8 MB gzipped against 434 GB free on the Jetson, so there is
no storage argument for geogating or for dropping stationary time — and every
filter is a chance to lose the one stretch you wanted. You cannot recover what
you did not write down.

Durability decisions, in order of how much they matter on a vehicle whose power
comes and goes:

* **Append-only CSV, flushed every row.** A power cut costs at most the row in
  flight; everything before it is already on disk and readable. A format that
  needs a closing tag (GPX, JSON) would risk the whole file. Convert to GPX or
  GeoJSON afterwards, from a file that survived.
* **Daily files**, so a corrupt tail can never cost more than one day.
* **Never crash on bad input.** A malformed sentence is skipped and counted,
  not raised — this process staying alive for two weeks matters more than any
  individual fix.
"""

from __future__ import annotations

import csv
import datetime as _dt
import os
import socket
import struct
from dataclasses import dataclass, field

FLEET_NMEA_GROUP = "239.7.7.10"
FLEET_NMEA_PORT = 10110

CSV_HEADER = ("utc", "lat", "lon", "speed_kph", "heading_deg", "fix_quality", "sats")

KNOTS_TO_KPH = 1.852
_MINUTES_PER_DEGREE = 60.0


@dataclass
class Fix:
    """One position report, already in decimal degrees."""

    utc: str
    lat: float
    lon: float
    speed_kph: float | None = None
    heading_deg: float | None = None
    fix_quality: int | None = None
    sats: int | None = None

    def row(self) -> list:
        def num(value, digits):
            return "" if value is None else f"{value:.{digits}f}"

        return [
            self.utc,
            f"{self.lat:.7f}",
            f"{self.lon:.7f}",
            num(self.speed_kph, 2),
            num(self.heading_deg, 1),
            "" if self.fix_quality is None else str(self.fix_quality),
            "" if self.sats is None else str(self.sats),
        ]


def _checksum_ok(line: str) -> bool:
    """NMEA XOR checksum. A sentence without one is accepted — some senders
    omit it — but a sentence with a *wrong* one is a corrupted packet."""
    if "*" not in line:
        return True
    body, _, given = line.partition("*")
    given = given.strip()
    if len(given) < 2:
        return False
    computed = 0
    for char in body.lstrip("$"):
        computed ^= ord(char)
    try:
        return computed == int(given[:2], 16)
    except ValueError:
        return False


def _dm_to_degrees(value: str, hemi: str) -> float | None:
    """`ddmm.mmmm` + hemisphere to signed decimal degrees."""
    if not value or not hemi:
        return None
    try:
        raw = float(value)
    except ValueError:
        return None
    degrees = int(raw / 100)
    minutes = raw - degrees * 100
    if minutes < 0 or minutes >= _MINUTES_PER_DEGREE:
        return None
    result = degrees + minutes / _MINUTES_PER_DEGREE
    if hemi in ("S", "W"):
        result = -result
    if hemi in ("N", "S") and abs(result) > 90:
        return None
    if hemi in ("E", "W") and abs(result) > 180:
        return None
    return result


def parse_fix(line: str, utc: str) -> Fix | None:
    """Parse a GGA or RMC sentence into a [Fix], or None if it carries no
    usable position. Empty fields are the normal no-sky case, not an error."""
    line = line.strip()
    if not line.startswith("$") or not _checksum_ok(line):
        return None
    body = line.partition("*")[0]
    parts = body.split(",")
    kind = parts[0][3:] if len(parts[0]) >= 6 else ""

    if kind == "GGA" and len(parts) >= 8:
        lat = _dm_to_degrees(parts[2], parts[3])
        lon = _dm_to_degrees(parts[4], parts[5])
        quality = int(parts[6]) if parts[6].isdigit() else None
        if lat is None or lon is None or not quality:
            return None
        return Fix(
            utc=utc,
            lat=lat,
            lon=lon,
            fix_quality=quality,
            sats=int(parts[7]) if parts[7].isdigit() else None,
        )

    if kind == "RMC" and len(parts) >= 9:
        if parts[2] != "A":
            return None
        lat = _dm_to_degrees(parts[3], parts[4])
        lon = _dm_to_degrees(parts[5], parts[6])
        if lat is None or lon is None:
            return None

        def opt(index, scale=1.0):
            try:
                return float(parts[index]) * scale
            except (ValueError, IndexError):
                return None

        return Fix(
            utc=utc,
            lat=lat,
            lon=lon,
            speed_kph=opt(7, KNOTS_TO_KPH),
            heading_deg=opt(8),
        )

    return None


@dataclass
class TrackWriter:
    """Daily append-only CSV files under [directory], flushed every row."""

    directory: str
    prefix: str = "zodiac-track"
    _day: str | None = field(default=None, init=False, repr=False)
    _handle: object = field(default=None, init=False, repr=False)
    _writer: object = field(default=None, init=False, repr=False)
    rows: int = field(default=0, init=False)

    def path_for(self, day: str) -> str:
        return os.path.join(self.directory, f"{self.prefix}-{day}.csv")

    def write(self, fix: Fix) -> None:
        day = fix.utc[:10]
        if day != self._day:
            self._rotate(day)
        self._writer.writerow(fix.row())
        # Flush AND fsync: a vehicle loses power without warning, and a row
        # sitting in the OS page cache is a row that was never recorded.
        self._handle.flush()
        os.fsync(self._handle.fileno())
        self.rows += 1

    def _rotate(self, day: str) -> None:
        self.close()
        os.makedirs(self.directory, exist_ok=True)
        path = self.path_for(day)
        fresh = not os.path.exists(path) or os.path.getsize(path) == 0
        self._handle = open(path, "a", newline="", encoding="ascii")
        self._writer = csv.writer(self._handle)
        if fresh:
            self._writer.writerow(CSV_HEADER)
        self._day = day

    def close(self) -> None:
        if self._handle is not None:
            self._handle.close()
            self._handle = None
            self._writer = None


def _now_utc() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run(directory: str, group: str = FLEET_NMEA_GROUP, port: int = FLEET_NMEA_PORT,
        clock=_now_utc, stop_after: int | None = None) -> int:
    """Listen and record until interrupted. Returns the row count (tests pass
    [stop_after] to bound it)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", port))
    sock.setsockopt(
        socket.IPPROTO_IP,
        socket.IP_ADD_MEMBERSHIP,
        struct.pack("4sl", socket.inet_aton(group), socket.INADDR_ANY),
    )
    writer = TrackWriter(directory)
    try:
        while stop_after is None or writer.rows < stop_after:
            try:
                datagram, _ = sock.recvfrom(65535)
            except socket.timeout:
                continue
            for raw in datagram.decode("ascii", "replace").splitlines():
                fix = parse_fix(raw, clock())
                if fix is not None:
                    writer.write(fix)
    finally:
        writer.close()
        sock.close()
    return writer.rows


def main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(
        prog="zodiac-track",
        description="Record a complete breadcrumb of the vehicle from the fleet NMEA bus.",
    )
    parser.add_argument("--dir", default="/var/lib/zodiac/track", help="where to write daily CSVs")
    parser.add_argument("--group", default=FLEET_NMEA_GROUP)
    parser.add_argument("--port", type=int, default=FLEET_NMEA_PORT)
    args = parser.parse_args(argv)
    run(args.dir, group=args.group, port=args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
