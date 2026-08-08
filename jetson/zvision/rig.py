"""The camera rig: N cameras around the vehicle fused into one full-circle
threat picture.

A single forward camera could get away with treating "bearing in frame" and
"bearing off the nose" as the same number. A ring can't — the rear RGB's dead
centre is 180° off the nose, and two neighbouring cameras will each see the same
person in their overlap. So a rig is a list of :class:`CameraMount`s (where the
camera points, how wide it sees, what lens it sees through) and this module
turns their independent per-camera contact lists into one merged list of global
bearings:

    camera-local az  --(+ mount_az)-->  global az  --(dedup overlap)-->  merged

Everything here is pure list/angle arithmetic over the ``Detector`` protocol, so
the whole fusion path is testable with fake cameras long before five real ones
are bolted to a car. Only :func:`build_camera` touches hardware, and it defers
its imports exactly like ``detector.py`` does.
"""

from __future__ import annotations

import inspect
import math
import sys
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

from .detector import Detector, DetectorTuning, FakeDetector, MotionDetector
from .normalize import TRACK_ID_LIMIT
from .geometry import (
    FOV_DIAGONAL,
    FOV_HORIZONTAL,
    FOV_REFS,
    LENS_EQUIDISTANT,
    LENS_MODELS,
    LENS_RECTILINEAR,
    pixel_to_bearing,
    wrap180,
)
from .threat import DriverThreat

SOURCES = ("fake", "thermal", "rgb")

# Track ids are per-camera and all start at 1, so they must be namespaced before
# the merged list leaves the rig — otherwise the tracker light would latch onto
# "id 1" and get handed a different person every frame as cameras take turns.
# Camera k owns the id block [k*ID_STRIDE, (k+1)*ID_STRIDE).
# Must equal the detector's id wrap point, or the composition below aliases —
# see normalize.TRACK_ID_LIMIT for what that costs.
ID_STRIDE = TRACK_ID_LIMIT

# Two cameras seeing the same person in their overlap must not become two
# contacts. Anything closer than this in bearing, seen by *different* cameras,
# is treated as one contact (see :func:`merge_contacts`).
DEFAULT_DEDUP_DEG = 8.0

# Friendlier spellings accepted in a --camera spec.
_LENS_ALIASES = {
    "fisheye": LENS_EQUIDISTANT,
    "ftheta": LENS_EQUIDISTANT,
    "f-theta": LENS_EQUIDISTANT,
    "pinhole": LENS_RECTILINEAR,
    "normal": LENS_RECTILINEAR,
}
_FOV_REF_ALIASES = {
    "h": FOV_HORIZONTAL,
    "horizontal": FOV_HORIZONTAL,
    "hfov": FOV_HORIZONTAL,
    "d": FOV_DIAGONAL,
    "diag": FOV_DIAGONAL,
    "diagonal": FOV_DIAGONAL,
    "dfov": FOV_DIAGONAL,
}


@dataclass(frozen=True)
class CameraMount:
    """One camera's identity, optics, and where it looks.

    ``mount_az_deg`` is the bearing of the optical axis off the vehicle's nose,
    positive to the right: 0 = forward, 90 = starboard, 180 = astern, -90 =
    port. It is the *only* thing that turns a camera-local bearing into a
    vehicle-global one."""

    name: str
    source: str = "fake"
    device: str = "/dev/video0"
    mount_az_deg: float = 0.0
    fov_deg: float = 160.0
    fov_ref: str = FOV_HORIZONTAL
    lens: str = LENS_EQUIDISTANT
    width: int = 160
    height: int = 120
    # Pixel format asked of the driver. Empty = leave the driver's default
    # alone, which is right for the thermal (LWIR is raw/greyscale, not MJPEG).
    # Set MJPG on the RGB ring cameras: uncompressed declares ~a gigabit each
    # and several of them will not coexist on one USB bus.
    fourcc: str = ""
    fps: Optional[float] = None
    tuning: DetectorTuning = field(default_factory=DetectorTuning)

    def half_h_fov_deg(self) -> float:
        """The *horizontal* half-angle this camera actually covers, through its
        lens model and FOV reference — i.e. the true bearing of the frame's
        side edge on the centreline.

        For a width-referenced FOV this is exactly ``fov/2`` for every lens
        model. For a *diagonal*-referenced FOV it is materially narrower —
        160° diagonal over a 4:3 sensor is only ±64° horizontal — and
        crediting ``fov/2`` anyway made the bring-up banner print
        "blind: none — the ring closes" over arcs no camera could see, which
        is a confident all-clear pointed at exactly the person it would miss."""
        aspect = self.height / self.width if self.width else 1.0
        az, _ = pixel_to_bearing(1.0, 0.5, self.fov_deg, aspect, self.lens, self.fov_ref)
        return abs(az)

    def arc(self) -> Tuple[float, float]:
        """The (left, right) global bearings this camera's frame spans — handy
        for checking a rig actually closes the circle."""
        half = self.half_h_fov_deg()
        return wrap180(self.mount_az_deg - half), wrap180(self.mount_az_deg + half)


