# Done

Historical task list. Detail and decision rationale lives in `SYNC.md` and the git log; this file just records what shipped.

## Initial setup
- [x] Confirm app scope (MVP features, target devices, Android min/target SDK, online/offline constraints)
- [x] Create Android project skeleton (Kotlin, Gradle, module layout, CI-ready structure)
- [x] Add baseline quality tooling (ktlint/detekt, unit/instrumentation test setup)
- [x] Implement first vertical slice feature (Concept B CRT dashboard prototype)
- [x] Configure GitHub remote and push initial repository state
- [x] Get JDK 17 + Android SDK installed locally so the CI gates run cleanly on every commit

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
