# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Zodiac Control — an Android tablet cockpit UI for a Judge Dredd-inspired vehicle. Built with Kotlin + Jetpack Compose, targeting Amazon Fire tablets in landscape. Currently a v0.1.0 prototype using "Concept B: CRT Vector" aesthetic (80s monochrome vector terminal with scanlines).

Package: `ai.openclaw.zodiaccontrol`

## Build & Test Commands

```bash
./gradlew assembleDebug              # Build debug APK
./gradlew testDebugUnitTest          # Run unit tests
./gradlew detekt                     # Static analysis
./gradlew ktlintCheck                # Code style check
./gradlew ktlintFormat               # Auto-fix formatting
```

CI runs all four checks (ktlint, detekt, unit tests, assembleDebug) on push/PR to `main`.

## Architecture

**Reactive state with Coroutines + Flow.** ViewModel subscribes to repository/gateway flows and exposes a single `StateFlow<CockpitUiState>` to the Compose UI.

**Key layers:**
- `ui/viewmodel/CockpitViewModel` — state orchestration, input validation (heading 0-359, speed 0-160)
- `ui/state/CockpitUiState` — immutable data class, updated via `.copy()`
- `data/VehicleConnectionGateway` — interface for transport selection/connection + command sending
- `data/RoutedVehicleGateway` — routes commands to the currently selected transport adapter
- `data/TelemetryRepository` — streams `Telemetry` via Flow
- `data/transport/TransportAdapter` — pluggable interface (connect/disconnect/send) per transport type (BLE/USB/WiFi)
- `core/model/VehicleCommand` — sealed interface (SetHeading, SetSpeed, EmergencyStop)
- `core/connection/ConnectionModels` — TransportType enum, ConnectionPhase, ConnectionState

**All transports are currently fake** (FakeTransportAdapter, FakeTelemetryRepository). Real BLE/USB/WiFi adapters are a future milestone.

**DI is manual** in `MainActivity.kt` — no Hilt/Dagger. Dependencies are created and wired up directly.

## UI Structure (CRTVectorScreen.kt)

Three-rail layout: left rail (system cards), center viewport (Canvas wireframe + touch input), right rail (transport selector + status). Full-screen scanline overlay. Touch on center viewport maps X→heading, Y→speed.

Color palette: pure black bg, neon green vectors (#00FF66), electric blue (#00BFFF), amber accents (#FFD166).

## Conventions

- **Kotlin 2.0.21**, JDK 17, Compose BOM 2024.11.00, AGP 8.7.3
- Detekt config at `config/detekt/detekt.yml` — MagicNumber, MaxLineLength, and LongMethod rules are disabled
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
