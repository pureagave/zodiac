"""Runner: detector -> ZTHREAT frames -> fleet bus, at a fixed rate.

    python -m zvision --source fake -v            # no hardware, exercise the HUD
    python -m zvision --source thermal --hz 10     # real Lepton on /dev/video0
    python -m zvision --once                        # single-frame smoke test

On exit it emits one empty "all clear" frame so the HUD clears immediately
instead of freezing on the last contacts.
"""

from __future__ import annotations

import argparse
import signal
import sys
import time
from typing import List, Optional

from . import fleet_bus
from .broadcaster import ThreatBroadcaster
from .detector import build_detector
from .threat_protocol import format_frame


def _parse_args(argv: Optional[List[str]]) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="zvision", description="Zodiac edge-box threat broadcaster"
    )
    p.add_argument("--source", choices=["fake", "thermal", "rgb"], default="fake")
    p.add_argument("--hz", type=float, default=10.0, help="broadcast rate (Hz)")
    p.add_argument("--hfov", type=float, default=57.0, help="camera horizontal FOV (deg)")
    p.add_argument("--group", default=fleet_bus.THREAT_GROUP)
    p.add_argument("--port", type=int, default=fleet_bus.THREAT_PORT)
    p.add_argument("--iface-ip", default=None, help="local IP of the vehicle-network NIC")
    p.add_argument("--bind-ip", default=None, help="bind sender to this source IP (multi-homed/VPN hosts)")
    p.add_argument("--broadcast", default=None, help="override subnet broadcast address")
    p.add_argument("--device", default="/dev/video0", help="camera device for thermal/rgb")
    p.add_argument("--width", type=int, default=160)
    p.add_argument("--height", type=int, default=120)
    p.add_argument("--once", action="store_true", help="emit one frame and exit")
    p.add_argument("--verbose", "-v", action="store_true")
    return p.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = _parse_args(argv)

    broadcaster = ThreatBroadcaster(
        group=args.group,
        port=args.port,
        iface_ip=args.iface_ip,
        bind_ip=args.bind_ip,
        broadcast=args.broadcast,
    )
    detector = build_detector(
        args.source, hfov_deg=args.hfov, device=args.device, width=args.width, height=args.height
    )

    running = {"go": True}

    def _stop(*_: object) -> None:
        running["go"] = False

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    period = 1.0 / max(args.hz, 0.1)
    start = time.monotonic()
    if args.verbose:
        print(
            f"zvision: source={args.source} -> {args.group}:{args.port} "
            f"+ subnet broadcast @ {args.hz}Hz",
            flush=True,
        )
    try:
        while running["go"]:
            t = time.monotonic() - start
            threats = detector.detect(t)
            frame = format_frame(threats)
            sent = broadcaster.send(frame)
            if args.verbose:
                print(f"[{t:7.2f}s] {len(threats):2d} contacts -> {sent} targets  {frame}", flush=True)
            if args.once:
                break
            time.sleep(period)
    finally:
        broadcaster.send(format_frame([]))  # all-clear so the HUD doesn't freeze
        detector.close()
        broadcaster.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
