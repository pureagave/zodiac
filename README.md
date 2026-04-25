# Zodiac Control

Android tablet cockpit app for the mutant Zodiac vehicle (Judge Dredd taxi-inspired).

## Current build

- **Concept:** B (CRT Vector)
- **Package:** `ai.openclaw.zodiaccontrol`
- **minSdk:** 30 (Android 11)
- **targetSdk / compileSdk:** 35
- **UI stack:** Jetpack Compose + Material3

## What is implemented

- CRT-style sci-fi dashboard shell
- Left subsystem rail + right status/event rail
- Interactive center wireframe viewport (touch adjusts heading/speed)
- Scanline overlay + perspective grid
- Black Rock City map data layer: 2025 GIS bundled in `app/src/main/assets/brc/2025/`, parsed into a `PlayaMap` domain model with an equirectangular projection anchored on the Golden Spike. UI overlay TBD (Phase 2).
- Baseline quality tooling (ktlint + detekt)
- GitHub Actions CI (lint + static analysis + unit tests + debug assemble)

## Data sources

- **Black Rock City GIS** — streets, plazas, blocks, trash fence, toilets, CPNs from [`burningmantech/innovate-GIS-data`](https://github.com/burningmantech/innovate-GIS-data) (master branch, 2025). Subject to the [Innovate Terms of Service](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/).

## CI

Workflow: `.github/workflows/android-ci.yml`

Runs on pushes/PRs to `main`:
1. `:app:ktlintCheck`
2. `:app:detekt`
3. `:app:testDebugUnitTest`
4. `:app:assembleDebug`

## Run locally

1. Open project in Android Studio (latest stable).
2. Install JDK 17 + Android SDK if missing.
3. Run app module on Fire tablet or emulator.

## Next sprint recommendations

- Replace placeholder wireframe with traced Zodiac geometry layers
- Add theme skinning and animation system
- Add telemetry domain model + fake feed service abstraction
- Introduce Compose UI tests/snapshot tests for critical screens
