#!/usr/bin/env python3
"""Watch the ZTHREAT bus from a laptop — what the driver's HUD sees.

Non-invasive: it joins the multicast group the running zvision service already
broadcasts to, so there is no need to stop the service or take the camera.

    python3 tools/threat_listen.py                 # live, ^C to stop
    python3 tools/threat_listen.py --track         # one line per frame, tracks |az| extremes

Frames are decoded with the real `zvision.threat_protocol.parse_frame`, so this
reads the wire exactly as the tablet does (same grammar, same 32-bit float
width, same golden corpus behind both).
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "jetson"))
from zvision.threat_protocol import parse_frame  # noqa: E402

GROUP = "239.7.7.20"
PORT = 10120


def open_socket(group: str, port: int) -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind(("", port))
    mreq = struct.pack("4sl", socket.inet_aton(group), socket.INADDR_ANY)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    s.settimeout(1.0)
    return s


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", default=GROUP)
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--seconds", type=float, default=0.0, help="stop after this long")
    ap.add_argument("--track", action="store_true",
                    help="FOV-calibration mode: report the extreme bearings seen")
    args = ap.parse_args()

    sock = open_socket(args.group, args.port)
    print(f"listening on {args.group}:{args.port}  —  ^C to stop", flush=True)

    started = time.monotonic()
    frames = 0
    widest_left = 0.0
    widest_right = 0.0
    last_render = 0.0

    try:
        while True:
            if args.seconds and time.monotonic() - started >= args.seconds:
                break
            try:
                data, _ = sock.recvfrom(4096)
            except socket.timeout:
                continue
            frames += 1
            contacts = parse_frame(data.decode("ascii", "replace"))
            if contacts is None:
                continue

            for c in contacts:
                widest_left = min(widest_left, c.rel_az_deg)
                widest_right = max(widest_right, c.rel_az_deg)

            now = time.monotonic()
            if args.track:
                # One refreshing line: bearing now, and the extremes so far.
                if now - last_render < 0.15:
                    continue
                last_render = now
                if contacts:
                    live = "  ".join(
                        f"id{c.id}@{c.rel_az_deg:+6.1f}° sz{c.size:.2f}"
                        f"{' COLLISION' if c.collision else ''}"
                        for c in contacts
                    )
                else:
                    live = "-- no contact --"
                sys.stdout.write(
                    f"\r{live:<56}   extremes: {widest_left:+.1f}° / {widest_right:+.1f}°   "
                )
                sys.stdout.flush()
            else:
                if contacts:
                    print("  ".join(f"id{c.id}@{c.rel_az_deg:+.1f}° sz{c.size:.2f}"
                                    for c in contacts), flush=True)
    except KeyboardInterrupt:
        pass

    elapsed = time.monotonic() - started
    print(f"\n\n=== {elapsed:.0f}s, {frames} frames ===", flush=True)
    print(f"widest bearing seen: {widest_left:+.1f}° (left) / {widest_right:+.1f}° (right)", flush=True)
    if args.track:
        print("\nFOV check: the code reports a bearing computed from the CONFIGURED fov.", flush=True)
        print("The physical truth is the tape measure: half-FOV = atan(offset / distance).", flush=True)
        print("  at 1.0 m, an edge at 2.05 m off centre => 64° half-FOV (160° DIAGONAL)", flush=True)
        print("  at 1.0 m, an edge at 5.67 m off centre => 80° half-FOV (160° horizontal)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