def _parse_float(key: str, raw: str) -> float:
    # Note float() happily accepts "nan"/"inf"; rejecting those is
    # validate_mount's job, so both config paths get the same gate.
    try:
        return float(raw)
    except ValueError:
        raise ValueError(f"camera spec: {key}={raw!r} is not a number") from None


def _parse_int(key: str, raw: str) -> int:
    try:
        return int(raw)
    except ValueError:
        raise ValueError(f"camera spec: {key}={raw!r} is not an integer") from None


def validate_mount(mount: CameraMount) -> CameraMount:
    """Reject a mount whose numbers cannot drive the geometry — the shared gate
    for both config paths (``--camera`` specs and the legacy single-camera
    flags), so ``--check`` actually stands between a typo and the vehicle.

    The dangerous cases are the quiet ones. ``float("nan")`` parses, and every
    downstream guard is a comparison NaN answers False to: a nan mount angle
    made every bearing from that camera the string ``"nan"`` on the wire
    (silently dropped by the tablet — green service, blind HUD), and a nan
    ``far_h`` slipped past the inverted-calibration guard so every contact
    read as size 1.0 — reported as touching the vehicle."""
    prefix = f"camera {mount.name!r}:"
    if not mount.name or any(sep in mount.name for sep in ("/", "\\")) or mount.name in (".", ".."):
        raise ValueError(
            f"camera name {mount.name!r} must be a plain name — it becomes a "
            "recording directory, so path separators would scatter frames"
        )
    if not math.isfinite(mount.mount_az_deg):
        raise ValueError(f"{prefix} mount az must be finite, got {mount.mount_az_deg}")
    if not (math.isfinite(mount.fov_deg) and mount.fov_deg > 0):
        raise ValueError(f"{prefix} fov must be a positive, finite number of degrees, got {mount.fov_deg}")
    if mount.width <= 0 or mount.height <= 0:
        raise ValueError(f"{prefix} width/height must be positive, got {mount.width}x{mount.height}")
    if mount.fourcc and len(mount.fourcc) != 4:
        raise ValueError(f"{prefix} fourcc must be 4 characters, got {mount.fourcc!r}")
    if mount.fps is not None and not (math.isfinite(mount.fps) and mount.fps > 0):
        raise ValueError(f"{prefix} fps must be positive and finite, got {mount.fps}")
    t = mount.tuning
    for label, value in (
        ("min-area/minarea", t.min_area_frac),
        ("match-dist/match", t.match_dist),
        ("far-h/farh", t.far_h),
        ("near-h/nearh", t.near_h),
        ("collision-az-rate/azrate", t.collision_az_rate_dps),
        ("collision-min-size/minsize", t.collision_min_size),
    ):
        if not math.isfinite(value):
            raise ValueError(f"{prefix} {label} must be a finite number, got {value}")
    # `not (a > b)` rather than `a <= b`: the two differ exactly on NaN, and
    # NaN is the case that turned this guard off entirely.
    if not (t.near_h > t.far_h):
        raise ValueError(
            f"{prefix} near-h/nearh ({t.near_h}) must exceed far-h/farh ({t.far_h}) "
            "— otherwise every contact reads as maximum range"
        )
    return mount


