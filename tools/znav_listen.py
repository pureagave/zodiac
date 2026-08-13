#!/usr/bin/env python3
"""Watch the ZNAV bus — the shared nav target the fleet's authorities broadcast.

Non-invasive: it joins the multicast group the tablets already broadcast to, so
nothing has to stop. Silence is ZNAV's *normal* idle state (no owner ⇒ no
traffic), so "0 frames" is a valid result, not a fault — send a set on an
authority (S9+/A54) and the owner re-broadcasts every 3 s.

    python3 tools/znav_listen.py                 # live, ^C to stop
    python3 tools/znav_listen.py --seconds 8     # bounded capture, then summarise
    python3 tools/znav_listen.py --raw           # print bytes, skip the decode

The Mac cannot listen (its firewall drops inbound UDP — multicast, broadcast and
unicast alike), so run this on the Jetson.

Unlike ZTHREAT, ZNAV is single-language (`:app`-only) — its authoritative parser
is Kotlin (`core/ops/NavShareProtocol.kt`) and there is no shared Python codec.
The decode below is a *convenience* reader: it verifies the XOR checksum and
splits the documented grammar (docs/PROTOCOLS.md §5), and flags anything it
can't. Treat a mismatch here as "look at the Kotlin", not as ground truth.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
from collections import Counter

GROUP = "239.7.7.30"
PORT = 10130


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


def checksum_ok(body: str, want: str) -> bool:
    """XOR of the bytes between '$' and '*', two-hex, case-insensitive."""
    got = 0
    for ch in body:
        got ^= ord(ch)
    try:
        return got == int(want, 16)
    except ValueError:
        return False


def decode(line: str) -> str:
    """One human line for a raw ZNAV sentence, or a flagged reason it isn't one."""
    line = line.strip()
    if not (line.startswith("$ZNAV,") and "*" in line):
        return "?? not a ZNAV sentence: %r" % line
    body, _, cc = line[1:].partition("*")
    ck = "ok" if checksum_ok(body, cc) else "BAD-CK(*%s)" % cc
    fields = body.split(",")  # ZNAV, seq, src, TYPE, [payload...]
    if len(fields) < 4:
        return "?? short: %r  [%s]" % (line, ck)
    seq, src, kind = fields[1], fields[2], fields[3]
    payload = ",".join(fields[4:])
    tail = " %s" % payload if payload else ""
    return "seq=%-4s src=%-8s %-6s%-10s [%s]" % (seq, src, kind, tail, ck)


def main() -> int:
    ap = argparse.ArgumentParser(description="Watch the ZNAV shared-nav-target bus.")
    ap.add_argument("--seconds", type=float, default=0.0, help="bounded capture then exit (0 = live until ^C)")
    ap.add_argument("--raw", action="store_true", help="print raw bytes, skip the decode")
    args = ap.parse_args()

    s = open_socket(GROUP, PORT)
    deadline = time.time() + args.seconds if args.seconds > 0 else None
    print("listening %s:%d%s" % (GROUP, PORT, (" for %.0fs" % args.seconds) if deadline else " (^C to stop)"))
    n = 0
    kinds: Counter = Counter()
    srcs: set = set()
    try:
        while deadline is None or time.time() < deadline:
            try:
                data, addr = s.recvfrom(2048)
            except socket.timeout:
                continue
            n += 1
            srcs.add(addr[0])
            text = data.decode("ascii", "replace").strip()
            if args.raw:
                print("%s  %-15s  %r" % (time.strftime("%H:%M:%S"), addr[0], text))
                continue
            fields = text[1:].split(",") if text.startswith("$") else []
            if len(fields) >= 4:
                kinds[fields[3]] += 1
            print("%s  %-15s  %s" % (time.strftime("%H:%M:%S"), addr[0], decode(text)))
    except KeyboardInterrupt:
        pass
    print("total %d frame(s) from %s%s" % (n, sorted(srcs), ("  kinds=%s" % dict(kinds) if kinds else "")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
