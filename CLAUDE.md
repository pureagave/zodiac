# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project

**Zodiac** — software for a Judge-Dredd-themed mutant vehicle (art car) run by
the Galactic Relay camp at Burning Man. Three parts, one system:

- **`:app`** — the Android tablet cockpit (`org.pureagave.zodiac.control`). Kotlin
  + Jetpack Compose. Three runtime-switchable "concepts" — `RADAR`, `MAP`,
  `DRIVER` — over a live Black Rock City playa map.
- **`:beacon`** — the [Zodiac Beacon](#beacon--the-sensor-hub)
  (`org.pureagave.zodiac.beacon`), a headless phone sensor hub that broadcasts
  NMEA over UDP multicast to the fleet.
- **`jetson/`** — the Python edge box on a Jetson Orin Nano Super: `zvision`
  (camera ring → one full-circle threat list on the fleet bus + a DMX tracker
  light) and `zdeck` (a six-key Stream Deck control surface).

**Read first:** [`README.md`](README.md) for what exists,
[`ARCHITECTURE.md`](ARCHITECTURE.md) for how it is put together,
[`docs/PROTOCOLS.md`](docs/PROTOCOLS.md) for the wire formats between the parts,
[`docs/BUILD.md`](docs/BUILD.md) for build/test/deploy, [`SYNC.md`](SYNC.md) top
entries for what just happened.

## The rule that matters most

**Measure, don't guess.** A fixture manual said the moving head's tilt range was
270°; it is 180°, and believing the manual scaled every tilt command by 1.5× and
cost real hours. Where a number here came from a datasheet rather than a
measurement, say so. When a doc and the code disagree, **the code wins** — fix
the doc and record the correction in `SYNC.md`.

The corollary: a test that cannot fail is worthless. This project has been bitten
by tests that agreed with the code they tested more than once.

## Build & test

```bash
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug  # before every commit
./gradlew ktlintFormat                                                  # auto-fix style
cd jetson && python3 -m unittest discover -s tests -t .                 # when touching the edge box
```

Counts as of 2026-08-14, all green: `:app` **1008**, `:beacon` **109**,
`jetson` **475**.

CI on push/PR to `main`:

- `android-ci.yml` — ktlint, detekt, `lintDebug`, unit tests, `assembleDebug`,
  **unscoped**, so both `:app` and `:beacon` are covered. Run them unscoped
  locally too; a `:app:`-prefixed task silently skips the beacon.
- `jetson-ci.yml` — path-filtered to `jetson/**` and `protocol/**`; runs the
  Python suite plus a one-frame emit check.

Java and the Android SDK may not be on `PATH`; set `JAVA_HOME` / `ANDROID_HOME`
inline per command if `./gradlew` cannot find them.

## `:app` architecture

**Reactive state with Coroutines + Flow.** Delegates fold repository and source
flows into one `StateFlow<CockpitUiState>` that the whole UI reads.

**Manual DI** in `ZodiacApplication.kt` (process-lifetime scope) — no Hilt or
Dagger. Everything is `by lazy`; `onCreate` does the only ordered work.

### Layers

- **`CockpitScreen`** — top-level dispatcher. Wrapped by `burnInScaffold`, routes
  on `CockpitConcept` to `MotionTrackerScreen` (RADAR) / `InstrumentBayScreen`
  (MAP) / `DriverNightScreen` (DRIVER), or bypasses all three for
  `passengerScreen` when this device holds the passenger role.
- **`ui/viewmodel/`** — **five** files: `CockpitViewModel` (~536 lines, 20 public
  functions) owns the single `MutableStateFlow<CockpitUiState>` and the flow
  wiring, and delegates to `MapCameraController` (pan/zoom/rotate/tilt/recenter),
  `NavigationController` (drive-to, routing, street and art callouts),
  `GpsController` (source selection, fake-GPS nudges) and `NavShareController`
  (adopt/broadcast the shared `$ZNAV` destination). The public API is the
  ViewModel's; the delegates are internal collaborators.
- **`ui/state/CockpitUiState`** — one immutable data class, 36 fields, updated by
  `.copy()`. The per-frame copy cost is known and tracked as A5 in `tasks/open.md`.
- **`core/`** — all pure logic, no Android: `geo`, `navigation`, `ops`, `vision`,
  `telemetry`, `sensor`, `model`, `connection`, `log`, `net`, `passenger`,
  `permission`, `kiosk`. **No new logic in a composable** — draw code turns decisions into
  pixels; decisions live in `core/` with tests. `SurroundRing` /
  `SurroundRingCanvas` is the model split to follow.
- **`data/`** — `VehicleConnectionGateway` / `RoutedVehicleGateway`,
  `TelemetryRepository`, `data/transport/TransportAdapter`,
  `data/sensor/*LocationSource`, `data/vision/*ThreatSource`,
  `data/playa/PlayaMapRepository`, `data/discovery/DiscoveryRepository`,
  `data/prefs/DataStoreCockpitPreferences`, `data/log/FileLogTree`.

**Input validation lives in the ViewModel layer**, so every entry point — chip,
gesture, synthetic GPS, persisted preference — is bounded identically: heading
0–359, speed 0–160 kph, tilt 0–80°, zoom 0.05–5.0 px/m, camera pan ±5000 m.

### Two routers, opposite lifecycle policies — both intentional

`RoutedLocationSource.select()` **stops** the old source before starting the new
one. `RoutedVehicleGateway.selectTransport()` **leaves** the old adapter
connected. Both are documented in place and pinned by tests.
`FailoverLocationSource` exists because the location policy makes
failover-on-top-of-routing impossible any other way.

### GPS / location

Five sources behind `LocationSource` — `FAKE` / `SYSTEM` / `BLE` (Bluetooth
Classic SPP) / `USB` (serial) / **`NET`** — selected at runtime through
`RoutedLocationSource` + `LocationSourceRegistry` and the right-rail chips.

**`NET` is the shipped, verified fleet path.** `NetworkLocationSource` binds
wildcard on `239.7.7.10:10110`, joins the group, holds a `MulticastLock`, and
feeds `NmeaParser`. It rebuilds its socket on exponential backoff after a failure
or 20 s of silence — rebuilding is the only way to re-join a group after a router
reboot. `start()` is idempotent; `stop()` joins the listener before clearing.

`FailoverLocationSource` wraps NET with SYSTEM, presents itself as `NET`, and
keeps both warm. Drop after 3 s unhealthy, recover only after 10 s healthy —
deliberately asymmetric so a half-alive beacon cannot flap the source. Armed only
where `FEATURE_LOCATION_GPS` is reported.

`NmeaParser` handles GGA, RMC, HDT and the six proprietary `$Z*` types, and
**deliberately rejects VTG, HDG and HDM** (see `docs/PROTOCOLS.md` §2.3 — this is
load-bearing, don't "fix" it).

### Beacon sensor channels

The five proprietary channels — `$ZAUD` (mic rms/peak/beat), `$ZENV` (lux),
`$ZSHK` (shock g), `$ZBCN` (health), `$ZODO` (odometer) — parse into
`core/telemetry/*` and flow `NetworkLocationSource.beaconSensors` →
`CockpitViewModel` → `CockpitUiState`. `$ZAUD` is held out of `beaconSensors`
because it updates ~15 Hz. `$ZENV` lux drives auto-dim through
`ScreenBrightness.luxToBrightness`, applied by `burnInScaffold`, which is the
**single writer** of screen brightness.

### Vision / threats (DRIVER)

`data/vision/*ThreatSource` behind `RoutedThreatSource` consume `ZTHREAT` frames
from `239.7.7.20:10120`. `RoutedThreatSource.demoEnabled` has **no default
value**, deliberately — it once defaulted to `true` and put fabricated contacts
on the driver's HUD. `ZodiacApplication` passes `false`.

`VisionFeed` is `LIVE` / `DEMO` / `ABSENT` and is surfaced, never hidden. An
empty-but-live feed is a real all-clear and must not trigger demo fallback.

`relAzDeg` is **full-circle** (±180). The forward perspective view filters to
`PERSPECTIVE_ARC_DEG = 30f`; the **surround ring carries every contact** and is
rendered by `SurroundRingCanvas` with all decisions in `core/vision/SurroundRing`.

### Playa map + navigation

`data/playa/` (GeoJSON → binary cache → `PlayaMapRepository`), `core/geo/`
(equirectangular `PlayaProjection`, track-up `PlayaViewport`), `core/navigation/`
(`PlayaNavigator`, `ClockBearing`, `PlayaCityModel`, `PlayaRoute`), rendered by
`ui/playamap/`.

Active year is one source of truth: `GoldenSpike.ACTIVE = Y2026` (assets in
`app/src/main/assets/brc/2026/`). The 2026 city moved ~583 m SW from 2025 but did
not rotate — the 12:00 axis is still 45° true. The 2026 GIS ships **no art
layer**; art and camp markers come from the BM API and stay hidden until BM
releases the data (~3 weeks pre-event).

Routing snaps every in-city corner to a real GIS street vertex, which is what
keeps the drawn route on the drawn streets.

### Other subsystems

- **Passenger display** (`ui/passenger/`, `core/passenger/`) — a self-running card
  carousel for riders. **A card with no data leaves the rotation rather than
  showing empty**, and the "souls" card requires a genuinely `LIVE` vision feed.
  Toggled by a hidden top-right long-press, hidden in both directions.
- **Burn-in mitigation** (`burnin/`) — `BurnInMitigationManager` is a pure phase
  machine (ACTIVE → DIM → DEEP_IDLE → SLEEP at 5 / 30 / 60 min) on an injectable
  clock. `burnInScaffold` wraps the whole cockpit from one node. Pixel-shift is
  universal; the breathe/dim layer is OLED-gated by
  `BurnInDeviceProfile` (`Build.MANUFACTURER != "Amazon"`). All parameters are in
  the self-coercing `BurnInConfig`, preferences-backed and live-tunable.
- **Kiosk** (`kiosk/`, `docs/KIOSK.md`) — device-owner lock task. Every step is a
  no-op without device owner. **Never rename `ZodiacDeviceAdminReceiver`** — it
  breaks every already-provisioned tablet.
- **Logging** (`core/log/RollingFileLog`, `data/log/FileLogTree`) — size-capped
  rotating log that never throws and **counts everything it discards**. Hidden
  bottom-right long-press opens an on-device viewer, which on the Fire is the
  only way to read it.
- **Discovery** (`data/discovery/`) — offline-first BM API cache in `filesDir`
  (not `cacheDir` — Android may purge that). A failed or partial fetch never
  clobbers a good cache.
- **Preferences** (`data/prefs/`) — 22 DataStore keys, enums stored by name, every
  numeric read clamped, corruption handler installed. Default GPS source is
  **`NET`, not `FAKE`**.
- **Nav target share** (`core/ops/NavShare*`, `data/nav/`) — the S9+ and A54 are
  **nav-authority** tablets; only they may set + broadcast the drive-to target
  over `$ZNAV` (`239.7.7.30:10130`, `docs/PROTOCOLS.md` §5). Authority is
  automatic, not a toggle: it *is* OLED-device-ness — `BurnInDeviceProfile
  .visualModulationSupported()`, the same manufacturer check burn-in already
  uses — computed once at startup, so the Samsungs are always authorities and
  the two Fires always follow. Every tablet adopts the target through the same
  `NavigationController` entry points a local set uses, so the DRIVER HUD's
  existing heading arch just starts pointing at the shared target — no new HUD
  UI. `NavShareArbiter` is a pure `(seq, src)` Lamport ordering + single-owner
  state machine; adoption never re-publishes, which is the whole no-echo
  guarantee. Followers (the two Fires) get the target but their local
  drive-to controls are gated no-ops.

### Hidden corner gestures

Top-left = park · bottom-left = burn-in tuning panel · bottom-right = log viewer
· top-right = toggle passenger role.

## `:beacon` — the sensor hub

Headless foreground service, minSdk 29. `TelemetryBroadcaster` forwards raw GNSS
NMEA **verbatim** and synthesizes `$GPHDT`, `$ZTLM`, `$ZAUD`, `$ZENV`, `$ZSHK`,
`$ZBCN`, `$ZODO`. Sentence builders and the XOR checksum are in `Nmea.kt`; every
format is pinned to `Locale.US`.

One 250 ms tick loop drives everything periodic with integer divisors. A deadman
watchdog banners the status readout if the loop dies. Broadcasts to the multicast
group **and** a subnet broadcast on UDP 10110.

**Only a mic level and beat flag ever leave the phone** — no audio is recorded,
buffered or transmitted. An absent sensor emits **nothing** rather than a
fabricated `0.0` (heading and lux). GPS failure is never fatal — the other
channels keep broadcasting.

Tests: `beacon/src/test/.../{Nmea,AudioLevels,ShockDetector,TripOdometer,BeaconNet,
ForegroundTypes,TickLoop,...}Test.kt` — 13 files, four of them Robolectric.

## `jetson/` — the edge box

`zvision` core is **standard-library only** (numpy/opencv are an optional lazy
extra), so the whole suite and `--source fake` run anywhere.

Pipeline: `--camera` specs → `rig.build_rig` → per-camera `detect()` →
`to_global` → `merge_contacts` → `format_frame` → `ThreatBroadcaster` (10 Hz)
→ `239.7.7.20:10120`, with an optional branch into `Tracker` → `DmxSink` → `olad`.

**The detector is background subtraction, not a model.** No TensorRT, ONNX, YOLO
or weights exist in the tree. `FakeDetector` is the default source, and
`install.sh` writes `--source fake --hz 10`, i.e. no real cameras.

`zdeck` borrows its DMX channel numbers from `zvision`'s `TrackerConfig` rather
than restating them. **Never run `zvision --dmx ola` and `zdeck` at the same
time** — arbitration is unresolved and BLACKOUT would flicker rather than kill.

Measured fixture facts that contradict its manual: tilt range is **180°** (not
270°) and the dimmer is **channel 8** (not 5, which is the colour wheel). Both
are pinned by tests.

## Wire protocols

Full reference in [`docs/PROTOCOLS.md`](docs/PROTOCOLS.md).

`ZTHREAT` is a **cross-language contract between two hand-written
implementations, and neither is authoritative.** The shared truth is the measured
golden corpus at `protocol/threat-protocol-golden.json`, read by both test suites,
which fail loudly rather than skipping if it is missing. Before it existed the two
sides had silently drifted in ten measured ways. If you change the format,
re-run the differential comparison that produced the corpus — **do not hand-edit
the JSON**, which is exactly the failure it exists to prevent.

## UI conventions

Concepts share the control strip (`ConceptControls`) and the `opsReadout` footer,
each rendered in its own palette. Center-viewport touch drives the **map**, not
the vehicle: drag to pan, pinch to zoom, two-finger twist to rotate. Heading and
speed come from the GPS fix, never from tapping the viewport.

Colour system (semantic, set 2026-07-04, in `ui/concepts/ConceptTheme`): pure
black background; **green `#00FF66`** for all chrome, controls, buttons and
labels; **blue `#00BFFF`** for status only (link/GPS state, "this is selected");
**purple `#C77DFF`** for live data values (heading, speed, range, zoom, clock,
distance, gauge needles, ego marker, map landmarks); **red `#FF5555`** for faults
and extreme warnings only. **Amber is banned.** DRIVER overrides these with a
dimmer night set for dark adaptation.

## Conventions

- **Kotlin 2.0.21**, JDK 17, Compose BOM 2024.11.00, AGP 8.7.3, Gradle 8.10.2
- `:app` minSdk **28**, `:beacon` minSdk **29**, both targetSdk/compileSdk 35
- `screenOrientation="fullUser"` — every concept reflows for landscape *or*
  portrait
- Detekt config at `config/detekt/detekt.yml` — `MagicNumber`, `MaxLineLength`,
  `LongMethod` disabled; `ReturnCount` relaxed to 3; `LongParameterList` and
  `TooManyFunctions` (22 in classes) raised with a written rationale each. Broad
  `catch (Exception)` at hardware/IO boundaries is `@Suppress`ed locally with a
  reason rather than rule-disabled.
- KtLint in Android mode, strict. Android Lint `abortOnError = true` in both
  modules.
- Composables are lowercase-named (detekt's `FunctionNaming` is loosened for it)
- JUnit 4 + `kotlinx-coroutines-test` (`runTest`, `advanceUntilIdle`), with a
  `MainDispatcherRule` `TestWatcher` for dispatcher setup

## What is fake — get this right

- **Every vehicle transport is fake.** `FakeTransportAdapter` is the only
  `TransportAdapter` implementation; `FakeTelemetryRepository` the only production
  `TelemetryRepository`. Real vehicle data arrives **only** via
  `NetworkLocationSource` and `NetworkThreatSource` — never through the gateway.
- The GPS location sources, by contrast, have real System/BLE/USB/Network
  implementations.
- MAP's cell gauges and throttle trace are hard-coded literals.
- The Jetson has no trained detector. Thermal + RGB cameras now stream on the box, but the permanent multi-camera ring, on-vehicle mount/aim, and the model are not built.
- The tracker light's aim constants are uncalibrated placeholders.

## Workspace

- `tasks/open.md` — active work items; `tasks/done.md` — completed
- `design/` — UI concept docs, burn-in design, performance backlog, surround-HUD
  design
- `protocol/` — cross-language wire contracts
- Tone: direct, technical, code over commentary. The user is a hardware/systems
  engineer.

## How we work

- **`SYNC.md` is append-only.** Anything significant decided, learned or built
  gets a dated entry, newest on top. Never rewrite past entries — supersede with
  a new one. This is the project's working memory.
- **`README.md` is the public-facing snapshot.** Update it when a major feature
  ships or the architecture changes. Don't log progress there — that is
  `SYNC.md`'s job.
- **Commit regularly and keep CI green.** Small, runnable commits. Run the gates
  before each commit and fix anything red. Never mark a task complete with the
  build broken.
- **Phased features get phased commits** (data → render → integrate), each leaving
  the app runnable.
- **Push to `origin/main` after each phase commit.** Pause and confirm before any
  destructive remote operation (force-push, branch delete, history rewrite).
