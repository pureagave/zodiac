# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Zodiac Control — an Android tablet cockpit UI for a Judge Dredd-inspired vehicle. Built with Kotlin + Jetpack Compose, targeting **Amazon Fire and Samsung Galaxy Tab** tablets in landscape (the Galaxy Tab S9+ OLED is the main dashboard; the Fire HD 10 LCD is the performance floor). Currently a v0.1.0 prototype. Three runtime-switchable cockpit "concepts" — `RADAR`, `MAP`, and `DRIVER` (`core/model/CockpitConcept`; the original A `CRT VECTOR` and B `PERSPECTIVE` were dropped and the survivors lost their letter tags, 2026-07-04; `DRIVER`, the OLED night HUD, was added later) — share the same underlying state and an 80s green-phosphor aesthetic (neon vectors, scanlines). The active concept is picked via a top-right pill and persisted across launches; switching is purely presentational. The center of every concept renders a live Black Rock City playa map driven by a pluggable GPS source.

Package: `org.pureagave.zodiac.control`

**Monorepo — three modules:**
- `:app` — the Android cockpit app (this file's main subject).
- `:beacon` — the [Zodiac Beacon](#zodiac-beacon-sensor-hub), a headless phone sensor-hub broadcaster (`org.pureagave.zodiac.beacon`).
- `jetson/` — `zvision`, a Python vision edge box on a Jetson Orin Nano Super (a ring of thermal/RGB cameras → one full-circle threat list on the fleet bus + a DMX tracker light). Docs in `jetson/*.md`.

## Build & Test Commands

```bash
./gradlew assembleDebug              # Build debug APK
./gradlew testDebugUnitTest          # Run unit tests
./gradlew detekt                     # Static analysis
./gradlew ktlintCheck                # Code style check
./gradlew ktlintFormat               # Auto-fix formatting
```

```bash
./gradlew lintDebug                  # Android Lint (manifest/permission/API)
./gradlew assembleRelease            # R8 minify + resource shrink (unsigned without a keystore)
```

Android CI (`android-ci.yml`, `:app`-scoped) runs ktlint, detekt, **Android Lint (lintDebug)**, unit tests, and assembleDebug on push/PR to `main` (via the Gradle wrapper). A separate `jetson-ci.yml` (path-filtered to `jetson/**`) runs the `zvision` Python unit tests. Run the app gates locally before each commit; run `jetson` tests (`cd jetson && python -m unittest discover -s tests -t .`) when touching the edge box.

## Architecture

**Reactive state with Coroutines + Flow.** ViewModel subscribes to repository/gateway flows and exposes a single `StateFlow<CockpitUiState>` to the Compose UI.

**Key layers:**
- `CockpitScreen` — top-level dispatcher: reads `CockpitConcept` and routes to one of three concept screens — `RADAR` (`ui/concepts/MotionTrackerScreen`) / `MAP` (`ui/concepts/InstrumentBayScreen`) / `DRIVER` (`ui/concepts/DriverNightScreen`). RADAR/MAP render the operational readout (`ui/ops/opsReadout`) as a first-class themed footer: BRC clock / sun / **drive-to guidance** (bearing + distance + heading-relative arrow to the active `core/ops/NavTarget` — HOME/MAN/TEMPLE, chosen via a prominent full-width `DRIVE TO` bar (`ui/ops/driveToBar`) above the footer). `DRIVER` is the OLED night HUD — a mostly-black vector display that renders thermal threat contacts + minimal nav (see "Vision / threats").
- `ui/viewmodel/CockpitViewModel` — state orchestration, input validation (heading 0-359, speed 0-160), command dispatch, map/GPS/concept actions
- `ui/state/CockpitUiState` — immutable data class, updated via `.copy()` (includes `commandError` surfaced from failed command sends)
- `data/VehicleConnectionGateway` / `data/RoutedVehicleGateway` — interface + pure router that forwards commands to the currently selected transport adapter (note: switching transports does **not** disconnect the old adapter — see `RoutedVehicleGatewayTest`)
- `data/TelemetryRepository` — streams `Telemetry` via Flow
- `data/transport/TransportAdapter` — pluggable interface (connect/disconnect/send) per transport type (BLE/USB/WiFi)
- `core/model/VehicleCommand` — sealed interface (`SetHeading`, `SetSpeed`)
- `core/connection/ConnectionModels` — TransportType enum, ConnectionPhase, ConnectionState

**GPS / location (see "GPS sourcing"):** `data/sensor/*LocationSource` (Fake/System/BLE/USB/**Network**) behind `RoutedLocationSource` + `LocationSourceRegistry`, feeding `data/sensor/nmea/NmeaParser`. Same selector-chip pattern as transports. `NetworkLocationSource` (NET) is the shipped, verified shared-WiFi fleet path — it also parses the beacon's five proprietary sensor sentences (see "Beacon sensor channels") and exposes them via a `beaconSensors` flow.

**Beacon sensor channels:** the `:beacon` broadcasts five proprietary NMEA sentences beyond GPS/heading/tilt — `$ZAUD` (mic rms/peak/beat), `$ZENV` (ambient lux), `$ZSHK` (shock/impact g), `$ZBCN` (beacon health: battery/fix/sats/uptime), `$ZODO` (trip+lifetime odometer). `NmeaParser` parses them into `core/telemetry/*` models (`AudioLevel`, `AmbientLight`, `ShockEvent`, `BeaconHealth`, `Odometer`, aggregated in `BeaconSensors`); `NetworkLocationSource.beaconSensors` → `CockpitViewModel` → `CockpitUiState`. `$ZENV` lux drives an **auto-dim** of screen brightness: `ui/state/ScreenBrightness.luxToBrightness` (log-scaled) applied by `MainActivity.autoDim`.

**Vision / threats (DRIVER concept):** `data/vision/*ThreatSource` (Fake/Network) behind `RoutedThreatSource` consume `ThreatProtocol` frames the Jetson broadcasts on the fleet threat group; `core/vision/DriverThreat` + `ThreatProtocol` (byte-exact mirror of the Python side) model per-contact bearing/size/collision. `relAzDeg` is a **full-circle** bearing (±180) since the edge box fuses a ring of cameras — but `DriverNightScreen` still draws only the forward half (`HUD_FORWARD_ARC_DEG`), so rear contacts ride the bus undisplayed until the surround HUD lands.

**Playa map + navigation:** `data/playa/` (GeoJSON parser → binary cache → `PlayaMapRepository`), `core/geo/` (equirectangular `PlayaProjection`, `PlayaViewport`), `core/navigation/` (`PlayaNavigator`, clock-bearing cues), rendered by `ui/playamap/` (projection, markers, labels, pan/pinch touch input). Active year is a single source of truth: `core/geo/GoldenSpike.ACTIVE` = `Y2026` (base assets in `app/src/main/assets/brc/2026/`; the 2026 city moved ~583 m SW from 2025 but the 12:00 axis is still 45°). The 2026 GIS ships no art layer — art/camp markers come from the BM API and stay hidden until BM releases 2026 data (~3 weeks pre-event).

**Preferences:** `data/prefs/DataStoreCockpitPreferences` persists GPS source / map mode / tilt / zoom / concept / burn-in config across launches (Jetpack DataStore).

**Burn-in mitigation (OLED dashboard):** `burnin/` — `BurnInMitigationManager` (process-lifetime idle state machine: ACTIVE → DIM → DEEP_IDLE → SLEEP, on an injectable clock; activity = touch / real GPS movement / link change) drives `burnInScaffold`, which wraps the whole cockpit from one node in `CockpitScreen`. Pixel-shift is universal; the brightness breathe/dim `graphicsLayer` is OLED-gated off on the Fire (`BurnInDeviceProfile`). DEEP_IDLE renders `standbyScreen`; SLEEP is app-drawn black + min backlight (Activity stays foreground, instant wake). Corner long-press = park / hidden `burnInTuningPanel`. All params are `BurnInConfig` (self-coercing) and preferences-backed.

**All transports are currently fake** (FakeTransportAdapter, FakeTelemetryRepository). Real BLE/USB/WiFi transport adapters are a future milestone; the GPS location sources, by contrast, have real System/BLE/USB implementations.

**DI is manual** in `ZodiacApplication.kt` (process-lifetime scope) — no Hilt/Dagger. Dependencies are created and wired up directly.

## GPS sourcing

Fire tablets have no built-in GNSS. Architecture is a pluggable `LocationSource` (FAKE / SYSTEM / BLE / USB / **NET**), parallel to the transport adapter pattern. Fleet target is 8-10 tablets in one vehicle, so a single shared sensor hub broadcasts on the car's local WiFi rather than per-tablet receivers. **NET is shipped and verified end-to-end.**

- **Hub:** the `:beacon` app (Zodiac Beacon), on the XCover Pro phone today; Pi Zero 2 W + u-blox USB GNSS + roof antenna later. The travel router keeps the AP/DHCP role.
- **Tablet side:** `NetworkLocationSource` listens on the fixed fleet multicast group `239.7.7.10:10110` (subnet-broadcast fallback; holds a `MulticastLock`), feeds lines into `NmeaParser`, emits `LocationSourceState` like every other source. Same selector chip pattern.
- Earlier bring-up used an iPhone running GPS2IP to prove `NetworkLocationSource` before the beacon app existed.

## Zodiac Beacon (sensor hub)

`:beacon` (`org.pureagave.zodiac.beacon`) is a headless foreground-service app that turns a phone into the vehicle's sensor hub. `TelemetryBroadcaster` forwards raw GNSS NMEA verbatim and adds: `$GPHDT` (compass true heading, from `Nmea.hdt`), `$ZTLM` (IMU pitch/roll + speed), and the five proprietary channels — `$ZAUD` (mic levels via `AudioLevels`), `$ZENV` (lux), `$ZSHK` (shock via `ShockDetector`), `$ZBCN` (health), `$ZODO` (odometer via `TripOdometer`). Sentence builders + XOR checksum are in `Nmea.kt`. Broadcasts to the fixed multicast group + subnet-broadcast fallback on UDP `10110`. Only a mic level/beat number leaves the phone — no audio is recorded or transmitted. Tests: `beacon/src/test/.../{Nmea,AudioLevels,ShockDetector,TripOdometer}Test.kt`.

## UI Structure

`CockpitScreen` dispatches on the active `CockpitConcept` (three concepts). `RADAR` (`MotionTrackerScreen`) is the *Aliens* M41A sweep-scope — a circular scope whose sweep arm lights up the real BRC map. `MAP` (`InstrumentBayScreen`) is the *Alien* Nostromo gauge-wall — bordered tiles (heading dial, speed gauge, ground-track map, cell/throttle gauges). `DRIVER` (`DriverNightScreen`) is the Star-Wars-vector OLED night HUD for the driver phone — mostly-black, thermal threat contacts as hollow wireframe figures + minimal nav. RADAR/MAP share the control strip (`ConceptControls`) and the `opsReadout` footer, each rendered in its own palette.

Center-viewport touch drives the **map**, not the vehicle: drag to pan, pinch to zoom, two-finger twist to rotate (`ui/playamap/MapTouchInput`). Heading/speed are set programmatically / by the synthetic GPS, not by tapping the viewport. (An earlier X→heading / Y→speed mapping was replaced by the map interaction.)

Color system (semantic, set 2026-07-04; shared constants in `ui/concepts/ConceptTheme`): pure black bg; **green `#00FF66`** for all chrome/controls/buttons/labels; **blue `#00BFFF`** for status only (link/connection/GPS state, selected control); **purple `#C77DFF`** for live data values (heading/speed/range/zoom, clock, distance, gauge needles, ego marker, map landmarks); **red `#FF5555`** for faults / extreme warnings only. Amber is banned.

## Conventions

- **Kotlin 2.0.21**, JDK 17, Compose BOM 2024.11.00, AGP 8.7.3
- Detekt config at `config/detekt/detekt.yml` — `MagicNumber`, `MaxLineLength`, `LongMethod` disabled; `ReturnCount` relaxed to 3 (guard-clause validation), `TooManyFunctions` bumped for the canonical screen/ViewModel, `FunctionNaming` loosened for lowercase composables. Broad `catch (Exception)` at hardware/IO boundaries is `@Suppress`ed locally with a rationale rather than rule-disabled.
- KtLint in Android mode, strict (fails on violations)
- Landscape-only, minSdk 30, targetSdk 35
- Test with JUnit 4 + kotlinx-coroutines-test (`runTest`, `advanceUntilIdle`)
- Tests use a `MainDispatcherRule` TestWatcher for coroutine dispatcher setup

## Workspace

- `tasks/open.md` — active work items; `tasks/done.md` — completed
- `design/` — UI concept docs and vehicle wireframe references
- Tone: direct, technical, code over commentary. The user is a hardware/systems engineer.

## How we work

- **`SYNC.md` is append-only.** Anything significant we decide, learn, or build gets a dated entry there. Newest entries on top; never rewrite past entries — supersede with a new one. This is the project's working memory.
- **`README.md` is the public-facing snapshot.** Update it whenever a major feature ships or the architecture changes (new layer, new dependency category, new build step). Don't log progress there — that's what SYNC.md is for.
- **Commit regularly and keep CI green.** Land work in small, runnable commits rather than big batches. Before each commit: run `./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug` (the CI gates) and fix anything red. Never mark a task complete with the build broken.
- **Phased features get phased commits.** When a feature has phases (data → render → integrate), each phase is its own commit and leaves the app runnable.
- **Push to `origin/main` after each phase commit.** GitHub Actions CI runs the same gates on every push, so anything green locally should stay green upstream. Pause and confirm before any destructive remote operation (force-push, branch delete, history rewrite).
