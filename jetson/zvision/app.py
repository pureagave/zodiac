"""Runner: detector -> ZTHREAT frames -> fleet bus, at a fixed rate.

    python -m zvision --source fake -v            # no hardware, exercise the HUD
    python -m zvision --source thermal --hz 10     # real Lepton on /dev/video0
    python -m zvision --once                        # single-frame smoke test

A surround rig is N repeated ``--camera`` flags, each carrying where that camera
looks and what it looks through; their contacts are fused into one full-circle
list (see ``rig.py``)::

    python -m zvision \
      --camera thermal:/dev/video0:az=0:fov=160:lens=fisheye \
      --camera rgb:/dev/video2:az=120:fov=90:lens=pinhole \
      --camera rgb:/dev/video4:az=-120:fov=90:lens=pinhole -v

On exit it emits one empty "all clear" frame so the HUD clears immediately
instead of freezing on the last contacts.
"""

from __future__ import annotations

import argparse
import math
import signal
import socket
import sys
import time
from typing import List, Optional

from . import fleet_bus
from .broadcaster import ThreatBroadcaster
from .detector import DetectorTuning
from .geometry import FOV_DIAGONAL, LENS_EQUIDISTANT, LENS_MODELS
from .recorder import (
    DEFAULT_MAX_MB,
    DEFAULT_RECORD_HZ,
    FrameRecorder,
    RecorderConfig,
    summarize,
)
from .rig import (
    DEFAULT_DEDUP_DEG,
    CameraMount,
    build_rig,
    coverage_gaps,
    parse_camera_spec,
    validate_mount,
)
from .threat_protocol import format_frame


