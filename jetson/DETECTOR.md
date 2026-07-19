# Detector roadmap — from motion blobs to a trained thermal model

Today's `MotionDetector` is deliberately model-free so bring-up needs no ML
stack. This is the path to the real thing. The key architectural fact: **the
detector is the only piece that changes.** Everything downstream —
`geometry.py` (box → az/size), `CollisionEstimator`, `ThreatBroadcaster`, the
wire format, the HUD — is fixed. A new detector just has to emit boxes+classes;
swapping it touches one file.

```
 frames ──▶ [ DETECTOR ] ──▶ boxes+classes ──▶ geometry ──▶ DriverThreat ──▶ bus ──▶ HUD
             ▲ this is the only thing that evolves below
```

## Stages

**Stage 0 — motion blobs (done, shipping now).** Background subtraction →
contours. No model, no labels. Purpose: prove the camera + geometry + bus + HUD
on real hardware the day it's plugged in. Weakness: reacts to *any* motion (dust,
shadows), no class, ids flicker.

**Stage 1 — off-the-shelf RGB detector (daytime real detections).** Drop in a
COCO-pretrained detector (YOLOv8/v11-nano or NanoDet), keep only `person` and
`bicycle`, export to **TensorRT** for the Orin. Gets us true person/bike
detection in daylight with zero training. This is the first "real" milestone and
it's mostly integration, not research.

**Stage 2 — thermal.** The RGB model transfers poorly to 160×120 LWIR (domain
gap). Two moves: (a) bootstrap from public thermal sets — **FLIR ADAS**,
Teledyne — to get a working night detector fast; (b) start collecting our *own*
clips from the actual Lepton at night on the playa, since our sensor + scene are
unique.

**Stage 3 — fine-tune on the H100 (the real model).** You have H100 access; this
is where it pays off.
- **Dataset:** self-collected thermal + RGB from the real cameras + public
  thermal to fill gaps. Record during test drives (the `zvision` capture path can
  dump frames).
- **Label taxonomy** (what you asked for): `person`, `person_on_bike`, and a
  **count bucket** (`1` vs `2+`) so the HUD can distinguish a lone walker from a
  group and a rider from a pedestrian.
- **Train** a YOLO-class detector, **export ONNX → TensorRT** for the Orin.
- **Validate** range: useful warning at vehicle speed means reliable person
  detection at ≥30 m; measure precision/recall by range bucket.

**Stage 4 — day/night fusion.** Late fusion is simplest and robust: run RGB by
day, thermal by night (or both), merge detections by bearing before geometry.
Auto-select on ambient light / time. Early (pixel-level) fusion is a later
optimization, not needed for v1.

## Tracking & collision

`CollisionEstimator` (constant-bearing / decreasing-range) stays. What it needs
is *stable ids* — upgrade the current nearest-centroid matcher to **ByteTrack or
SORT** once a real detector feeds it, so a contact keeps its id across frames and
the bearing-rate estimate is clean.

## Latency budget (Orin Nano Super)

- Target **~10 Hz** end-to-end (capture → detect → broadcast). The bus and HUD
  are already comfortably faster than that.
- Nano-class detectors at 160×120–640×480 on TensorRT clear 10 Hz with headroom;
  spend the rest on tracking + the second camera.

## Data collection now (before the H100 window)

Every test drive is training data. Add a `--record <dir>` dump path to the
capture loop so thermal+RGB frames are saved with timestamps; label later. The
sooner real playa/night clips exist, the better the Stage 3 model — the H100 is
useless without our own data, and that we *can* gather ahead of time.
