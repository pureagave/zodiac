# Zodiac

Software for **Zodiac**, a Judge-Dredd-themed mutant vehicle (art car) run by the
Galactic Relay camp at Burning Man. It is one system in three parts: a fleet of
Android tablets showing an 80s green-phosphor cockpit over a live Black Rock City
map, a phone that acts as the vehicle's shared sensor hub, and a roof-mounted
vision box that watches for people in the dark and tells every screen — and a
spotlight — where they are.

Everything realtime runs on the vehicle's own WiFi. Starlink is aboard but is
treated as unreliable; nothing safety-relevant depends on it.

> **Docs map** — this file is the public snapshot: what exists, what runs where,
> what is honestly not built. For the engineering map of all three codebases see
> [`ARCHITECTURE.md`](ARCHITECTURE.md). For build/test/deploy see
> [`docs/BUILD.md`](docs/BUILD.md). For the wire formats between the parts see
> [`docs/PROTOCOLS.md`](docs/PROTOCOLS.md). For the dated decision log see
> [`SYNC.md`](SYNC.md) — that is where progress is recorded, not here.

---

## The three parts

| Part | Language | Runs on | What it does |
|---|---|---|---|
| **`:app`** — Zodiac Control | Kotlin / Jetpack Compose | Android tablets & phones | The cockpit. Three runtime-switchable "concepts" (RADAR / MAP / DRIVER), a live BRC playa map, street-following navigation, threat HUD, passenger display, OLED burn-in mitigation. |
| **`:beacon`** — Zodiac Beacon | Kotlin, headless | one Android phone | The vehicle's sensor hub. Reads GNSS, compass, IMU, mic level, ambient light, shock and battery, and broadcasts them as NMEA over UDP multicast to the whole fleet. |
| **`jetson/`** — `zvision` + `zdeck` | Python 3 | Jetson Orin Nano Super | The edge box. A ring of cameras → one full-circle threat list on the fleet bus, plus a DMX moving-head tracker light and a six-key Stream Deck control surface. |

They share no code. They agree through three documented wire formats — NMEA
sentences (beacon → fleet), `ZTHREAT` frames (Jetson → fleet), and `$ZNAV`
nav-share (tablet ↔ tablet) — all pinned by tests, see
[`docs/PROTOCOLS.md`](docs/PROTOCOLS.md).

```
                     vehicle WiFi (travel router, single /24, DHCP)
  ┌──────────────┐                                          ┌──────────────────┐
  │  :beacon     │ ── NMEA ──▶ 239.7.7.10:10110 ──┐         │  jetson zvision  │
  │  phone       │    + subnet broadcast          │         │  Orin Nano Super │
  │  sensor hub  │                                │         └────────┬─────────┘
  └──────────────┘                                │                  │
                                                  ▼                  │ ZTHREAT
                                        ┌─────────────────┐          ▼
                                        │  :app tablets   │◀── 239.7.7.20:10120
                                        │  (8–10 of them) │    + subnet broadcast
                                        └───────┬─────────┘
                                                │ $ZNAV 239.7.7.30:10130       │ DMX (via olad)
                                                └─▶ shared drive-to target     ▼
                                                    (tablet ↔ tablet)  moving-head tracker light
```

The tablets also share one piece of state among *themselves* — the drive-to
destination — over a third bus, `$ZNAV` (239.7.7.30:10130). A fourth,
`$ZVER` (239.7.7.40:10140), is being added for fleet build-version reporting.

---

## Repository layout

```
app/            :app      — the Android cockpit (org.pureagave.zodiac.control)
beacon/         :beacon   — the headless sensor hub (org.pureagave.zodiac.beacon)
jetson/         zvision + zdeck — the Python edge box, and its own docs
protocol/       cross-language wire contracts (the ZTHREAT golden corpus)
docs/           build, protocols, devices, kiosk provisioning, audits
design/         UI concept docs and design history
tasks/          open.md (active work) / done.md
tools/          offline asset pipelines (art pre-render)
SYNC.md         append-only decision log — the project's working memory
```

A handful of root-level files (`AGENTS.md`, `SOUL.md`, `IDENTITY.md`,
`BOOTSTRAP.md`, `HEARTBEAT.md`, `TOOLS.md`, `USER.md`, `NEXT.md`) are
**AI-assistant workspace/persona files, not product documentation** — safe to
ignore when reading the codebase.

