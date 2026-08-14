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

The wire format (`zvision/threat_protocol.py`) and the tablet's Kotlin
`ThreatProtocol` are **two hand-written implementations of the same grammar, and
neither is authoritative.** The shared truth is the measured golden corpus at
`protocol/threat-protocol-golden.json`, which both test suites read and both fail
loudly on rather than skipping if it is missing. Before it existed the two sides
had silently drifted in ten measured ways. See `protocol/README.md` and
`docs/PROTOCOLS.md` §3.

## Quick start (no hardware needed)

```bash
cd jetson
python3 -m zvision --source fake -v        # emit synthetic contacts, print each frame
python3 -m unittest discover -s tests -t . # the whole suite — no third-party packages needed
```

The suite needs no `numpy`, `opencv`, `StreamDeck` or `PIL`. It does need the
**repository root**: `tests/test_threat_protocol_golden.py` reads
`protocol/threat-protocol-golden.json` two directories up and fails if it is
missing, so run it from a full checkout rather than a copy of `jetson/` alone.

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
     thermal: thermal /dev/video0 az=+0° fov=160°h equidistant -> covers -80°..+80°
    stbd-aft: rgb /dev/video2 az=+120° fov=90°h rectilinear -> covers +75°..+165°
    port-aft: rgb /dev/video4 az=-120° fov=90°h rectilinear -> covers -165°..-75°
  blind: +166°..+195°
```

> **That transcript predates 2026-08-10 and shows `fov=160°h -> ±80°`.**
> `--fov-ref` now defaults to **`d`**, so the same command prints `fov=160°d ...
> covers -64°..+64°`. The Lepton Ultra Wide's quoted 160° is the **diagonal** —
> argued from physics in `HARDWARE.md` §1 and since confirmed by measurement (a
> true 40.4° target reported at 42.5°; the horizontal reading predicts 32.3°).
> The tablet's `SurroundRing.COVERED_ARCS` carries the matching ±64°.

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
| `zvision/capture.py` | `UvcCamera` + `ThermalCamera` (cv2); reopens a camera that wedges in `select()` at cold boot |
| `zvision/tracker.py` | DMX tracker light — target-select + pan/tilt map + slew + idle sound show |
| `zvision/dmx.py` | DMX transport — `FakeDmxSink` (stdlib) + `OlaDmxSink` (posts to `olad`) |
| `zvision/recorder.py` | frame + weak-label dump for model training (`--record`) |
| `zvision/audio_bus.py` | `$ZAUD` listener — beacon mic levels for the idle sound-reactive show |
| `zvision/normalize.py` | the array-free arithmetic — contrast stretch window, track-id assignment, re-baseline guard, camera-stall guard (exists so the suite runs without numpy) |
| `zvision/dmxpark.py` | fail-safe: zeroes all 512 slots, with retries. Also the operator kill: `python3 -m zvision.dmxpark` |
| `zvision/tracklog.py` | breadcrumb recorder — joins the telemetry group, writes daily NMEA-derived CSV |
| `zvision/trackserve.py` | read-only HTTP server for those CSVs, port 8087, `/index.json` listing |
| `zvision/threat.py` | the `DriverThreat` record |
| `zvision/app.py` | CLI runner / broadcast + tracker loop |
| **`zdeck/`** | the Stream Deck control surface — `model.py` (pure key/DMX state), `surface.py` (protocol + fake), `hardware.py` (vendor lib + PIL, isolated), `app.py` (runner). See [DECK.md](DECK.md) |
| `systemd/zvision.service` | auto-start unit for the vision runner |
| `systemd/zodiac-deck.service` | auto-start unit for the Stream Deck runner |
| `systemd/zodiac-track.service` | auto-start unit for the breadcrumb recorder |
| `systemd/zodiac-track-serve.service` | auto-start unit for the CSV HTTP server |
| `systemd/70-zodiac-streamdeck.rules` | udev rule giving `plugdev` access to the deck |
| `scripts/install.sh` | copy **`zvision` only** to `/opt/zodiac/jetson`, write `/etc/default/zvision`, enable + start `zvision.service` |
| `scripts/install-ola.sh` | OLA (`olad`) + `ftdidmx` plugin, CPU-pinned, on-boot |
| `scripts/preflight-flash.sh` | read-only pre-flash checks on the Linux flash host; `--command` prints the flash command |

> `install.sh` installs **only** `zvision` and **only** `zvision.service`. The
> deck, track and track-serve units, the udev rule, and the `zdeck` package itself
> are installed by hand. `zdeck` must end up under `/opt/zodiac/jetson`, because
> that is the `WorkingDirectory` its unit runs `-m zdeck` from.

## Docs

- **[DEPLOY.md](DEPLOY.md)** — full hardware bring-up (flash → network → prove-with-fake → camera → permanent → DMX tracker light)
- **[HARDWARE.md](HARDWARE.md)** — edge-box bill of materials, wiring, power & thermal budget
- **[MOVING-HEAD.md](MOVING-HEAD.md)** — the DMX fixture: channel map, the measured pan/tilt ranges (the manual is wrong about tilt), and the bench log
- **[DECK.md](DECK.md)** — the Stream Deck control surface, its deployment, and the unresolved DMX arbitration
- **[DETECTOR.md](DETECTOR.md)** — roadmap from today's motion blobs to the trained thermal model
- **[TRAINING.md](TRAINING.md)** — what actually needs training (RGB: nothing), what the big GPU is and isn't for, and the recording workflow that feeds it
- **[../docs/PROTOCOLS.md](../docs/PROTOCOLS.md)** — the `ZTHREAT` wire format in full, and the NMEA channels this box listens to

## Recording training data

```bash
python3 -m zvision --record /data/drive-01 --record-hz 1 --camera thermal:/dev/video0:az=0:fov=160
```

Writes frames per camera plus an `index.jsonl` carrying the **pixel boxes** the
motion detector found — weak labels, so an annotator corrects rather than draws.
Small frames go to lossless PNG and large ones to JPEG — the split is by **pixel
count**, not by camera kind, so the 160×120 thermal lands on PNG and a full-size
RGB frame lands on JPEG. (The threshold was originally 128×128, chosen for a
sensor believed to be 120×120, which silently JPEG'd the thermal.) Capped at
20 GB by default, shared across all cameras, so a long night can't fill the boot
disk. Failures are reported once and never interrupt
detection. Footage can only be captured while the rig is on the vehicle; GPU
time can be rented any evening — which is why this exists before any model work.
