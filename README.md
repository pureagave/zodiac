# Zodiac Control

Android tablet cockpit app for the mutant Zodiac vehicle (Judge Dredd taxi-inspired).

For the full system design (hardware devices, the fleet network bus, sensors, roadmap), see [`ARCHITECTURE.md`](ARCHITECTURE.md). For the running decision log (architecture choices, audit follow-ups, hardware lessons), see [`SYNC.md`](SYNC.md). Current open items live in [`tasks/open.md`](tasks/open.md).

This repo is a monorepo: the Android cockpit app (`:app`), a phone sensor-hub broadcaster (`:beacon`, the [Zodiac Beacon](#zodiac-beacon-beacon)), and a Jetson vision edge box (`jetson/`, [zvision](jetson/README.md)).

## Current build

- **Concepts:** three cockpit aesthetics shipped together, runtime-switchable via the `NAME >` pill in the top-right of every screen. The choice persists across launches. (The original A CRT VECTOR and B PERSPECTIVE GRID were dropped 2026-07-04; the remaining ones lost their letter tags.)
  - **RADAR** — *Aliens* '86 M41A motion-tracker sweep scope
  - **MAP** — *Alien* '79 Nostromo instrument-bay gauge wall (green phosphor)
  - **DRIVER** — Star-Wars-'83 vector night HUD for the OLED driver phone — thermal threat contacts + minimal nav
- **Package:** `org.pureagave.zodiac.control`
- **minSdk:** 28 (Android 9 / Fire OS 7) — covers the Fire HD 10 9th gen and Fire HD 8 10th gen as passenger displays; older 8" Fires are Fire OS 6/5 (API 25/22) and are not supported
- **targetSdk / compileSdk:** 35
- **UI stack:** Jetpack Compose + Material3
- **Fleet devices:** Galaxy Tab S9+ (`SM-X810`, 12.4" OLED) — hero dashboard; Galaxy A54 (`SM-A546V`, OLED) — night-driver phone (DRIVER concept); XCover Pro (`SM-G715U`, Android 10) — the headless [Zodiac Beacon](#zodiac-beacon-beacon) sensor/GPS hub; Fire HD 10 (`KFTUWI`, LCD) — passenger displays / performance floor.
- **Orientation:** responsive — landscape *or* portrait (`fullUser`); each concept reflows its layout to the screen shape, so it runs on portrait-mounted small tablets too.

## What is implemented

- Three runtime-switchable cockpit concepts (RADAR / MAP / DRIVER) sharing the same underlying state (heading, speed, transport, GPS source, telemetry, BRC map, ego fix); only presentation differs. Tap the corner pill to cycle.
- BRC map rendered into both concepts with concept-specific palettes (dim/lit sweep scope for RADAR, green blocky tiles for MAP). Pinch-zoom, drag-pan, recenter, and a TOP/TILT mode toggle are wired to every concept's rail.
- Drive-to navigation: pick a destination from a prominent full-width `DRIVE TO` button bar — HOME (camp at Heiau & 2:15), MAN (Golden Spike), TEMPLE, BATH (nearest toilet, re-resolved live), or ADDR (type any city address on a full-screen keypad: clock time → A–K ring → 5-sec heading flash → live nav). The ops footer shows bearing + distance + a heading-relative arrow.
- Street-aware routing: guidance follows how you actually drive BRC — free-drive across the open playa, but inside the city the route goes *playa → nearest entrance radial ∩ Esplanade → out to the ring → along the ring to the address*. Every in-city corner is snapped to the **nearest vertex of the real GIS street polylines** (not an idealised circle), so the drawn line lies on the drawn streets instead of cutting across camps. The chevron + footer steer to the next corner (not straight-line), and the dashed route is drawn on both concepts (`core/navigation/PlayaRoute`).
- Street-crossing popups: the street you're on / just crossed flashes big top-centre ("ENTERING ATWOOD") as you drive, using the real BRC street names — situational awareness without looking down.
- "Passing" callouts: drive within range of a notable art piece and its name flashes bottom-centre ("◂ PASSING S.A.N. Forest Interface"), from the offline discovery cache — passenger flavour.
- Heading-guidance chevron (both concepts — MAP's second card and a RADAR bar above the scope): a big glance-and-steer indicator — a thick chevron rides a track, sliding to show how far off the active target is (`0.5 + Δ/360`: dead-ahead centred, 90° right at 75%, ±180° hard against an edge) and pointing the way to turn (► / ◄), recolouring to blue "ON COURSE" with an up chevron when you're lined up.
- Playa discovery (offline-first Burning Man API cache): nearby art + theme camps plot as contacts on the RADAR scope (art = pink diamond, camp = purple dot), positioned by real playa location relative to the ego and pulsing with the sweep; the active drive-to target is a distinct ringed blip.
- RADAR's M41A-style sweep arm illuminates the real BRC map — features brighten as the wedge passes over them rather than as static blips.
- Zoom-gated map labels in MAP: plazas, named arcs and clock-position radials, CPNs, and art (major art like The Temple and The Man come in earlier than self-funded). Street labels are deduplicated across the BRC source's per-segment features so each logical street draws once. Toilets are unlabelled but recoloured BRC porta-potty purple as the type indicator.
- DRIVER night HUD: a mostly-black vector display for the OLED driver phone that renders thermal **threat contacts** broadcast by the Jetson edge box (`data/vision/NetworkThreatSource` → `core/vision/ThreatProtocol`, listening on the fleet threat group) as hollow wireframe figures with deep-red lock brackets on a collision-course contact, over minimal on-playa/in-city nav context. Falls back to a synthetic threat demo when no edge box is on the bus.
- Center viewport: BRC map (trash fence, streets, plazas, toilets, CPNs, art) with track-up rotation following vehicle heading. Two map modes: `TOP` (orthographic, ego at center — default) and `TILT` (~40° pitched 3D with a retro perspective-grid backdrop, ego anchored to the lower third — Battlezone / Out-Run feel). Touch pans (drag), zooms (pinch), and rotates (two-finger twist) the map; heading/speed come from the GPS fix, not from tapping the viewport.
- Art layer: for 2025, placements bundled from iBurn-Data (majors — Honorarium + ManPavGrant — drawn larger than self-funded and labelled at lower zoom). For 2026, the bundled GIS ships no art layer — art/camp locations come from the BM API and are embargoed until ~3 weeks pre-event, so no static markers show until BM releases them.
- GPS / location source abstraction: pluggable `LocationSource` with five implementations — synthetic `FakeLocationSource` (default, slow circle around the Spike for testing), Android `LocationManager`, Bluetooth Classic SPP NMEA receivers, USB serial NMEA dongles via [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android), and **`NetworkLocationSource` (NET)** — the shared-WiFi fleet path, listening for NMEA on the fixed fleet multicast group (with a subnet-broadcast fallback) on UDP 10110 (holds a `MulticastLock` so Android doesn't filter the broadcast). Source selectable at runtime via the right-rail GPS chips. Map viewport centers on the live ego fix.
- Beacon sensor channels: beyond GPS/heading/tilt, `NetworkLocationSource` parses five proprietary NMEA sentences from the [Zodiac Beacon](#zodiac-beacon-beacon) — `$ZAUD` (mic sound rms/peak/beat), `$ZENV` (ambient lux), `$ZSHK` (shock/impact g), `$ZBCN` (beacon health: battery/fix/sats/uptime), `$ZODO` (trip + lifetime odometer) — into `core/telemetry` models, surfaced through `CockpitViewModel` into `CockpitUiState` (`ui/state/CockpitUiState`).
- Ambient-light auto-dim: `$ZENV` lux drives the tablet's screen brightness through `ui/state/ScreenBrightness.luxToBrightness` (log-scaled night→day floor/ceiling), applied by `MainActivity.autoDim` — the dashboard follows the real ambient light without a manual brightness change.
- **Passenger display** (`ui/passenger/`, `core/passenger/`): a self-running carousel aimed at the people riding the car rather than the driver — playa address, a mic-driven oscilloscope (`$ZAUD`), the thermal contacts around the vehicle, an impact "bump" gauge, trip odometer, sunrise/sunset countdown, and nearest art. Every card is fed by data already broadcasting on the fleet bus, so any number of tablets can run off one beacon and one edge box. Cards with no data are dropped from the rotation rather than shown empty, events (a street crossing, a big bump) interrupt the rotation and then hand the screen back, and type scales off the panel so 8" and 10" Fires both fill. Enabled per device via a hidden top-right long-press (`core/passenger/DisplayRoleStore`), which also hides the concept switcher so a rider can't wander into the driver's HUD.
- Scanline overlay
- OLED burn-in mitigation (`burnin/`) for the S9+ dashboard, wrapping every concept from one node via `burnInScaffold`: whole-UI pixel-shift, a subtle brightness breathe + idle-dim (OLED-only — gated off on the LCD Fire), and an idle state machine (ACTIVE → DIM → CRT "STANDBY" screen → app-drawn black sleep with instant wake-on-touch/GPS-movement/link-change). Manual park (top-left long-press) and a hidden, preferences-backed tuning panel (bottom-left long-press) for on-playa adjustment of every timeout/parameter. All four phases verified on the S9+ OLED.
- Operational-awareness readout (`core/ops/` + `ui/ops/opsReadout`): a first-class, palette-driven footer in each concept (rendered in that concept's own aesthetic, not a shared overlay) showing the BRC clock, today's sunrise/sunset (local NOAA calc, no API), and a live return-to-camp distance + heading-relative arrow to Heiau & 2:15. No network required.
- Full-screen kiosk chrome: draws edge-to-edge and hides the status/nav bars (immersive), required because targetSdk 35 forces edge-to-edge on Android 15+ (the S9+).
- Black Rock City map data layer: 2026 GIS bundled in `app/src/main/assets/brc/2026/`, parsed into a typed `PlayaMap` and projected via `PlayaProjection` (equirectangular, anchored on the Golden Spike) and `PlayaViewport` (track-up, configurable zoom). The active year is a single source of truth (`core/geo/GoldenSpike.ACTIVE` = `Y2026`): the 2026 city translated ~583 m SW from 2025 but did not rotate, so the 12:00 axis stays at 45° true. (2025 assets remain in `brc/2025/` for reference.)
- Persisted preferences via `androidx.datastore.preferences` — last-picked GPS source, map mode, tilt angle, and zoom survive a restart.
- On-device postmortem logging (`core/log/RollingFileLog` + `data/log/FileLogTree`, on Timber): every build writes a size-capped, rotating log under `getExternalFilesDir("logs")`, so a misbehaving fleet tablet is diagnosable after the fact. On the Samsungs it comes off with a plain `adb pull` — no root, no debug build. **On the Fire it does not**: Fire OS denies shell access to `/sdcard/Android/data`, so `adb pull` fails with "Permission denied" and the on-device viewer below is the only way in. Bounded (`maxBytes × (keep+1)`, oldest dropped first), never throws, and writes drain on IO through a bounded channel so a log call can't stall a frame. Tags the lifecycle events that actually get asked about — GPS source selection, NET→SYSTEM failover, vision feed LIVE/DEMO/ABSENT, transport connect, which path served the map — plus uncaught exceptions, written synchronously before the process dies. A hidden **bottom-right long-press opens an on-device log viewer** (last 400 lines, severity-coloured, with the dropped-line count) for when there's no laptop next to the vehicle.
- GPS failures name their own fix: `core/sensor/LocationSourceError` categorises every failure by what the operator would do about it — `⊘ PERMISSION` / `⊘ ADAPTER OFF` / `? NO DEVICE` / `✕ I/O` — shown under the GPS chips in the control strip, with the free-text detail going to the rolling log.
- Baseline quality tooling (ktlint + detekt)
- GitHub Actions CI (lint + static analysis + unit tests + debug assemble)

## Data sources

- **Black Rock City GIS** — streets, plazas, blocks, trash fence, toilets, CPNs from [`burningmantech/innovate-GIS-data`](https://github.com/burningmantech/innovate-GIS-data) (2026 Innovate dataset bundled in `brc/2026/`; 2025 kept in `brc/2025/`). Subject to the [Innovate Terms of Service](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/).
- **Art / camp locations** — from the BM API (`api.burningman.org`), fetched offline-first and cached; embargoed until ~3 weeks pre-event, so nothing renders until BM releases the 2026 data. (2025 art was bundled from [`iBurnApp/iBurn-Data`](https://github.com/iBurnApp/iBurn-Data) (MIT) as a stripped `art.geojson`; the 2026 GIS ships no art layer.)

## GPS sourcing (8-10 tablets, single vehicle)

Fire tablets have no built-in GNSS, and per-tablet receivers don't scale (Bluetooth GPS pucks cap at ~5 simultaneous clients), so the fleet uses **a single shared sensor hub on the car's local WiFi** and every tablet reads the same UDP stream. The tablet's `NetworkLocationSource` (`NET`) — the fifth `LocationSource` alongside FAKE / SYSTEM / BLE / USB — listens on UDP `10110`, feeds lines into `NmeaParser`, and follows the same `StateFlow<LocationSourceState>` contract and right-rail chip pattern as the other sources. **This path is built and verified end-to-end.**

The shared hub is the [Zodiac Beacon](#zodiac-beacon-beacon) (`:beacon`), running on the XCover Pro phone today (Pi + u-blox later). Earlier bring-up used a spare iPhone running [GPS2IP](https://capsicumdreams.com/iphone/gps2ip/) — it validated `NetworkLocationSource` before any custom app existed.

## Zodiac Beacon (`:beacon`)

A headless Android foreground-service app that turns a phone into the vehicle's **sensor hub**, broadcasting to the whole fleet over WiFi (fixed multicast group `239.7.7.10:10110` with a subnet-broadcast fallback for APs that drop multicast). It forwards the phone's raw GNSS NMEA verbatim and synthesizes/adds:

- `$GPHDT` — true compass heading from the magnetometer (updates even when stopped, where GPS course is meaningless)
- `$ZTLM` — IMU pitch/roll + ground speed
- `$ZAUD` — mic sound level (rms/peak/beat) for sound-reactive lighting; only a level/beat number leaves the phone, no audio
- `$ZENV` — ambient lux (drives the tablets' auto-dim)
- `$ZSHK` — shock/impact peak-g events (event-driven)
- `$ZBCN` — beacon health heartbeat: battery %, GNSS fix quality + sat count, uptime
- `$ZODO` — trip + lifetime odometer (persisted)

Runs GPS + compass verified on the XCover Pro; the new sensor channels are consumed by every tablet's `NetworkLocationSource`.

## Jetson edge box (`jetson/`)

A roof-mounted **Jetson Orin Nano Super** vision node (`zvision`, Python) that turns thermal / RGB camera frames into vehicle-relative **threat contacts** and broadcasts them on the fleet threat group (`239.7.7.20:10120`), where every tablet's DRIVER HUD is listening. It runs a **camera ring** — one 160° ultra-wide thermal forward plus RGB cameras around the body, each declared with its mounting bearing and lens (`--camera`, `zvision/rig.py`) — and fuses them into one **full-circle** contact list: pixels unprojected through a real lens model (`zvision/geometry.py`), bearings rotated into vehicle terms, and the same person seen by two overlapping cameras collapsed to one contact. The wire format (`zvision/threat_protocol.py`) carries bearings across the full ±180 and is a byte-exact mirror of the tablet's Kotlin `core/vision/ThreatProtocol`. (The DRIVER HUD still draws only the forward half; the surround layout is the follow-up.) A second, optional output drives a **DMX moving-head "tracker" light** (`zvision/tracker.py` + `zvision/dmx.py`, over OLA) that points at detected contacts and — when idle — pulses to the beacon's `$ZAUD` audio. Software is built and tested (`--source fake` proves the bus with no hardware); see [`jetson/README.md`](jetson/README.md), [`jetson/DEPLOY.md`](jetson/DEPLOY.md), [`jetson/HARDWARE.md`](jetson/HARDWARE.md), [`jetson/DETECTOR.md`](jetson/DETECTOR.md).

## CI

Two workflows run on pushes/PRs to `main`:

**Android** — `.github/workflows/android-ci.yml` (via the Gradle wrapper, `:app`-scoped):
1. `:app:ktlintCheck`
2. `:app:detekt`
3. `:app:lintDebug` (Android Lint)
4. `:app:testDebugUnitTest`
5. `:app:assembleDebug`

**Jetson** — `.github/workflows/jetson-ci.yml` (path-filtered to `jetson/**`): runs the `zvision` unit tests (`python -m unittest`, standard-library only) + a one-frame emit check.

Release builds (`:app:assembleRelease`) run R8 minify + resource shrink and are signed when `ZODIAC_KEYSTORE_FILE` (+ matching password/alias) is provided via env or gradle properties; otherwise they build unsigned.

## Run locally

1. Open project in Android Studio (latest stable).
2. Install JDK 17 + Android SDK if missing.
3. Run app module on Fire tablet or emulator.

## Next sprint recommendations

- On-device verify of the 2026 base map: render the moved city and type a known address (e.g. the camp at 2:15 & H) to confirm it lands on the right corner before trusting nav (`tasks/open.md`).
- Bring up the Jetson edge box on hardware ([`jetson/DEPLOY.md`](jetson/DEPLOY.md)): flash → prove the bus with `--source fake` → attach the thermal/RGB camera → optional DMX tracker light; then wire real thermal detections into the DRIVER HUD.
- Operational logging (Timber + rolling file) so a misbehaving tablet can be postmortem'd on the playa (`tasks/open.md` M10).
- Compose UI / instrumented tests for the map touch interaction (drag-pan / pinch / twist), which has no automated coverage.
- Validate the R8-shrunk release APK on a real Fire tablet before fleet distribution.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Bundled Black Rock City GIS data is **not** covered by that license; it is used
under the [Innovate Terms of Service](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/).
See [NOTICE](NOTICE) for the full third-party data attribution.