def parse_camera_spec(
    spec: str, index: int = 0, defaults: Optional[CameraMount] = None
) -> CameraMount:
    """Parse one ``--camera`` argument.

        source[:device][:key=value...]

    e.g. ``thermal:/dev/video0:az=0:fov=160:lens=fisheye`` or a bare ``fake:az=90``.

    **Prefer a stable device name.** ``/dev/videoN`` numbering follows USB
    enumeration order and is *not* stable across reboots — on this rig the
    thermal and RGB swapped nodes on a cold boot, which silently ran one camera
    through the other's code path. Use ``/dev/v4l/by-path/...``, which is tied
    to the physical USB port, so a port keeps its identity and therefore its
    mount angle. (``by-id`` is not safe for the ring: identical cameras report
    identical serials — the Arducams all say ``SN0001``.) Such names contain
    colons, which this parser handles.

    Optics/mount keys: ``az`` ``fov`` ``fovref`` (h|d) ``lens`` ``name``
    ``width`` ``height``.
    Field-tuning keys (see :class:`DetectorTuning`): ``minarea`` ``match``
    ``farh`` ``nearh`` ``azrate`` ``minsize``.

    ``defaults`` supplies the fallback for anything the spec doesn't set, so the
    global CLI flags act as rig-wide defaults and a spec only states what makes
    *that* camera different.

    Raises ``ValueError`` with a specific message on anything unrecognised — a
    typo'd mount angle points a real spotlight at the wrong person, and a typo'd
    key silently accepted at 3am on playa is worse, so this fails loudly rather
    than defaulting."""
    base = defaults or CameraMount(name="")
    fields = [f for f in spec.split(":") if f != ""]
    if not fields:
        raise ValueError("camera spec is empty")
    source = fields[0].strip().lower()
    if source not in SOURCES:
        raise ValueError(f"camera spec: unknown source {source!r} (want one of {', '.join(SOURCES)})")

    rest = fields[1:]
    device = base.device
    # The device may itself contain colons — a stable /dev/v4l/by-path/ name
    # looks like ".../platform-3610000.usb-usb-0:2.3:1.0-video-index0", and
    # by-path is the *correct* way to name a camera here (see below). So
    # consume every leading field until one looks like key=value, and rejoin.
    # Option keys never contain "=" in their name, and paths never contain "=",
    # so the split is unambiguous.
    dev_parts = []
    while rest and "=" not in rest[0]:
        dev_parts.append(rest.pop(0))
    if dev_parts:
        device = ":".join(dev_parts).strip()

    kw: Dict[str, str] = {}
    for field_ in rest:
        if "=" not in field_:
            raise ValueError(f"camera spec: expected key=value, got {field_!r}")
        key, _, value = field_.partition("=")
        kw[key.strip().lower()] = value.strip()

    name = kw.pop("name", f"{source}{index}")
    az = _parse_float("az", kw.pop("az", "0"))
    fov = _parse_float("fov", kw.pop("fov", str(base.fov_deg)))
    width = _parse_int("width", kw.pop("width", str(base.width)))
    height = _parse_int("height", kw.pop("height", str(base.height)))

    fourcc = kw.pop("fourcc", base.fourcc).strip().upper()
    fps_raw = kw.pop("fps", "" if base.fps is None else str(base.fps))
    fps = _parse_float("fps", fps_raw) if fps_raw else None

    lens_raw = kw.pop("lens", base.lens).lower()
    lens = _LENS_ALIASES.get(lens_raw, lens_raw)
    if lens not in LENS_MODELS:
        raise ValueError(f"camera spec: unknown lens {lens_raw!r} (want one of {', '.join(LENS_MODELS)})")

    ref_raw = kw.pop("fovref", base.fov_ref).lower()
    fov_ref = _FOV_REF_ALIASES.get(ref_raw, ref_raw)
    if fov_ref not in FOV_REFS:
        raise ValueError(f"camera spec: fovref must be h or d, got {ref_raw!r}")

    t = base.tuning
    tuning = DetectorTuning(
        min_area_frac=_parse_float("minarea", kw.pop("minarea", str(t.min_area_frac))),
        match_dist=_parse_float("match", kw.pop("match", str(t.match_dist))),
        far_h=_parse_float("farh", kw.pop("farh", str(t.far_h))),
        near_h=_parse_float("nearh", kw.pop("nearh", str(t.near_h))),
        collision_az_rate_dps=_parse_float("azrate", kw.pop("azrate", str(t.collision_az_rate_dps))),
        collision_min_size=_parse_float("minsize", kw.pop("minsize", str(t.collision_min_size))),
    )

    if kw:
        raise ValueError(f"camera spec: unknown key(s) {', '.join(sorted(kw))}")

    return validate_mount(
        CameraMount(
            name=name,
            source=source,
            device=device,
            mount_az_deg=wrap180(az),
            fov_deg=fov,
            fov_ref=fov_ref,
            lens=lens,
            width=width,
            height=height,
            fourcc=fourcc,
            fps=fps,
            tuning=tuning,
        )
    )