---

## What runs where

Full detail, including why each role is on the hardware it is on, in
[`docs/DEVICES.md`](docs/DEVICES.md).

| Role | Device | Runs | Status |
|---|---|---|---|
| Hero dashboard | Galaxy Tab S9+ (`SM-X810`), 12.4" OLED | `:app`, RADAR or MAP | In use |
| Driver night display | Galaxy A54 (`SM-A546V`), OLED | `:app`, DRIVER concept | HUD built; on-vehicle night legibility check outstanding |
| Passenger / crew displays | Fire HD 10 (`KFTUWI`) and other tablets, LCD | `:app`, passenger carousel or RADAR/MAP | In use; the Fire is the performance floor |
| Sensor hub | XCover Pro (`SM-G715U`), screen off | `:beacon` | Built, GPS + compass verified end-to-end |
| Vision edge box | Jetson Orin Nano Super | `zvision` | Software built and tested; thermal + RGB cameras now connected and streaming on the box; permanent ring, on-vehicle mount/aim and a trained detector still to come |
| Light control surface | Elgato Stream Deck Mini (`0fd9:0063`) | `zdeck` | Built and driven on the real deck |
| Tracker light | DMX moving head + FTDI USB-DMX | driven by `zvision` / `zdeck` via `olad` | Fixture characterised on the bench; on-vehicle aim calibration outstanding |
| Network | travel router (AP + DHCP) | — | In use |

---

## `:app` — the cockpit

Package `org.pureagave.zodiac.control`. Kotlin + Compose, minSdk 28,
targetSdk/compileSdk 35, `screenOrientation="fullUser"` (each concept reflows for
landscape or portrait).

### Three concepts

Picked with the top-right pill, persisted across launches. Switching is purely
presentational — all three read the same state.

- **RADAR** (`MotionTrackerScreen`) — the *Aliens* M41A motion tracker. A circular
  scope whose sweep arm (one revolution every 4 s) lights up the real BRC map as
  it passes, with art and camp contacts pulsing behind it.
- **MAP** (`InstrumentBayScreen`) — the *Alien* Nostromo instrument bay. Bordered
  tiles: heading dial, speed gauge, ground-track map, cell and throttle gauges.
- **DRIVER** (`DriverNightScreen`) — a mostly-black OLED night HUD for the driver's
  phone. Thermal contacts as hollow wireframe figures in a forward perspective
  view, plus a nose-up surround ring carrying every contact all the way around
  the vehicle, plus minimal nav.

### What is built

**Map and navigation**
- Black Rock City rendered from the bundled 2026 Innovate GIS — trash fence,
  streets, plazas, city blocks, toilets, CPNs. Active year is one source of truth
  (`GoldenSpike.ACTIVE = Y2026`). The 2026 city moved ~583 m SW from 2025 but did
  not rotate, so the 12:00 axis is still 45° true.
- Two map modes: `TOP` (orthographic, ego centred) and `TILT` (pitched 3D over a
  retro perspective grid). Drag to pan, pinch to zoom, two-finger twist to rotate.
  Heading and speed come from the GPS fix, never from tapping the map.
- Zoom-gated labels: plazas at 0.20 px/m, major art 0.30, streets 0.45, CPNs 0.65,
  minor art 1.10. Street labels are de-duplicated across the GIS's per-segment
  features so each logical street draws once.
- **Drive-to**: HOME (camp at Heiau & 2:15), MAN, TEMPLE, BATH (nearest toilet,
  re-resolved live), or ADDR — type any city address on a full-screen keypad
  (clock time → ring letter → a 10 s heading flash → live nav).
- **Street-following routing**: out to the nearest entrance radial, in to
  Esplanade, round the ring, to the address — with every corner snapped to a real
  vertex of the GIS street polylines, so the drawn route lies on the drawn
  streets instead of cutting through camps.
- Street-crossing popups ("ENTERING ATWOOD") and "passing" callouts for notable
  art within 120 m.
- A heading-guidance chevron that slides along a track to show how far off the
  target is and which way to turn.
