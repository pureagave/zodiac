# Done

Historical task list. Detail and decision rationale lives in `SYNC.md` and the git log; this file just records what shipped.

## Initial setup
- [x] Confirm app scope (MVP features, target devices, Android min/target SDK, online/offline constraints)
- [x] Create Android project skeleton (Kotlin, Gradle, module layout, CI-ready structure)
- [x] Add baseline quality tooling (ktlint/detekt, unit/instrumentation test setup)
- [x] Implement first vertical slice feature (Concept B CRT dashboard prototype)
- [x] Configure GitHub remote and push initial repository state
- [x] Get JDK 17 + Android SDK installed locally so the CI gates run cleanly on every commit

## OLED burn-in mitigation (2026-06-20)
- [x] `BurnInMitigationManager` idle state machine (ACTIVE/DIM/DEEP_IDLE/SLEEP) on an injectable clock; activity = touch / real GPS movement / link change; unit-tested
- [x] `burnInScaffold` integration — pixel-shift + OLED-gated brightness breathe/dim from one node, non-consuming touch observer, per-phase window backlight + KEEP_SCREEN_ON
- [x] CRT `standbyScreen` for deep idle; app-drawn black SLEEP with instant wake
- [x] Manual park (top-left long-press) + hidden, preferences-backed tuning panel (bottom-left long-press)
- [x] `BurnInConfig` self-coercion + DataStore persistence; verified end-to-end on the Fire HD 10
- [x] Full four-phase burn-in visual layer verified on the S9+ OLED (2026-07-04): DIM 30% clean (no Adreno ModulateAlpha artifacts), CRT STANDBY, black SLEEP

## Operational awareness (2026-07-04)
- [x] `core/ops/Camp` (provisional Heiau & 2:15), `campGuidance` (ego→camp bearing/distance), `SunTimes` (local NOAA sunrise/sunset, no API) — unit-tested
- [x] Ambient ops strip (`ui/ops/OpsStrip`): BRC clock + sunrise/sunset + return-to-camp over every concept; opaque HUD bar + reserved band (no chrome overlap)
- [x] Edge-to-edge immersive kiosk chrome in `MainActivity` (targetSdk 35 / Android 15+); strip is `safeContent`-inset-aware. Verified on the S9+.
- [x] Ops readout reworked into a first-class per-concept themed footer (`ui/ops/opsReadout`), replacing the shared overlay; verified across A/C/D on the S9+.

## Concept changes (2026-07-04)
- [x] Dropped Concept B (PERSPECTIVE); tags A/C/D kept stable, cycle A→C→D→A; tests updated
- [x] Recoloured Concept D (Instrument Bay) from amber/orange to green phosphor (theme + map palette)

## Cockpit UI
- [x] Three-rail CRT layout (left subsystem rail, center viewport, right status rail)
- [x] Heading / speed touch input on the center viewport (later replaced — see drag-pan)
- [x] HDG SET and SPD SET debug chips
- [x] RECENTER MAP chip and lifted pan state into the VM
- [x] One-finger drag-pan, two-finger pinch-zoom on the map viewport
- [x] MAP MODE state + SYS-3 toggle (TOP / TILT)
- [x] 3D tilt mode with adjustable tilt angle and retro perspective grid in playa-meter space

## Black Rock City map (Phases 1–4)
- [x] Phase 1 — bundle Innovate GIS data, type into `PlayaMap`, add `PlayaProjection`
- [x] Phase 2 — render the map in the center viewport
- [x] Phase 3 — add iBurn-Data art layer
- [x] Phase 4a — `LocationSource` abstraction + `FakeLocationSource`
- [x] Phase 4b — NMEA 0183 parser
- [x] Phase 4c — `SystemLocationSource` (Android `LocationManager`)
- [x] Phase 4d — `BleLocationSource` (Bluetooth Classic SPP NMEA)
- [x] Phase 4e — `UsbLocationSource` (`usb-serial-for-android`)
- [x] Phase 4f — GPS source selector chips + permission flow