def to_global(threat: DriverThreat, mount: CameraMount, cam_index: int) -> DriverThreat:
    """Rotate one camera-local contact into vehicle-global terms: bearing
    measured off the nose, and a track id namespaced to this camera so ids from
    different cameras can never collide."""
    # Detectors are contracted to mint local ids in [1, ID_STRIDE) — see
    # normalize.TRACK_ID_LIMIT, which wraps them so a long run cannot escape the
    # block. The modulo here is belt-and-braces for a future detector (a trained
    # model, ByteTrack) with its own id scheme.
    #
    # No finite mapping can keep unbounded ids distinct, so this cannot promise
    # uniqueness. What it must never do is fold a *real* track onto 0: that is
    # the "ad-hoc, never latch" sentinel, and a contact silently marked
    # un-latchable is one the DMX light refuses to hold.
    local_id = threat.id % ID_STRIDE
    if threat.id != 0 and local_id == 0:
        local_id = ID_STRIDE - 1
    global_id = 0 if local_id == 0 else cam_index * ID_STRIDE + local_id
    return DriverThreat(
        rel_az_deg=wrap180(threat.rel_az_deg + mount.mount_az_deg),
        size=threat.size,
        collision=threat.collision,
        id=global_id,
    )


def merge_contacts(
    per_camera: Sequence[Sequence[DriverThreat]],
    dedup_deg: float = DEFAULT_DEDUP_DEG,
) -> List[DriverThreat]:
    """Fuse per-camera contact lists (already in global bearings) into one
    full-circle list, collapsing the same person seen by two overlapping
    cameras into a single contact.

    Only *cross-camera* neighbours are collapsed: two contacts a few degrees
    apart in one camera's own frame are two people it deliberately resolved,
    while two contacts a few degrees apart in adjacent cameras' overlap are
    almost certainly one person. Ties break toward the more urgent contact
    (collision first, then nearer), so the survivor is the one the driver and
    the tracker light most need. ``dedup_deg <= 0`` disables the collapse.

    Returns contacts ordered by bearing, port to starboard — a stable, readable
    sweep of the circle rather than whatever order the cameras happened to be
    polled in."""
    flat: List[Tuple[int, DriverThreat]] = [
        (cam, t) for cam, contacts in enumerate(per_camera) for t in contacts
    ]
    if dedup_deg <= 0:
        return sorted((t for _, t in flat), key=lambda t: (t.rel_az_deg, t.id))

    # Most urgent first, so the contact that survives a collapse is the one
    # worth keeping. id breaks ties to keep the result deterministic.
    ranked = sorted(flat, key=lambda ct: (not ct[1].collision, -ct[1].size, ct[1].id))
    kept: List[Tuple[int, DriverThreat]] = []
    for cam, t in ranked:
        duplicate = any(
            other_cam != cam and abs(wrap180(t.rel_az_deg - other.rel_az_deg)) <= dedup_deg
            for other_cam, other in kept
        )
        if not duplicate:
            kept.append((cam, t))
    return sorted((t for _, t in kept), key=lambda t: (t.rel_az_deg, t.id))


class MultiDetector:
    """Runs every camera in the rig for one timestamp and returns the merged
    full-circle contact list. Implements the same ``detect``/``close`` protocol
    as a single detector, so ``app.py`` doesn't care how many cameras there are.

    A camera that throws (unplugged USB, a driver hiccup) is dropped for that
    frame instead of taking the whole rig down — losing one arc of the circle
    beats losing the HUD and the tracker light."""

    def __init__(
        self,
        cameras: Sequence[Tuple[CameraMount, Detector]],
        dedup_deg: float = DEFAULT_DEDUP_DEG,
    ) -> None:
        self._cameras = list(cameras)
        self._dedup_deg = dedup_deg
        self._failed: Set[str] = set()

    @property
    def mounts(self) -> List[CameraMount]:
        return [m for m, _ in self._cameras]

    def detect(self, t: float) -> List[DriverThreat]:
        per_camera: List[List[DriverThreat]] = []
        for index, (mount, detector) in enumerate(self._cameras):
            per_camera.append(self._detect_one(index, mount, detector, t))
        return merge_contacts(per_camera, self._dedup_deg)

    def _detect_one(
        self, index: int, mount: CameraMount, detector: Detector, t: float
    ) -> List[DriverThreat]:
        try:
            local = detector.detect(t)
        # Broad by design: this is the hardware/driver boundary, and one dead
        # camera must not end the run. Reported once per camera, then quiet.
        except Exception as exc:  # noqa: BLE001
            if mount.name not in self._failed:
                self._failed.add(mount.name)
                print(f"zvision: camera {mount.name} failing, dropped: {exc}", file=sys.stderr, flush=True)
            return []
        self._failed.discard(mount.name)
        return [to_global(c, mount, index) for c in local]

    def close(self) -> None:
        for _, detector in self._cameras:
            closer = getattr(detector, "close", None)
            if closer is None:
                continue
            try:
                closer()
            except Exception as exc:  # noqa: BLE001 - shutdown must not raise
                print(f"zvision: camera close failed: {exc}", file=sys.stderr, flush=True)


