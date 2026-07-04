# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Zodiac Control — an Android tablet cockpit UI for a Judge Dredd-inspired vehicle. Built with Kotlin + Jetpack Compose, targeting **Amazon Fire and Samsung Galaxy Tab** tablets in landscape (the Galaxy Tab S9+ OLED is the main dashboard; the Fire HD 10 LCD is the performance floor). Currently a v0.1.0 prototype. Three runtime-switchable cockpit "concepts" — A `CRT VECTOR`, C `TRACKER`, D `BAY` (`core/model/CockpitConcept`; B `PERSPECTIVE` was dropped 2026-07-04, tags A/C/D kept stable) — share the same underlying state and an 80s monochrome green-phosphor aesthetic (neon vectors, scanlines). The active concept is picked via a top-right pill and persisted across launches; switching is purely presentational. The center of every concept renders a live Black Rock City playa map driven by a pluggable GPS source.

Package: `ai.openclaw.zodiaccontrol`

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

CI runs ktlint, detekt, **Android Lint (lintDebug)**, unit tests, and assembleDebug on push/PR to `main` (via the Gradle wrapper). Run the same gates locally before each commit.

## Architecture

**Reactive state with Coroutines + Flow.** ViewModel subscribes to repository/gateway flows and exposes a single `StateFlow<CockpitUiState>` to the Compose UI.

**Key layers:**
- `CockpitScreen` — top-level dispatcher: reads `CockpitConcept` from state and routes to one of the three concept screens (`CRTVectorScreen` / `ui/concepts/MotionTrackerScreen` / `InstrumentBayScreen`). Each concept renders the operational readout (`ui/ops/opsReadout`: clock / sun / return-to-camp) as a first-class themed footer.
- `ui/viewmodel/CockpitViewModel` — state orchestration, input validation (heading 0-359, speed 0-160), command dispatch, map/GPS/concept actions
- `ui/state/CockpitUiState` — immutable data class, updated via `.copy()` (includes `commandError` surfaced from failed command sends)
- `data/VehicleConnectionGateway` / `data/RoutedVehicleGateway` — interface + pure router that forwards commands to the currently selected transport adapter (note: switching transports does **not** disconnect the old adapter — see `RoutedVehicleGatewayTest`)
- `data/TelemetryRepository` — streams `Telemetry` via Flow
- `data/transport/TransportAdapter` — pluggable interface (connect/disconnect/send) per transport type (BLE/USB/WiFi)
- `core/model/VehicleCommand` — sealed interface (`SetHeading`, `SetSpeed`)
- `core/connection/ConnectionModels` — TransportType enum, ConnectionPhase, ConnectionState

**GPS / location (see "GPS sourcing"):** `data/sensor/*LocationSource` (Fake/System/BLE/USB) behind `RoutedLocationSource` + `LocationSourceRegistry`, feeding `data/sensor/nmea/NmeaParser`. Same selector-chip pattern as transports.

**Playa map + navigation:** `data/playa/` (GeoJSON parser → binary cache → `PlayaMapRepository`), `core/geo/` (equirectangular `PlayaProjection`, `PlayaViewport`), `core/navigation/` (`PlayaNavigator`, clock-bearing cues), rendered by `ui/playamap/` (projection, markers, labels, pan/pinch touch input).

**Preferences:** `data/prefs/DataStoreCockpitPreferences` persists GPS source / map mode / tilt / zoom / concept / burn-in config across launches (Jetpack DataStore).

**Burn-in mitigation (OLED dashboard):** `burnin/` — `BurnInMitigationManager` (process-lifetime idle state machine: ACTIVE → DIM → DEEP_IDLE → SLEEP, on an injectable clock; activity = touch / real GPS movement / link change) drives `burnInScaffold`, which wraps the whole cockpit from one node in `CockpitScreen`. Pixel-shift is universal; the brightness breathe/dim `graphicsLayer` is OLED-gated off on the Fire (`BurnInDeviceProfile`). DEEP_IDLE renders `standbyScreen`; SLEEP is app-drawn black + min backlight (Activity stays foreground, instant wake). Corner long-press = park / hidden `burnInTuningPanel`. All params are `BurnInConfig` (self-coercing) and preferences-backed.

**All transports are currently fake** (FakeTransportAdapter, FakeTelemetryRepository). Real BLE/USB/WiFi transport adapters are a future milestone; the GPS location sources, by contrast, have real System/BLE/USB implementations.

**DI is manual** in `ZodiacApplication.kt` (process-lifetime scope) — no Hilt/Dagger. Dependencies are created and wired up directly.

## GPS sourcing

Fire tablets have no built-in GNSS. Architecture is a pluggable `LocationSource` (FAKE / SYSTEM / BLE / USB / NET — NET is planned), parallel to the transport adapter pattern. Fleet target is 8-10 tablets in one vehicle, so the production path is a single shared GPS source on the car's local WiFi rather than per-tablet receivers:

- **Bring-up:** iPhone running GPS2IP broadcasts NMEA over UDP `10110` on the car's existing travel-router WiFi.
- **Production:** Pi Zero 2 W + u-blox USB GNSS + roof antenna, running `gpsd` + UDP NMEA broadcaster on the same WiFi. Pi only does GPS; the travel router keeps the AP/DHCP role.
- Tablet side: `NetworkLocationSource` listens on UDP `10110`, feeds lines into the existing `NmeaParser`, emits `LocationSourceState` like every other source. Same selector chip pattern.

The phone bring-up exists specifically to prove `NetworkLocationSource` end-to-end before any hardware is bought.

## UI Structure

`CockpitScreen` dispatches on the active `CockpitConcept`. Concept A (`CRTVectorScreen`) is the canonical three-rail layout: left rail (system cards), center viewport (the playa map Canvas), right rail (transport selector + status), with a full-screen scanline overlay. Concepts C/D (`ui/concepts/`) are alternate HUD presentations over the same state. Every concept carries the `opsReadout` footer (clock / sun / return-to-camp) in its own palette.

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

- `tasks/open.md` — active work items; `tasks/done.md` — completed; `tasks/someday.md` — backlog
- `design/` — UI concept docs and vehicle wireframe references
- Tone: direct, technical, code over commentary. The user is a hardware/systems engineer.

## How we work

- **`SYNC.md` is append-only.** Anything significant we decide, learn, or build gets a dated entry there. Newest entries on top; never rewrite past entries — supersede with a new one. This is the project's working memory.
- **`README.md` is the public-facing snapshot.** Update it whenever a major feature ships or the architecture changes (new layer, new dependency category, new build step). Don't log progress there — that's what SYNC.md is for.
- **Commit regularly and keep CI green.** Land work in small, runnable commits rather than big batches. Before each commit: run `./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug` (the CI gates) and fix anything red. Never mark a task complete with the build broken.
- **Phased features get phased commits.** When a feature has phases (data → render → integrate), each phase is its own commit and leaves the app runnable.
- **Push to `origin/main` after each phase commit.** GitHub Actions CI runs the same gates on every push, so anything green locally should stay green upstream. Pause and confirm before any destructive remote operation (force-push, branch delete, history rewrite).