- **Shared destination across the fleet.** When one tablet sets a drive-to
  target, every tablet adopts it over `$ZNAV` (239.7.7.30:10130), so the whole
  fleet's DRIVER HUD points the same way. The OLED tablets (S9+, A54) are
  automatic broadcast authorities and the two Fires follow — authority is *not* a
  toggle, it is the same OLED-device check burn-in uses. Lamport `(seq, src)`
  ordering, single-owner, no-echo (`NavShareArbiter`); adoption reuses the same
  local drive-to path, so no new HUD was needed.

**Sensing**
- Five pluggable GPS sources behind one interface — `FAKE`, `SYSTEM`, `BLE`
  (Bluetooth Classic SPP receivers), `USB` (serial NMEA dongles), `NET`. `NET` is
  the shipped fleet path and is verified end-to-end against the real beacon.
- Automatic `NET → SYSTEM` failover on a device that has its own GNSS: drop after
  3 s of an unhealthy primary (on top of a 5 s staleness window), recover only
  after 10 s of health, so a half-alive beacon cannot flap the source.
- `NET` also parses the beacon's five proprietary sensor channels and surfaces
  them in the UI; `$ZENV` ambient lux drives automatic screen dimming.
- Threat contacts from the Jetson over `ZTHREAT`, with an explicit feed state —
  `LIVE` / `DEMO` / `ABSENT`. On a deployed vehicle the demo source is **off**, so
  a dead feed reads "NO VISION" rather than inventing pedestrians.

**Displays and operations**
- **Passenger display**: a self-running card carousel for riders — playa address,
  a mic-driven oscilloscope, thermal contacts around the vehicle, an impact
  "bump" gauge, trip odometer, sunrise/sunset countdown, nearest art. A card with
  no data is dropped from the rotation rather than shown empty, and the "souls
  detected" card only appears on a genuinely live vision feed. Enabled per device
  by a hidden top-right long-press, which also hides the concept switcher.
- **OLED burn-in mitigation**: whole-UI pixel shift (universal), a brightness
  breathe and idle dim (OLED only — gated off on Amazon devices), and an idle
  escalation ACTIVE → DIM (5 min) → DEEP_IDLE "STANDBY" screen (30 min) → app-drawn
  black sleep (60 min), waking instantly on touch, real GPS movement or a link
  change. Every parameter is adjustable on the playa from a hidden tuning panel
  and persists.
- **Kiosk mode**: device-owner lock task, lockscreen and automatic OS updates
  disabled, and — because Android blocks a boot receiver from launching an
  activity — the cockpit is set as the device's Home app so a kiosked tablet
  **relaunches itself after a reboot or power loss** instead of sitting on the
  stock launcher. See [`docs/KIOSK.md`](docs/KIOSK.md).
- **On-device postmortem logging**: every build writes a size-capped rotating log
  (512 KiB × 5 segments ≈ 2.5 MB). It counts everything it discards — write
  failures, rotation failures, and lines aged out of the ring — so it can never
  lie about its own completeness. A hidden bottom-right long-press opens a log
  viewer on the device itself, which on the Fire is the only way in, because
  Fire OS denies `adb pull` access to `/sdcard/Android/data`.
- **Playa discovery**: offline-first cache of the Burning Man API's art and camp
  records, stored in `filesDir` (not `cacheDir` — Android may purge that, and this
  is the only copy for up to 14 unattended days). Refreshes daily when there is
  internet; a failed or partial fetch never clobbers a good cache. Art
  placements are embargoed by BM until roughly three weeks pre-event, so no art
  markers show until they release the 2026 data.
- **Pre-rendered art imagery**: `tools/prerender_art.py` bakes BM art thumbnails
  into the APK in the cockpit's phosphor treatment. Baked rather than styled at
  runtime because `RuntimeShader` is API 33+ while the passenger Fires are API 28,
  and the playa has no reliable internet.

### What is *not* built

- **Every vehicle transport is fake.** `FakeTransportAdapter` is the only
  implementation of `TransportAdapter` in the repository, and
  `FakeTelemetryRepository` the only production `TelemetryRepository`. The
  transport chips, CONNECT/DISCONNECT buttons, `SetHeading`/`SetSpeed` commands and
  the thermal/mode/link readouts they feed are scaffolding for a vehicle bus that
  does not exist yet. Real vehicle data reaches the app **only** through
  `NetworkLocationSource` (NMEA) and `NetworkThreatSource` (ZTHREAT).