def build_camera(mount: CameraMount, recorder=None) -> Detector:
    """Factory for one mount: ``fake`` needs nothing; ``thermal``/``rgb`` open a
    UVC device and wrap it in the motion detector with that camera's optics.
    Camera/cv2 imports stay lazy so the fake rig is pure stdlib."""
    if mount.source == "fake":
        return FakeDetector()

    if mount.source == "thermal":
        # The Lepton needs raw Y16 + our own stretch; its 8-bit output is flat
        # enough to detect nothing at all. See capture.ThermalCamera.
        from .capture import ThermalCamera

        camera = ThermalCamera(mount.device, width=mount.width, height=mount.height)
    else:
        from .capture import UvcCamera

        camera = UvcCamera(
            mount.device,
            width=mount.width,
            height=mount.height,
            fourcc=mount.fourcc,
            fps=mount.fps,
        )
    return MotionDetector(
        camera,
        fov_deg=mount.fov_deg,
        lens=mount.lens,
        fov_ref=mount.fov_ref,
        tuning=mount.tuning,
        recorder=recorder,
        name=mount.name,
    )


def build_rig(
    mounts: Iterable[CameraMount],
    dedup_deg: float = DEFAULT_DEDUP_DEG,
    factory=build_camera,
    recorder=None,
) -> MultiDetector:
    """Open every mount in the rig, skipping any camera that won't open.

    Same call as the per-frame guard in :class:`MultiDetector`, one stage
    earlier: on a vehicle where five USB cameras enumerate in whatever order
    they feel like, one missing ``/dev/videoN`` must cost you that arc, not the
    entire night's detection. The caller decides what to do if *nothing* opened
    — check :attr:`MultiDetector.mounts` before running.

    ``factory`` is injectable so tests can build a whole rig without hardware."""
    opened: List[Tuple[CameraMount, Detector]] = []
    for mount in mounts:
        try:
            # Tests inject a plain one-arg factory; the real one also takes the
            # shared recorder so every camera writes into one byte budget.
            # Decided by signature, not by catching TypeError — that would
            # silently swallow a genuine TypeError raised inside the factory.
            takes_recorder = len(inspect.signature(factory).parameters) >= 2
            detector = factory(mount, recorder) if takes_recorder else factory(mount)
            opened.append((mount, detector))
        # Broad by design: OpenCV/V4L2 raise a wide variety of things for a
        # device that isn't there, and none of them should end the run.
        except Exception as exc:  # noqa: BLE001
            print(
                f"zvision: camera {mount.name} ({mount.device}) would not open, skipped: {exc}",
                file=sys.stderr,
                flush=True,
            )
    return MultiDetector(opened, dedup_deg=dedup_deg)


def coverage_gaps(mounts: Sequence[CameraMount], step_deg: float = 1.0) -> List[Tuple[float, float]]:
    """Bearings around the circle no camera can see, as (start, end) arcs swept
    clockwise — ``end`` may exceed 180 for a gap straddling dead astern.

    A bring-up aid: print it once and you know whether the ring actually closes,
    rather than discovering the blind arc when somebody standing in it never
    appears on the HUD."""
    if step_deg <= 0:
        step_deg = 1.0
    steps = max(1, int(round(360.0 / step_deg)))
    step = 360.0 / steps

    def bearing(i: int) -> float:
        return wrap180(-180.0 + (i % steps) * step)

    # Effective horizontal coverage, not the raw quoted FOV: a diagonal-
    # referenced 160° is only ±64° wide on a 4:3 sensor, and overstating it
    # here reported a closed ring around real blind arcs.
    # The tolerance absorbs the float error of the lens round-trip (atan2 of
    # sin/cos loses ~1e-14 deg), which otherwise reports a phantom 1° gap when
    # two cameras meet exactly edge-to-edge.
    spans = [(m.mount_az_deg, m.half_h_fov_deg() + 1e-9) for m in mounts]
    covered = [
        any(abs(wrap180(bearing(i) - az)) <= half for az, half in spans)
        for i in range(steps)
    ]
    if all(covered):
        return []
    if not any(covered):
        return [(-180.0, 180.0)]

    # Start scanning from a covered bearing so a seam-straddling gap is one run,
    # not two half-runs at either end of the array.
    origin = covered.index(True)
    gaps: List[Tuple[float, float]] = []
    start: Optional[int] = None
    for offset in range(steps + 1):
        i = origin + offset
        is_covered = covered[i % steps]
        if not is_covered and start is None:
            start = i
        elif is_covered and start is not None:
            gaps.append((bearing(start), bearing(start) + (i - start) * step))
            start = None
    return gaps
