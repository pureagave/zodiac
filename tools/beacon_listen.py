#!/usr/bin/env python3
"""Listen to the Zodiac fleet bus from a laptop — what a tablet hears, without a tablet.

The beacon broadcasts to multicast 239.7.7.10:10110 with a /24 subnet-directed
broadcast fallback, so this joins the group AND accepts broadcast on the same
port; whichever path is working, you see it.

    python3 tools/beacon_listen.py                 # live tail, ^C to stop
    python3 tools/beacon_listen.py --summary 60    # 60 s, then a per-channel report
    python3 tools/beacon_listen.py --await-first   # wait for the FIRST sentence and
                                                   # report how long it took

``--await-first`` is the unattended-reboot acceptance test: start it, reboot the
beacon phone, DO NOT unlock it, and see whether traffic returns on its own. On a
file-based-encryption device with a secure lock screen, BOOT_COMPLETED does not
fire until first unlock — so a phone with a PIN will simply never appear here,
which is the failure this test exists to catch.

⚠️ **Run this on the Jetson, not on the Mac.** Measured 2026-08-10: the macOS
Application Firewall silently drops inbound UDP to python, so this tool reports
"no traffic seen" on a busy bus and you conclude the beacon is dead. A control
burst — multicast, subnet broadcast AND unicast straight at the laptop — was not
heard at all, while the Jetson heard 93 frames of its own bus in 6 s. **Before
trusting a negative result from any passive listener, send yourself a control
packet and confirm the instrument can hear it.**
"""

from __future__ import annotations

import argparse
import collections
import socket
import struct
import subprocess
import sys
import time

GROUP = "239.7.7.10"
PORT = 10110

# The proprietary channels, so the report says what is actually flowing rather
# than just counting bytes. Keep in step with docs/PROTOCOLS.md.
CHANNELS = {
    "$GPGGA": "GPS fix (lat/lon/alt/sats)",
    "$GPRMC": "GPS recommended minimum",
    "$GPVTG": "GPS course/speed over ground",
    "$GPGSA": "GPS DOP + active satellites",
    "$GPGSV": "GPS satellites in view",
    "$GPHDT": "compass true heading",
    "$ZTLM":  "IMU pitch/roll + speed",
    "$ZAUD":  "mic rms/peak/beat",
    "$ZENV":  "ambient lux (drives auto-dim)",
    "$ZSHK":  "shock / impact g",
    "$ZBCN":  "beacon health (battery/fix/sats/uptime)",
    "$ZODO":  "odometer (trip + lifetime)",
}


def lan_ips() -> list:
    """Every IPv4 address this host actually holds, most-likely-LAN first.

    Needed because joining a multicast group with INADDR_ANY lets the OS pick the
    interface, and on a laptop running a VPN (Tailscale here) that pick can land
    on a tunnel that carries none of the vehicle's traffic. Symptom: a listener
    that reports silence while the bus is busy -- measured 2026-08-10, when a
    control burst sent straight at this machine was not heard at all.
    """
    out, seen = [], set()
    try:
        info = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=10).stdout
    except Exception:
        info = ""
    for block in info.split("\n"):
        line = block.strip()
        if line.startswith("inet ") and "127.0.0.1" not in line:
            ip = line.split()[1]
            if ip not in seen:
                seen.add(ip)
                out.append(ip)
    # Private LAN ranges before tunnel/link-local addresses.
    out.sort(key=lambda a: (not (a.startswith("192.168.") or a.startswith("10.")), a))
    return out


def open_socket(ifaces: list) -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind(("", PORT))
    joined = []
    for ip in ifaces:
        try:
            mreq = socket.inet_aton(GROUP) + socket.inet_aton(ip)
            s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
            joined.append(ip)
        except OSError:
            pass
    try:  # belt and braces: the wildcard join too
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                     struct.pack("4sl", socket.inet_aton(GROUP), socket.INADDR_ANY))
    except OSError:
        pass
    print(f"joined {GROUP} on: {', '.join(joined) or '(wildcard only)'}", flush=True)
    s.settimeout(1.0)
    return s


def talker(line: str) -> str:
    head = line.split(",", 1)[0].strip()
    return head if head in CHANNELS else (head or "?")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary", type=float, metavar="SECONDS",
                    help="listen this long, then print a per-channel report")
    ap.add_argument("--await-first", action="store_true",
                    help="wait for the first sentence and report the wait")
    ap.add_argument("--timeout", type=float, default=300.0,
                    help="give up after this long with no traffic (default 300 s)")
    args = ap.parse_args()

    sock = open_socket(lan_ips())
    print(f"listening on {GROUP}:{PORT} (+ subnet broadcast)  —  ^C to stop", flush=True)

    counts: collections.Counter = collections.Counter()
    senders: collections.Counter = collections.Counter()
    started = time.monotonic()
    first_at = None

    try:
        while True:
            now = time.monotonic()
            if args.summary and now - started >= args.summary:
                break
            if first_at is None and now - started >= args.timeout:
                print(f"\nNOTHING HEARD in {args.timeout:.0f}s.", flush=True)
                print("  - is the phone on the same WiFi (not guest / not cellular)?", flush=True)
                print("  - is the beacon service running and auto-start enabled?", flush=True)
                print("  - if this follows a reboot: does the phone have a lock-screen", flush=True)
                print("    credential? BOOT_COMPLETED does not fire before first unlock.", flush=True)
                return 2
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue

            if first_at is None:
                first_at = time.monotonic()
                waited = first_at - started
                print(f"\nFIRST SENTENCE after {waited:.1f}s from {addr[0]}", flush=True)
                if args.await_first:
                    print("  the beacon came back on its own — no unlock needed.", flush=True)

            senders[addr[0]] += 1
            for raw in data.decode("ascii", "replace").splitlines():
                line = raw.strip()
                if not line:
                    continue
                counts[talker(line)] += 1
                if not args.summary and not args.await_first:
                    print(line, flush=True)

            if args.await_first and time.monotonic() - first_at > 5.0:
                break
    except KeyboardInterrupt:
        print("", flush=True)

    elapsed = time.monotonic() - started
    print(f"\n=== {elapsed:.0f}s, {sum(counts.values())} sentences "
          f"from {len(senders)} sender(s) ===", flush=True)
    if not counts:
        print("no traffic seen", flush=True)
        return 2
    for src, n in senders.most_common():
        print(f"  sender {src}: {n} datagrams", flush=True)
    print("  channel   count  meaning", flush=True)
    for head, n in counts.most_common():
        print(f"  {head:<9} {n:>6}  {CHANNELS.get(head, '(unrecognised)')}", flush=True)
    missing = [c for c in CHANNELS if c.startswith("$Z") and c not in counts]
    if missing:
        print(f"  proprietary channels NOT seen: {', '.join(missing)}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
