#!/usr/bin/env python3
"""Watch the ZVER bus — the build every fleet node is announcing (FLEET-1).

Non-invasive: it joins the multicast group every node already broadcasts to, so
nothing has to stop. Each node re-announces every ~10 s, so a few seconds is
enough to see the whole fleet. A node you never hear from is simply absent —
silence reads as unknown, never as "current".

    python3 tools/zver_listen.py                 # live + a roster on ^C
    python3 tools/zver_listen.py --seconds 12    # bounded capture, then the roster
    python3 tools/zver_listen.py --raw           # print bytes, skip the decode

The Mac cannot listen (its firewall drops inbound UDP — multicast, broadcast and
unicast alike), so run this on the Jetson.

ZVER is a cross-language contract (`:app`, `:beacon`, and the Jetson all emit),
so its authoritative parsers are the three hand-written codecs pinned by the
golden corpus. The decode below is a *convenience* reader: it verifies the XOR
checksum and splits the documented grammar (`$ZVER,<node>,<name>,<base>,<sha>,
<dirty>,<epoch>*CC`), and mirrors FleetRoster's "newest commit-epoch wins" rule
so you can see at a glance which nodes are behind. Treat a mismatch here as
"look at the Kotlin/golden", not as ground truth.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time

GROUP = "239.7.7.40"
PORT = 10140
UNKNOWN_SHA = "unknown"


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


def parse(line: str) -> dict | None:
    """A ZVER sentence as a dict, or None if it isn't one / fails the checksum."""
    line = line.strip()
    if not (line.startswith("$ZVER,") and "*" in line):
        return None
    body, _, cc = line[1:].partition("*")
    if not checksum_ok(body, cc):
        return None
    f = body.split(",")  # ZVER, node, name, base, sha, dirty, epoch
    if len(f) != 7:
        return None
    try:
        epoch = int(f[6])
    except ValueError:
        return None
    return {"node": f[1], "name": f[2], "base": f[3], "sha": f[4], "dirty": f[5] == "1", "epoch": epoch}


def trustworthy(v: dict) -> bool:
    """A build usable as the 'newest' yardstick: real sha, clean, self-dating (mirrors FleetRoster)."""
    return v["sha"] != UNKNOWN_SHA and bool(v["sha"]) and not v["dirty"] and v["epoch"] > 0


def status(v: dict, latest: int | None) -> str:
    if not trustworthy(v):
        return "UNKNOWN"
    if latest is not None and v["epoch"] >= latest:
        return "CURRENT"
    return "BEHIND"


def print_roster(seen: dict) -> None:
    if not seen:
        print("roster: (nothing heard — no node announced ZVER)")
        return
    latest = max((v["epoch"] for v in seen.values() if trustworthy(v)), default=None)
    print("\n--- roster (%d node%s) ---" % (len(seen), "" if len(seen) == 1 else "s"))
    # Worst-first, like the hero card: BEHIND/UNKNOWN above CURRENT, then by name.
    order = {"UNKNOWN": 0, "BEHIND": 1, "CURRENT": 2}
    for v in sorted(seen.values(), key=lambda x: (order.get(status(x, latest), 9), x["name"])):
        st = status(v, latest)
        dirty = " dirty" if v["dirty"] else ""
        print("  %-8s %-10s %-8s+%-9s epoch=%-10d %s%s"
              % (v["node"], v["name"], v["base"], v["sha"], v["epoch"], st, dirty))


def main() -> int:
    ap = argparse.ArgumentParser(description="Watch the ZVER fleet-version bus (FLEET-1).")
    ap.add_argument("--seconds", type=float, default=0.0, help="bounded capture then exit (0 = live until ^C)")
    ap.add_argument("--raw", action="store_true", help="print raw bytes, skip the decode")
    args = ap.parse_args()

    s = open_socket(GROUP, PORT)
    deadline = time.time() + args.seconds if args.seconds > 0 else None
    print("listening %s:%d%s" % (GROUP, PORT, (" for %.0fs" % args.seconds) if deadline else " (^C to stop)"))
    n = 0
    seen: dict = {}
    try:
        while deadline is None or time.time() < deadline:
            try:
                data, addr = s.recvfrom(2048)
            except socket.timeout:
                continue
            n += 1
            text = data.decode("ascii", "replace").strip()
            if args.raw:
                print("%s  %-15s  %r" % (time.strftime("%H:%M:%S"), addr[0], text))
                continue
            v = parse(text)
            if v is None:
                print("%s  %-15s  ?? not a ZVER sentence: %r" % (time.strftime("%H:%M:%S"), addr[0], text))
                continue
            seen[v["node"]] = v
            print("%s  %-15s  node=%-8s %-10s %s+%s%s epoch=%d"
                  % (time.strftime("%H:%M:%S"), addr[0], v["node"], v["name"],
                     v["base"], v["sha"], " dirty" if v["dirty"] else "", v["epoch"]))
    except KeyboardInterrupt:
        pass
    print("\ntotal %d ZVER frame(s)" % n)
    if not args.raw:
        print_roster(seen)
    return 0


if __name__ == "__main__":
    sys.exit(main())
