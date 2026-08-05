# Training plan — what actually needs training, and what doesn't

Companion to [DETECTOR.md](DETECTOR.md), which gives the staged roadmap. This
one answers the practical questions: *what has to be trained, what does that
cost, what do I do with the big GPU, and what is realistic before the event.*

## The short version

| | training needed? | effort |
|---|---|---|
| **RGB person detection** | **No.** COCO-pretrained models already detect `person` | hours — export + integrate |
| **Thermal person detection** | **Yes**, but bootstrap from public thermal data | days–weeks, gated on *our* footage |
| **The big GPU** | barely relevant | fine-tuning a nano model is <1 hr |

**The bottleneck is not compute. It is footage from our own cameras.** An A100
or H100 fine-tunes a YOLO-nano on a few thousand images in well under an hour —
you could do it on a decent laptop GPU overnight. What no amount of compute
substitutes for is that nobody, anywhere, has recorded 120×120 ultra-wide LWIR
from a mutant vehicle on a playa at 2am. That footage can only be captured while
the rig is bolted to the car. GPU time can be rented any evening.

So: **the schedule-critical task is recording, not training.** That's why
`--record` exists (see below) and why it was built before any model work.

## Why train at all — the honest reason

Today's `MotionDetector` is background subtraction, which **assumes a stationary
camera**. Parked or crawling, it works. Driving, the entire frame is "motion"
and the detector degrades badly — dust, shadows, and the moving background all
read as contacts.

That is the real argument for a model. Not accuracy polish: a learned detector
doesn't care that the camera is moving, because it recognises *people* rather
than *change*. Everything else — bearings, collision logic, the wire format,
the HUD — is unaffected. Per DETECTOR.md, **the detector is the only piece that
changes.**

---

## Stage A — RGB, no training required

COCO includes `person` and `bicycle`, so a pretrained YOLOv8/v11-nano already
does what we need in daylight, with zero labelling.

```bash
# on any machine
pip install ultralytics
yolo export model=yolov8n.pt format=onnx opset=12 imgsz=640
```

Then **build the TensorRT engine on the Jetson itself**:

```bash
# on the Orin — NOT on the training server
/usr/src/tensorrt/bin/trtexec --onnx=yolov8n.onnx --saveEngine=yolov8n.engine --fp16
```

> **Gotcha worth internalising: TensorRT engines are not portable.** They're
> built against a specific GPU architecture and TensorRT version. An engine
> built on an A100 will not load on an Orin. Export **ONNX** on the server,
> convert to `.engine` on the target. This trips up nearly everyone once.

This is the highest value-per-hour work in the whole plan and needs no dataset.

## Stage B — Thermal, where training is genuinely required

A COCO model transfers poorly to LWIR: no colour, no texture, inverted
contrast, and a person is a warm blob rather than a shape with clothing detail.
The domain gap is real and it will not be closed by prompt-fiddling.

### B1. Bootstrap from public thermal data

| dataset | what it is | licence |
|---|---|---|
| **Teledyne FLIR ADAS** (v1/v2) | ~10k+ annotated thermal frames, `person`/`bike`/`car` | free, registration |
| **KAIST Multispectral** | aligned thermal+RGB pedestrian pairs, day and night | academic |

These get a working night detector quickly. **But mind the domain gap** — FLIR
ADAS is 640×512 Boson, forward-facing, mounted on a car at road height, with a
normal lens. Ours is 120×120, ultra-wide **fisheye**, mounted on an art car,
looking at people standing *around* the vehicle rather than ahead of it.
Different resolution, different distortion, different viewpoint, different
range. Public data gets you to "detects warm humans"; our own data gets you to
"works on our rig".

### B2. Collect our own — this is the part with a deadline

Already built and shipping:

```bash
python3 -m zvision --record /data/drive-01 --record-hz 1 \
  --camera thermal:/dev/video0:az=0:fov=160:lens=fisheye:name=thermal \
  --camera rgb:/dev/video2:az=105:fov=87:lens=pinhole:name=stbd
```