def _parse_args(argv: Optional[List[str]]) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="zvision", description="Zodiac edge-box threat broadcaster"
    )
    p.add_argument("--source", choices=["fake", "thermal", "rgb"], default="fake")
    p.add_argument("--hz", type=float, default=10.0, help="broadcast rate (Hz)")
    p.add_argument(
        "--hfov",
        type=float,
        default=160.0,
        help="single-camera FOV (deg); default suits the Lepton Ultra Wide",
    )
    p.add_argument(
        "--lens",
        choices=list(LENS_MODELS),
        default=LENS_EQUIDISTANT,
        help="single-camera projection model (fisheye lenses are 'equidistant')",
    )
    p.add_argument(
        "--fov-ref",
        choices=["h", "d"],
        default=FOV_DIAGONAL,
        help="is --hfov measured across the frame width (h) or its diagonal (d)? "
        "defaults to diagonal, which is how fisheye lenses are specified",
    )
    p.add_argument(
        "--camera",
        action="append",
        default=None,
        metavar="SPEC",
        help=(
            "add a camera to the rig: source[:device][:az=..][:fov=..][:fovref=h|d]"
            "[:lens=..][:name=..][:width=..][:height=..]. Repeat for a surround rig; "
            "overrides --source/--device/--hfov."
        ),
    )
    p.add_argument(
        "--merge-deg",
        type=float,
        default=DEFAULT_DEDUP_DEG,
        help="collapse contacts this close in bearing seen by different cameras (0 disables)",
    )

    # Field tuning. These are the numbers you can only get right once the rig is
    # bolted to the vehicle and real people are walking around it, so they're all
    # reachable without editing code: set a rig-wide default here, override per
    # camera in a --camera spec (minarea/match/farh/nearh/azrate/minsize).
    tune = p.add_argument_group(
        "field tuning",
        "rig-wide defaults; override per camera via --camera key=value",
    )
    _T = DetectorTuning()
    tune.add_argument(
        "--min-area", type=float, default=_T.min_area_frac,
        help="min blob area as a fraction of frame; raise to reject dust/shimmer, lower to see people further out",
    )
    tune.add_argument(
        "--match-dist", type=float, default=_T.match_dist,
        help="track association distance (normalised frame widths); raise if ids churn, lower if passers swap ids",
    )
    tune.add_argument(
        "--far-h", type=float, default=_T.far_h,
        help="bbox height fraction that reads as maximum range (size 0)",
    )
    tune.add_argument(
        "--near-h", type=float, default=_T.near_h,
        help="bbox height fraction that reads as closest range (size 1)",
    )
    tune.add_argument(
        "--collision-az-rate", type=float, default=_T.collision_az_rate_dps,
        help="max bearing drift (deg/s) still counted as constant-bearing; lower = stricter/fewer alarms",
    )
    tune.add_argument(
        "--collision-min-size", type=float, default=_T.collision_min_size,
        help="how near a contact must be (0..1) before it can trip the collision flag",
    )

    p.add_argument(
        "--check",
        action="store_true",
        help="validate the config, print the resolved rig, and exit without opening cameras or the network",
    )

    # Recording. Frames can only be captured while the rig is on the vehicle;
    # GPU time can be rented whenever. That asymmetry makes this the
    # schedule-critical half of the detector roadmap — see DETECTOR.md.
    rec = p.add_argument_group("recording", "dump frames + weak labels for model training")
    rec.add_argument("--record", metavar="DIR", default=None, help="write frames and index.jsonl here")
    rec.add_argument(
        "--record-hz", type=float, default=DEFAULT_RECORD_HZ,
        help="frames per second per camera to keep (default 1; consecutive frames at full rate are near-duplicates)",
    )
    rec.add_argument(
        "--record-max-mb", type=int, default=DEFAULT_MAX_MB,
        help="stop recording once the dump reaches this size, so a long night can't fill the boot disk",
    )
    rec.add_argument("--record-quality", type=int, default=85, help="JPEG quality for RGB frames (thermal is PNG)")

    p.add_argument(
        "--fourcc", default="", metavar="MJPG",
        help="pixel format to request from every camera (blank = driver default; MJPG for RGB on a shared USB bus)",
    )
    p.add_argument("--fps", type=float, default=None, help="frame rate to request from every camera")
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
    p.add_argument(
        "--dmx-channels",
        type=int,
        choices=[9, 11],
        default=11,
        help="fixture DMX personality (MOVING-HEAD.md 3.1/3.2). 11 = 16-bit "
        "pan/tilt, the vehicle's head is set to this; 9 = coarse fallback",
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


def _mounts_from_args(args: argparse.Namespace) -> List[CameraMount]:
    """The rig described by the CLI: explicit ``--camera`` specs if given, else
    the legacy single forward camera. Either way the global flags supply the
    defaults, so a ``--camera`` spec only has to state what makes that camera
    different from the rig."""
    defaults = CameraMount(
        name=args.source,
        source=args.source,
        device=args.device,
        mount_az_deg=0.0,
        fov_deg=args.hfov,
        fov_ref=args.fov_ref,
        lens=args.lens,
        width=args.width,
        height=args.height,
        fourcc=args.fourcc,
        fps=args.fps,
        tuning=DetectorTuning(
            min_area_frac=args.min_area,
            match_dist=args.match_dist,
            far_h=args.far_h,
            near_h=args.near_h,
            collision_az_rate_dps=args.collision_az_rate,
            collision_min_size=args.collision_min_size,
        ),
    )
    if args.camera:
        return [
            parse_camera_spec(spec, index=i, defaults=defaults)
            for i, spec in enumerate(args.camera)
        ]
    # The legacy single-camera path takes its numbers straight from argparse,
    # which accepts "nan"/"inf" as floats — run it through the same gate the
    # --camera specs go through, so --check means the same thing on both.
    return [validate_mount(defaults)]


def _validate_runtime_args(args: argparse.Namespace) -> None:
    """Catch the runtime arguments that would otherwise fail *after* --check
    passed them — the service is Restart=always, so anything that raises at
    startup is a crash loop (a malformed --iface-ip died inside inet_aton with
    a bare traceback), and anything the socket layer only rejects per-send is
    worse: a healthy-looking service broadcasting to nobody (a typo'd --group
    just counts send errors forever). --hz gets the same treatment because
    "inf" parses: period 0 is a hot loop flooding the vehicle network."""
    if not (math.isfinite(args.hz) and args.hz > 0):
        raise ValueError(f"--hz must be a positive, finite rate, got {args.hz}")
    if not math.isfinite(args.merge_deg):
        raise ValueError(f"--merge-deg must be finite, got {args.merge_deg}")
    if not 1 <= args.port <= 65535:
        raise ValueError(f"--port must be 1..65535, got {args.port}")
    for flag, value in (
        ("--group", args.group),
        ("--iface-ip", args.iface_ip),
        ("--bind-ip", args.bind_ip),
        ("--broadcast", args.broadcast),
    ):
        if value in (None, ""):
            continue
        try:
            socket.inet_aton(value)
        except OSError:
            raise ValueError(f"{flag}: {value!r} is not an IPv4 address") from None
    if args.record is not None and not (math.isfinite(args.record_hz) and args.record_hz >= 0):
        raise ValueError(f"--record-hz must be a finite rate, got {args.record_hz}")
    if args.dmx != "none":
        for flag, value in (
            ("--dmx-pan-center", args.dmx_pan_center),
            ("--dmx-pan-gain", args.dmx_pan_gain),
        ):
            if not math.isfinite(value):
                # A NaN aim parks the head at 0 forever while looking configured.
                raise ValueError(f"{flag} must be finite, got {value}")


def _print_rig(mounts: List[CameraMount], show_tuning: bool = False) -> None:
    """The resolved rig, as the runner actually understands it — every camera's
    arc plus any bearing no camera can see."""
    for m in mounts:
        left, right = m.arc()
        print(
            f"  {m.name:>10}: {m.source} {m.device} az={m.mount_az_deg:+.0f}° "
            f"fov={m.fov_deg:.0f}°{m.fov_ref} {m.lens} -> covers {left:+.0f}°..{right:+.0f}°",
            flush=True,
        )
        if show_tuning:
            t = m.tuning
            print(
                f"              tuning: minarea={t.min_area_frac:g} match={t.match_dist:g} "
                f"farh={t.far_h:g} nearh={t.near_h:g} "
                f"azrate={t.collision_az_rate_dps:g} minsize={t.collision_min_size:g}",
                flush=True,
            )
    gaps = coverage_gaps(mounts)
    if gaps:
        arcs = ", ".join(f"{a:+.0f}°..{b:+.0f}°" for a, b in gaps)
        print(f"  blind: {arcs}", flush=True)
    else:
        print("  blind: none — the ring closes", flush=True)


def main(argv: Optional[List[str]] = None) -> int:
    args = _parse_args(argv)

    try:
        mounts = _mounts_from_args(args)
        _validate_runtime_args(args)
    except ValueError as exc:
        print(f"zvision: {exc}", file=sys.stderr, flush=True)
        return 2

    if args.check:
        # Dry-run the config only. The service runs Restart=always, so a bad
        # arg written into /etc/default/zvision becomes a crash loop — check it
        # here first, especially when the only terminal is a laptop in the dust.
        print(f"zvision: config OK — {len(mounts)} camera(s)", flush=True)
        _print_rig(mounts, show_tuning=True)
        print(f"  merge: {args.merge_deg:g}°   rate: {args.hz:g} Hz   bus: {args.group}:{args.port}", flush=True)
        return 0

    broadcaster = ThreatBroadcaster(
        group=args.group,
        port=args.port,
        iface_ip=args.iface_ip,
        bind_ip=args.bind_ip,
        broadcast=args.broadcast,
    )
    recorder = None
    if args.record:
        recorder = FrameRecorder(
            RecorderConfig(
                directory=args.record,
                hz=args.record_hz,
                jpeg_quality=args.record_quality,
                max_mb=args.record_max_mb,
            )
        )

    detector = build_rig(mounts, dedup_deg=args.merge_deg, recorder=recorder)
    if not detector.mounts:
        # Every camera failed to open. Better to exit loudly than to sit there
        # broadcasting a confident "all clear" while completely blind.
        print("zvision: no cameras opened — nothing to detect with", file=sys.stderr, flush=True)
        broadcaster.close()
        return 3
    mounts = detector.mounts  # whatever actually opened

    tracker = None
    dmx_sink = None
    zaud = None
    if args.dmx != "none":
        from .dmx import build_sink
        from .tracker import Tracker, config_for_channel_mode

        tracker = Tracker(
            config_for_channel_mode(
                args.dmx_channels,
                pan_center_deg=args.dmx_pan_center,
                pan_gain=args.dmx_pan_gain,
            )
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
            f"zvision: {len(mounts)} camera(s) -> {args.group}:{args.port} "
            f"+ subnet broadcast @ {args.hz}Hz",
            flush=True,
        )
        _print_rig(mounts)
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
        if recorder is not None:
            recorder.close()
            if args.verbose:
                print(f"zvision: recorded {summarize(recorder)} -> {args.record}", flush=True)
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
