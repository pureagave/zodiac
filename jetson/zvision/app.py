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
    p.add_argument(
        "--dmx",
        choices=["none", "fake", "ola"],
        default="none",
        help="drive a moving-head tracker light: fake=log only, ola=send to local olad",
    )
    p.add_argument("--dmx-universe", type=int, default=0, help="OLA universe id")
    p.add_argument("--dmx-url", default="http://127.0.0.1:9090", help="olad HTTP API base URL")
    p.add_argument("--dmx-pan-center", type=float, default=270.0, help="fixture pan (deg) for az=0")
    p.add_argument("--dmx-pan-gain", type=float, default=1.0, help="fixture pan deg per az deg")
    p.add_argument(
        "--dmx-no-sound",
        action="store_true",
        help="disable the idle sound-reactive light show (ignore the beacon's $ZAUD)",
    )
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

    tracker = None
    dmx_sink = None
    zaud = None
    if args.dmx != "none":
        from .dmx import build_sink
        from .tracker import Tracker, TrackerConfig

        tracker = Tracker(
            TrackerConfig(pan_center_deg=args.dmx_pan_center, pan_gain=args.dmx_pan_gain)
        )
        dmx_sink = build_sink(args.dmx, universe=args.dmx_universe, base_url=args.dmx_url)
        if not args.dmx_no_sound:
            from .audio_bus import ZaudListener

            zaud = ZaudListener()
            zaud.start()  # idle sound show pulses the head to the beacon's $ZAUD

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
    last_t: Optional[float] = None
    try:
        while running["go"]:
            t = time.monotonic() - start
            threats = detector.detect(t)
            frame = format_frame(threats)
            sent = broadcaster.send(frame)
            if args.verbose:
                print(f"[{t:7.2f}s] {len(threats):2d} contacts -> {sent} targets  {frame}", flush=True)
            if tracker is not None and dmx_sink is not None:
                dt = 1.0e9 if args.once else (period if last_t is None else t - last_t)
                audio = zaud.latest() if zaud is not None else None
                tf = tracker.update(threats, dt, audio)
                dmx_sink.send(tf.channels)
                if args.verbose:
                    aim = f"id={tf.target_id}" if tf.target_id is not None else "idle"
                    print(
                        f"          dmx: {aim:>7} pan={tf.pan_deg:6.1f} tilt={tf.tilt_deg:6.1f} "
                        f"dim={tf.dimmer:3d}",
                        flush=True,
                    )
            last_t = t
            if args.once:
                break
            time.sleep(period)
    finally:
        broadcaster.send(format_frame([]))  # all-clear so the HUD doesn't freeze
        if tracker is not None and dmx_sink is not None:
            dmx_sink.send(tracker.park().channels)  # rest + black out the head
            dmx_sink.close()
        if zaud is not None:
            zaud.close()
        detector.close()
        broadcaster.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