Writes `<dir>/<camera>/<seconds>.png|jpg` plus an `index.jsonl` line per frame
carrying the **pixel boxes** the motion detector found:

```json
{"camera":"thermal","t":132.5,"file":"thermal/000132.500.png","boxes":[[44,60,18,39]]}
```

Those boxes are **weak labels** — motion blobs, not truth. Their value is that
correcting an existing box takes an annotator ~2–5 seconds versus ~10–20 to
draw one from scratch, and a night of driving arrives pre-sorted into "something
moved here" rather than as thousands of unsorted stills.

Defaults are deliberate: **1 Hz** (consecutive 10 Hz frames are near-duplicates
that waste disk and annotator attention), thermal as **PNG** (lossless — JPEG
artefacts on a low-contrast 120×120 frame destroy real signal), RGB as JPEG, and
a **20 GB cap** so a long night can't fill the boot disk. Recording failures are
swallowed and reported once; they never interrupt detection.

**What to record:** night is the priority — that's where thermal earns its
place and where no public data resembles our sensor. Get variety: people
walking past, walking toward, standing still, in groups, on bikes, at 2 m and
at 30 m, with and without the art car's own lighting on them.

### B3. Label

500–2,000 corrected frames is a realistic target for fine-tuning a pretrained
model into a narrow domain. This is *not* a tens-of-thousands-of-images job —
we're adapting a model, not training from scratch.

Tools: **CVAT** (self-hosted, free), **Label Studio**, or **Roboflow** (free
tier, easiest). All import YOLO-format boxes, so the weak labels can be loaded
as a starting layer.

Taxonomy per DETECTOR.md: `person`, `person_on_bike`, and a count bucket
(`1` vs `2+`) so the HUD can distinguish a lone walker from a group.

### B4. Fine-tune — the part the server is for

```bash
# on the A100/H100
yolo train model=yolov8n.pt data=zodiac-thermal.yaml \
    imgsz=160 epochs=100 batch=64 patience=20
yolo export model=runs/detect/train/weights/best.pt format=onnx opset=12
```

Note `imgsz=160` — matching the sensor rather than upscaling to 640 keeps
inference cheap on the Orin and stops the model learning interpolation
artefacts. Expect this to take **well under an hour**. If you find yourself
waiting on the GPU, something is misconfigured.

Then ONNX → `.engine` **on the Orin**, per the Stage A gotcha.

### B5. Validate by range, not by mAP

A single mAP number hides the thing that matters. Measure **precision/recall in
range buckets** (0–5 m, 5–15 m, 15–30 m, 30 m+). A useful warning at vehicle
speed means reliable detection at ≥30 m; a model that's brilliant at 3 m and
blind at 25 m scores well and is useless.

---

## What I'd actually do, given the calendar

The event is weeks away and the cameras aren't mounted yet. Being straight
about it:

1. **Don't gate the event on a trained thermal model.** Training on footage that
   doesn't exist yet, alongside the mechanical build, is not a three-week task.
2. **Ship what works:** motion detection (fine while parked and crawling, which
   is most of an art car's life) plus **Stage A RGB**, which is integration work
   with no dataset dependency and covers daylight properly.
3. **Record everything, from the first test drive onward.** Every hour of
   footage is an asset that cannot be back-filled after the event.
4. **Train after**, with real data, for next year — or during the event if
   there's an idle afternoon and the server is reachable over Starlink.

The mistake to avoid is spending the remaining weeks on model work and arriving
with an untested rig. The mistake that costs the *most* is arriving, having a
great week, and coming home with no footage.

## Cost of being wrong

Both detectors are wrong sometimes, in opposite directions, and it's worth
deciding which you prefer. A **false positive** puts a phantom contact on the
HUD and swings the tracker light at nobody — annoying, erodes trust in the
display. A **false negative** means a person near a moving vehicle isn't shown.
Tune toward false positives; that's what `--collision-min-size` and
`--min-area` are for (see DEPLOY.md §8).
