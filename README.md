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

## GPS sourcing plan (8-10 tablets, single vehicle)

Fire tablets have no built-in GNSS, and per-tablet receivers don't scale (Bluetooth GPS pucks cap at ~5 simultaneous clients). Plan is a **single shared GPS source on the car's local WiFi**, with every tablet reading the same UDP NMEA stream:

- **Bring-up:** spare iPhone running [GPS2IP](https://capsicumdreams.com/iphone/gps2ip/) joins the car's travel-router WiFi and broadcasts NMEA over UDP port `10110` (de facto NMEA-over-IP). The paid version's background-location mode keeps it broadcasting with the screen locked. Validates the tablet-side `NetworkLocationSource` end-to-end before any hardware purchase.
- **Production:** Raspberry Pi Zero 2 W + USB u-blox GNSS receiver (NEO-M9N preferred; BU-353-S4 acceptable) with a roof-mounted active antenna, running `gpsd` plus a small UDP NMEA broadcaster on the same WiFi. The Pi only does GPS — the existing travel router stays as the AP/DHCP source (Pi Zero 2 W's 2.4 GHz-only radio is not viable as the fleet AP for 8-10 clients).

Tablets gain a 5th `LocationSource` implementation (`NET`) alongside FAKE / SYSTEM / BLE / USB, listening on UDP `10110` and feeding lines into the existing `NmeaParser`. Same `state: StateFlow<LocationSourceState>` contract as the other sources, same right-rail selector chip pattern.

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