- Two MAP tiles render hard-coded literals: the cell gauges (`CELL A 70%`,
  `CELL B 45%`) and the throttle trace. They are not wired to anything.
- `CockpitUiState.tiltDeg` is persisted and clamped but the renderer applies a
  compile-time 45° instead — a runtime `rotationX` would not render in that
  `graphicsLayer` on the S9+.
- No automated coverage of the map touch interaction (drag / pinch / twist).

---

## `:beacon` — the sensor hub

Package `org.pureagave.zodiac.beacon`. A headless foreground-service app that
turns a spare Android phone into the vehicle's shared sensor. minSdk 29 (the
XCover Pro is Android 10).

It forwards the phone's raw GNSS NMEA **verbatim** and adds:

| Sentence | Carries | Rate |
|---|---|---|
| `$GPHDT` | true compass heading (magnetometer, declination-corrected once a fix exists) | ~4 Hz |
| `$ZTLM` | IMU pitch/roll + ground speed | ~4 Hz |
| `$ZAUD` | mic level — rms, peak, beat flag | ~15 Hz |
| `$ZENV` | ambient lux | ~0.5 Hz |
| `$ZSHK` | shock/impact peak g | on impact |
| `$ZBCN` | health — battery %, fix quality, satellites, uptime | ~0.2 Hz |
| `$ZODO` | trip + lifetime odometer (persisted) | ~0.5 Hz |

Field-by-field definitions in [`docs/PROTOCOLS.md`](docs/PROTOCOLS.md).

Design notes that matter operationally:

- **Only a level number ever leaves the phone.** No audio is recorded,
  buffered to disk, or transmitted — the PCM buffer is reduced to three scalars
  in place and discarded. The microphone is optional at every layer; a
  boot-started service never requests it at all.
- Every sentence goes to both the multicast group *and* a subnet-directed
  broadcast, because some access points drop multicast. That is why the tablet
  de-duplicates byte-identical `$ZSHK` lines inside a 200 ms window.
- GPS failure is never fatal — if location permission is missing or wiring
  throws, every other channel keeps broadcasting and the notification says so.
- A sensor the phone does not have emits **nothing** rather than a fabricated
  zero, for heading and lux.
- The odometer refuses steps that are inaccurate (> 20 m), below a 5 m jitter
  floor, or imply more than 160 kph — so GPS multipath cannot inflate the trip.

---

## `jetson/` — the edge box

A roof-mounted **Jetson Orin Nano Super**. Two Python packages:

### `zvision` — cameras → threat contacts → the fleet bus

```
camera ring ──▶ detector ──▶ rig merge ──▶ ThreatBroadcaster ──▶ 239.7.7.20:10120
 per camera     per camera   full-circle    ZTHREAT frames        + subnet broadcast
 (az, lens,     (rel_az,     bearings +     10 Hz, TTL 1                │
  fov)           size, id)   overlap dedup                              ▼
                                                       tablets ▶ DRIVER HUD
                                    │
                                    └──▶ Tracker ──▶ olad ──▶ DMX moving head
```

Each camera is declared on the command line with its mounting bearing, field of
view and lens model; `rig.py` unprojects pixels through a real lens model,
rotates bearings into vehicle terms, namespaces track ids per camera, and
collapses the same person seen by two overlapping cameras into one contact. On
start-up it prints each camera's covered arc **and the blind sectors**, so a ring
that does not close is found immediately rather than by wondering why nobody
astern ever appears. A camera that fails costs its arc, not the run; if nothing
opens at all the runner exits rather than broadcasting a confident "all clear"
while blind.

**It also self-heals a wedged camera.** A V4L2 handle opened before its USB
device is streaming-ready can hang forever in `select()` and never recover — on a
cold boot that once left the box "running but blind" for hours, emitting false
all-clear. A `CameraStallGuard` now reopens a camera that has stopped delivering
frames (~20 s, measured on hardware), so a power-cycle no longer needs a human.
The broadcaster likewise re-derives its subnet-broadcast target if it started
before the DHCP lease, and `olad` is set to restart on crash — the fleet's
edge is built to come back by itself after a power event.

