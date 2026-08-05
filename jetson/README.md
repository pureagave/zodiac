# zvision — Zodiac edge-box (Jetson) software

Runs on the roof-mounted **Jetson Orin Nano Super**. Turns thermal / RGB camera
frames into vehicle-relative **threat contacts** and broadcasts them on the
fleet bus, where every tablet's DRIVER night-HUD is already listening. A second,
optional output drives a **DMX moving-head "tracker" light** that points at those
same contacts and — when idle — pulses to the beacon's `$ZAUD` audio (see the
[DMX section in DEPLOY.md](DEPLOY.md#7-bring-up-the-dmx-tracker-light-optional-independent-of-the-camera)).

```
 camera ring ──▶ detector ──▶ rig merge ──▶ ThreatBroadcaster ──▶ fleet bus
  UW thermal      per camera   full-circle    ZTHREAT frames       239.7.7.20:10120
  + RGB x N       (rel_az,     bearings +     (UDP, TTL 1)         + subnet broadcast
                   size, id)   overlap dedup                            │
                                            tablets: NetworkThreatSource ▶ DRIVER HUD
```

The wire format (`zvision/threat_protocol.py`) is a **byte-exact mirror** of the
tablet's Kotlin `ThreatProtocol` — the two sides share no code, only the frozen
protocol. The round-trip tests here also guard that contract.

## Quick start (no hardware needed)

```bash
cd jetson
python3 -m zvision --source fake -v        # emit synthetic contacts, print each frame
python3 -m unittest discover -s tests -t . # 152 tests, standard library only
```

`--source fake` needs nothing installed — it's how you prove the bus and light
up the HUD before any camera or model exists. Point it at a tablet on the same
WiFi and the DRIVER concept comes alive.

## With a camera (bring-up)

```bash
python3 -m zvision --source thermal --device /dev/video0 --hz 10 -v
```

`--source thermal|rgb` runs `MotionDetector`: background-subtraction blobs →
contacts. **No trained model required** — plug in a UVC camera (Lepton via
PureThermal, or a USB webcam) and moving bodies show up on the HUD immediately.
The trained thermal/RGB model drops in behind the same `detect()` signature
later.

`--hfov` defaults to **160°** for the Lepton Ultra Wide, and `--lens` selects
the projection (`equidistant` for a fisheye, `rectilinear` for an ordinary
lens). Getting these right matters: bearings drive both the HUD *and* where a
real spotlight points.

## The surround rig (360°)

The vehicle carries one ultra-wide thermal forward plus several RGB cameras
around the body. Repeat `--camera` once per camera, telling each where it looks
and what it looks through; `rig.py` fuses them into a single full-circle contact
list — bearings rotated into vehicle terms, track ids namespaced per camera, and
the same person seen by two overlapping cameras collapsed to one contact.

```bash
python3 -m zvision -v \
  --camera thermal:/dev/video0:az=0:fov=160:lens=fisheye:name=thermal \
  --camera rgb:/dev/video2:az=120:fov=90:lens=pinhole:name=stbd-aft \
  --camera rgb:/dev/video4:az=-120:fov=90:lens=pinhole:name=port-aft
```

`az` is the camera's bearing off the nose (+right), so `az=180` faces astern.
Verbose start-up prints each camera's covered arc **and the blind sectors**, so
you find out the ring doesn't close now rather than by wondering why someone
standing behind the car never appeared:

```
     thermal: fake /dev/video0 az=+0° fov=160°h equidistant -> covers -80°..+80°
    stbd-aft: fake /dev/video0 az=+120° fov=90°h equidistant -> covers +75°..+165°
    port-aft: fake /dev/video0 az=-120° fov=90°h equidistant -> covers -165°..-75°
  blind: +166°..+195°
```

A camera that won't open, or starts throwing mid-run, costs you its arc — not
the run. If *nothing* opens, the runner exits rather than broadcasting a
confident "all clear" while blind.

## Field tuning

The detection numbers can't be got right on a bench — they depend on how high
the camera ends up mounted, how far away people actually walk, and how much
contrast a body has against hot ground at 2am. So they're all reachable from
the command line: `--min-area` `--match-dist` `--far-h` `--near-h`
`--collision-az-rate` `--collision-min-size` set rig-wide defaults, and the
matching `--camera` keys (`minarea` `match` `farh` `nearh` `azrate` `minsize`)
override one camera. Tuning on-site is a config line and a restart, never a
code edit.

`--check` validates the whole config, prints the resolved rig and its blind
arcs, and exits without opening a camera or touching the network:

```bash
python3 -m zvision --check --min-area 0.008 --camera thermal:/dev/video0:az=0:fov=160
```

Run it before writing anything into `/etc/default/zvision` — the service is
`Restart=always`, so an unvalidated typo is a crash loop. Full playa procedure
in [DEPLOY.md §8](DEPLOY.md#8-tuning-on-playa-laptop-only--no-keyboard-mouse-or-monitor).

## Layout

| file | role |
|---|---|
| `zvision/threat_protocol.py` | ZTHREAT wire format (mirrors Kotlin) |
| `zvision/fleet_bus.py` | multicast group/port constants (mirrors Kotlin) |
| `zvision/broadcaster.py` | UDP sender — multicast **and** subnet broadcast |
| `zvision/detector.py` | `FakeDetector` (stdlib) + `MotionDetector` (cv2) |
| `zvision/geometry.py` | pixel → (az, el) through a lens model, bbox → size, constant-bearing collision rule |
| `zvision/rig.py` | camera mounts, `--camera` spec parsing, full-circle merge + overlap dedup |
| `zvision/capture.py` | UVC camera wrapper (cv2) |
| `zvision/tracker.py` | DMX tracker light — target-select + pan/tilt map + slew + idle sound show |
| `zvision/dmx.py` | DMX transport — `FakeDmxSink` (stdlib) + `OlaDmxSink` (posts to `olad`) |
| `zvision/recorder.py` | frame + weak-label dump for model training (`--record`) |
| `zvision/audio_bus.py` | `$ZAUD` listener — beacon mic levels for the idle sound-reactive show |
| `zvision/app.py` | CLI runner / broadcast + tracker loop |
| `systemd/zvision.service` | auto-start unit |
| `scripts/install.sh` | provision to `/opt/zodiac/jetson` + enable service |
| `scripts/install-ola.sh` | OLA (`olad`) + `ftdidmx` plugin, CPU-pinned, on-boot |

## Docs

- **[DEPLOY.md](DEPLOY.md)** — full hardware bring-up (flash → network → prove-with-fake → camera → permanent → DMX tracker light)
- **[HARDWARE.md](HARDWARE.md)** — edge-box bill of materials, wiring, power & thermal budget
- **[DETECTOR.md](DETECTOR.md)** — roadmap from today's motion blobs to the trained thermal model
- **[TRAINING.md](TRAINING.md)** — what actually needs training (RGB: nothing), what the big GPU is and isn't for, and the recording workflow that feeds it

## Recording training data

```bash
python3 -m zvision --record /data/drive-01 --record-hz 1 --camera thermal:/dev/video0:az=0:fov=160
```

Writes frames per camera plus an `index.jsonl` carrying the **pixel boxes** the
motion detector found — weak labels, so an annotator corrects rather than draws.
Thermal goes to lossless PNG, RGB to JPEG, capped at 20 GB by default so a long
night can't fill the boot disk. Failures are reported once and never interrupt
detection. Footage can only be captured while the rig is on the vehicle; GPU
time can be rented any evening — which is why this exists before any model work.
