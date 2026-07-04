# Zodiac Control

Android tablet cockpit app for the mutant Zodiac vehicle (Judge Dredd taxi-inspired).

For the running decision log (architecture choices, audit follow-ups, hardware lessons), see [`SYNC.md`](SYNC.md). Current open items live in [`tasks/open.md`](tasks/open.md).

## Current build

- **Concepts:** two cockpit aesthetics shipped together, runtime-switchable via the `NAME >` pill in the top-right of every screen. The choice persists across launches. (The original A CRT VECTOR and B PERSPECTIVE GRID were dropped 2026-07-04; the remaining two lost their letter tags.)
  - **RADAR** — *Aliens* '86 M41A motion-tracker sweep scope
  - **MAP** — *Alien* '79 Nostromo instrument-bay gauge wall (green phosphor)
- **Package:** `ai.openclaw.zodiaccontrol`
- **minSdk:** 30 (Android 11)
- **targetSdk / compileSdk:** 35
- **UI stack:** Jetpack Compose + Material3
- **Target displays:** Amazon Fire HD 10 (LCD, perf floor) and Samsung Galaxy Tab (S9 / S9+ / S10) — the **S9+** 12.4" OLED is the main dashboard.

## What is implemented

- Two runtime-switchable cockpit concepts (RADAR / MAP) sharing the same underlying state (heading, speed, transport, GPS source, telemetry, BRC map, ego fix); only presentation differs. Tap the corner pill to cycle.
- BRC map rendered into both concepts with concept-specific palettes (dim/lit sweep scope for RADAR, green blocky tiles for MAP). Pinch-zoom, drag-pan, recenter, and a TOP/TILT mode toggle are wired to every concept's rail.
- Drive-to navigation: pick a destination (HOME = camp at Heiau & 2:15, MAN = Golden Spike, TEMPLE) from the control-strip `DRIVE TO` chips; the ops footer shows bearing + distance + a heading-relative arrow to it.
- Concept C's M41A-style sweep arm illuminates the real BRC map — features brighten as the wedge passes over them rather than as static blips.
- Zoom-gated map labels in concept D: plazas, named arcs and clock-position radials, CPNs, and art (major art like The Temple and The Man come in earlier than self-funded). Street labels are deduplicated across the BRC source's per-segment features so each logical street draws once. Toilets are unlabelled but recoloured BRC porta-potty purple as the type indicator.
- Center viewport: BRC map (trash fence, streets, plazas, toilets, CPNs, art) with track-up rotation following vehicle heading. Two map modes: `TOP` (orthographic, ego at center — default) and `TILT` (~40° pitched 3D with a retro perspective-grid backdrop, ego anchored to the lower third — Battlezone / Out-Run feel). Touch pans (drag), zooms (pinch), and rotates (two-finger twist) the map; heading/speed come from the GPS fix, not from tapping the viewport.
- Art layer: 332 2025 placements bundled from iBurn-Data; majors (Honorarium + ManPavGrant) drawn larger than self-funded and labelled at lower zoom.
- GPS / location source abstraction: pluggable `LocationSource` with four implementations — synthetic `FakeLocationSource` (default, slow circle around the Spike for testing), Android `LocationManager`, Bluetooth Classic SPP NMEA receivers, and USB serial NMEA dongles via [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android). Source selectable at runtime via the right-rail GPS chips. Map viewport centers on the live ego fix.
- Scanline overlay
- OLED burn-in mitigation (`burnin/`) for the S9+ dashboard, wrapping every concept from one node via `burnInScaffold`: whole-UI pixel-shift, a subtle brightness breathe + idle-dim (OLED-only — gated off on the LCD Fire), and an idle state machine (ACTIVE → DIM → CRT "STANDBY" screen → app-drawn black sleep with instant wake-on-touch/GPS-movement/link-change). Manual park (top-left long-press) and a hidden, preferences-backed tuning panel (bottom-left long-press) for on-playa adjustment of every timeout/parameter. All four phases verified on the S9+ OLED.
- Operational-awareness readout (`core/ops/` + `ui/ops/opsReadout`): a first-class, palette-driven footer in each concept (rendered in that concept's own aesthetic, not a shared overlay) showing the BRC clock, today's sunrise/sunset (local NOAA calc, no API), and a live return-to-camp distance + heading-relative arrow to Heiau & 2:15. No network required.
- Full-screen kiosk chrome: draws edge-to-edge and hides the status/nav bars (immersive), required because targetSdk 35 forces edge-to-edge on Android 15+ (the S9+).
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

Runs on pushes/PRs to `main` (via the Gradle wrapper):
1. `:app:ktlintCheck`
2. `:app:detekt`
3. `:app:lintDebug` (Android Lint)
4. `:app:testDebugUnitTest`
5. `:app:assembleDebug`

Release builds (`:app:assembleRelease`) run R8 minify + resource shrink and are signed when `ZODIAC_KEYSTORE_FILE` (+ matching password/alias) is provided via env or gradle properties; otherwise they build unsigned.

## Run locally

1. Open project in Android Studio (latest stable).
2. Install JDK 17 + Android SDK if missing.
3. Run app module on Fire tablet or emulator.

## Next sprint recommendations

- Build `NetworkLocationSource` (NET) — the UDP-10110 shared-WiFi GPS path is the production target (see GPS sourcing plan); add the `INTERNET` permission with it.
- Operational logging (Timber + rolling file) so a misbehaving tablet can be postmortem'd on the playa (`tasks/open.md` M10).
- Compose UI / instrumented tests for the map touch interaction (drag-pan / pinch / twist), which has no automated coverage.
- Validate the R8-shrunk release APK on a real Fire tablet before fleet distribution.
