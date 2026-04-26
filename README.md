# Zodiac Control

Android tablet cockpit app for the mutant Zodiac vehicle (Judge Dredd taxi-inspired).

For the running decision log (architecture choices, audit follow-ups, hardware lessons), see [`SYNC.md`](SYNC.md). Current open items live in [`tasks/open.md`](tasks/open.md).

## Current build

- **Concept:** B (CRT Vector)
- **Package:** `ai.openclaw.zodiaccontrol`
- **minSdk:** 30 (Android 11)
- **targetSdk / compileSdk:** 35
- **UI stack:** Jetpack Compose + Material3

## What is implemented

- CRT-style sci-fi dashboard shell
- Left subsystem rail + right status/event rail
- Center viewport: BRC map (trash fence, streets, plazas, toilets, CPNs, art) with track-up rotation following vehicle heading. Two map modes selectable via the SYS-3 button in the left rail: `TOP` (orthographic, ego at center — default) and `TILT` (~40° pitched 3D with a retro perspective-grid backdrop, ego anchored to the lower third — Battlezone / Out-Run feel). Touch on the viewport sets heading (X) and speed (Y); pinch zooms.
- Art layer: 332 2025 placements bundled from iBurn-Data; majors (Honorarium + ManPavGrant) drawn larger than self-funded.
- GPS / location source abstraction: pluggable `LocationSource` with four implementations — synthetic `FakeLocationSource` (default, slow circle around the Spike for testing), Android `LocationManager`, Bluetooth Classic SPP NMEA receivers, and USB serial NMEA dongles via [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android). Source selectable at runtime via the right-rail GPS chips. Map viewport centers on the live ego fix.
- Scanline overlay
- Black Rock City map data layer: 2025 GIS bundled in `app/src/main/assets/brc/2025/`, parsed into a typed `PlayaMap` and projected via `PlayaProjection` (equirectangular, anchored on the Golden Spike) and `PlayaViewport` (track-up, configurable zoom).
- Persisted preferences via `androidx.datastore.preferences` — last-picked GPS source, map mode, tilt angle, and zoom survive a restart.
- Baseline quality tooling (ktlint + detekt)
- GitHub Actions CI (lint + static analysis + unit tests + debug assemble)

## Data sources

- **Black Rock City GIS** — streets, plazas, blocks, trash fence, toilets, CPNs from [`burningmantech/innovate-GIS-data`](https://github.com/burningmantech/innovate-GIS-data) (master branch, 2025). Subject to the [Innovate Terms of Service](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/).
- **Art locations** — 2025 placements from [`iBurnApp/iBurn-Data`](https://github.com/iBurnApp/iBurn-Data) (MIT). The bundled `art.geojson` is a stripped subset (name + program + Point) of `data/2025/APIData/APIData.bundle/art.json`.

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