## Audit batch (audit.md, 2026-04-26)
All Critical and High items closed:
- [x] C1 — hoist DI graph into `ZodiacApplication` (no more leaked `MainScope`)
- [x] C2 / C3 — `try/finally` socket close on Ble/Usb connect failure
- [x] C4 — guard `SystemLocationSource` against double-`start()`
- [x] C5 / H3 — `RoutedLocationSource` mutex serializes lifecycle
- [x] C6 — strict NMEA hemisphere validation
- [x] H1 — `RoutedVehicleGateway.connectionState` derived via `flatMapLatest`
- [x] H4 — single ordered `init` launch in the VM
- [x] H5 — clamp pan offset to `MAX_PAN_M`
- [x] H6 — restart location source on permission grant
- [x] H7 — surface map load failures via `MapLoadResult`

Medium / Low sweep:
- [x] M1 — delete dead types (`VehicleGateway`, `EmergencyStop`); move `FakeVehicleGateway` to test sources
- [x] M3 — pre-compute polygon centroids at parse time
- [x] M5 — cache `egoFix` as a stored property
- [x] M7 — DataStore for last-selected source / tilt / zoom / map mode
- [x] M11 / M12 — NMEA: doc the multi-talker support; accept 1-digit checksums
- [x] M13 — promote heading / speed bounds to companion-object constants
- [x] M15 — zero placeholder defaults (heading / speed / thermal)
- [x] L1 (partial) — tests for `wrapHeading` and `RoutedVehicleGateway` switch / double-connect / send-after-disconnect
- [x] L5 — `require(anchorYFrac in 0.0..1.0)` on `PlayaViewport`
- [x] M2 — cache projected geometry per camera state, plus draw-call batching (consolidated `Path`s + `drawPoints` for marker layers); fixed the Fire HD 10 stutter on Concept C's lit-wedge sweep
- [x] M4 — cache the meter-space retro grid as one `Path` per viewport; per-frame cost drops from 102 `drawLine` calls to 1 `drawPath`
- [x] Render perf round 2: scanline overlay cached as a single `Path` (CRT + Tracker), static map data pre-partitioned at load (`majorArt` / `minorArt` / `*LabelSeeds`), projection fused into path build (no intermediate `List<Offset>` per polyline)
- [x] Render perf round 3: DoubleArray polyline storage + inline primitive projection (zero per-vertex allocations on the hot path), binary cache for parsed `PlayaMap` (skips ~1 MB JSON parse on warm starts), `MapUiInputs` slice + `derivedStateOf` so map subtree skips recomposition on thermal / link / connection updates
- [x] Pre-laid-out `TextLayoutResult`s for labels: parallel `LabelLayouts` cache keyed on `(map, density)`, palette colour applied at draw time so concept switches don't invalidate; `drawProjectedMap` bundles the gated label pass via optional params

## Robustness audit (2026-06-14) — see SYNC entry + commits `c5d652a` `5171eb2` `81473b1`
- [x] Tier 1 — `collectAllVertices` odd-length crash; NMEA lat/lon/minutes range + course normalization; Usb/Ble pump loops observe the job scope (so `stop()` halts them); binary-cache count bounds-check (no OOM/NegativeArraySize)
- [x] Tier 2 — broaden Ble/Usb/System setup catches → `Error` state; GeoJSON per-feature skip; atomic cache write + `Mutex`-guarded `load()`; `PlayaNavigator` at-origin → `Unknown`; `commandError` surfaced on failed sends; `viewRotationDeg` normalized
- [x] Tier 3 — ~21 unit tests for the above; removed `Example*` boilerplate tests
- [x] Tier 4 — `ACCESS_COARSE_LOCATION` (real lint error); `lintDebug` in CI via `./gradlew`; R8 release (36MB→2.4MB) + proguard keep rules; opt-in release signing; CLAUDE.md/README re-synced to reality