**The detector today is background subtraction, not a model.** There is no
TensorRT, ONNX, YOLO or PyTorch anywhere in the tree and no weights file. The
trained thermal model is a roadmap ([`jetson/DETECTOR.md`](jetson/DETECTOR.md),
[`jetson/TRAINING.md`](jetson/TRAINING.md)), and `zvision` ships a `--record` mode
that dumps frames plus the motion detector's boxes as weak labels to feed it.

The **DMX tracker light** aims the moving head at the selected contact —
collision contacts win, otherwise the largest, with hysteresis so the beam does
not twitch between two similar contacts. When there is nothing to track it
pulses to the beacon's `$ZAUD` level. Transport is HTTP to a local `olad`, which
drives an FTDI USB-DMX dongle. A separate `dmxpark` module zeroes the whole
universe as a fail-safe and is wired as `ExecStopPost` on both services.

### `zdeck` — the physical control surface

Six keys on an Elgato Stream Deck Mini in the cab: BLACKOUT, LAMP, HOME, DIM−,
DIM+, COLOUR. It borrows its channel numbers from `zvision`'s `TrackerConfig`
rather than restating them, so the deck and the tracker cannot disagree about
which channel is the dimmer. BLACKOUT deliberately bypasses the normal DMX send
path and goes through `dmxpark.park()` with retries, and renders **DMX FAIL** in
red if the kill did not land — a calm panel over a live beam is the one lie that
matters here.

**Known unresolved:** `zvision --dmx ola` and `zdeck` must not run at the same
time. At the tracker's frame rate a BLACKOUT would be overwritten within ~125 ms
and would flicker rather than kill. This works today only because `zvision`'s DMX
output defaults to `none`. See [`jetson/DECK.md`](jetson/DECK.md).

---

## Status summary

| | Built & verified | Built, not yet proven on the vehicle | Not built |
|---|---|---|---|
| **`:app`** | all three concepts, playa map, routing, shared `$ZNAV` destination (verified device-to-device), GPS sourcing incl. NET, burn-in, kiosk (incl. auto-relaunch after reboot), logging, passenger display | 2026 base-map address check on-device | real vehicle transports |
| **`:beacon`** | GPS + compass end-to-end; all seven sentence types under test | the five sensor channels in a moving vehicle | production Pi + u-blox hub |
| **`jetson/`** | ZTHREAT bus with `--source fake`; thermal + RGB cameras streaming on the box; boot-wedge self-heal proven on hardware; DMX fixture characterised; Stream Deck driven on real hardware | tracker aim calibration; the permanent multi-camera ring on the vehicle | trained detector, DMX arbitration between tracker and deck, proximity alarm |

---

## Build and test

Detail, including exact CI gates and deploy commands, in
[`docs/BUILD.md`](docs/BUILD.md).

```bash
# Android — run all of these before every commit
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug

# Jetson
cd jetson && python3 -m unittest discover -s tests -t .
```

Measured 2026-08-14, all green: **`:app` 1008 tests**, **`:beacon` 109**,
**`jetson` 475**.

CI runs on push and PR to `main`:

- `.github/workflows/android-ci.yml` — ktlint, detekt, Android Lint, unit tests,
  assembleDebug, unscoped so both `:app` and `:beacon` are covered.
- `.github/workflows/jetson-ci.yml` — path-filtered to `jetson/**` and
  `protocol/**`; runs the Python suite plus a one-frame emit check.

---

## Data sources

- **Black Rock City GIS** — streets, plazas, blocks, trash fence, toilets and CPNs
  from [`burningmantech/innovate-GIS-data`](https://github.com/burningmantech/innovate-GIS-data).
  The 2026 dataset is bundled in `app/src/main/assets/brc/2026/`; 2025 is kept
  alongside for reference. Used under the
  [Innovate Terms of Service](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/).
- **Art and camp locations** — the BM API (`api.burningman.org`), fetched
  offline-first and cached. Embargoed until roughly three weeks pre-event. The
  2025 art layer was bundled from [`iBurnApp/iBurn-Data`](https://github.com/iBurnApp/iBurn-Data)
  (MIT); the 2026 GIS ships no art layer at all.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The bundled Black Rock City GIS data is **not** covered by that license; it is
used under the Innovate Terms of Service linked above. See [NOTICE](NOTICE) for
the full third-party attribution.
