# Code Audit — 2026-04-26

A read-through of every production source file in `app/src/main/java/ai/openclaw/zodiaccontrol/`, plus tests and resources. Findings below; nothing was changed. No security issues found (no SQL, no crypto, no remote code paths in production yet).

Many items here are already acknowledged in `SYNC.md` "Open follow-ups" sections — those are repeated for completeness so this doc stands on its own.

Severity grades:

- **C — Critical**: can crash, leak, or corrupt state with reasonable user actions on the playa.
- **H — High**: real bug or resource leak, unlikely to crash but will misbehave eventually.
- **M — Medium**: incorrect under edge cases, or causes user confusion.
- **L — Low**: style, dead code, missing tests, deferred decisions.

---

## C — Critical

### C1. `MainActivity.kt` — `MainScope()` is never cancelled

Already in SYNC. The cockpit creates `MainScope()` inside `remember {}` and shares it with `FakeLocationSource` and `RoutedLocationSource`. Activity destruction (e.g., the Android system reclaiming the app under memory pressure, app backgrounded for too long) does not cancel that scope. Subsequent activity creation creates a *new* MainScope and the old one stays alive forever.

Pragmatic note: landscape-only orientation lock + no fragments mostly eliminates Activity recreation in normal use, which is why this hasn't bitten yet. It will bite the moment we add config-change handling, dark mode toggling, or any Activity restart path.

Fix shape: hoist the registry/routed-source construction into the Activity (`override fun onCreate` then `lifecycleScope`), or better — move it into a custom `Application` subclass with a process-lifetime scope.

### C2. `BleLocationSource` — socket leaks on `connect()` exception

`runConnection()` does `socket = sppSocket; sppSocket.connect(); pumpNmea(...)`. If `connect()` throws `IOException`, the catch block sets `_state = Error(...)` but never closes `sppSocket` or clears `socket`. The next `start()` will allocate a new socket and overwrite the field, leaking the old one's file descriptor and any kernel-side BT bookkeeping.

Fix shape: wrap in `try/finally` and close on failure, or set `socket = null` after a successful close in the catch block.

### C3. `UsbLocationSource` — port leak on partial-init exception

Same shape as C2. After `sp.open(connection)` succeeds and we set `port = sp`, if `setParameters` or anything inside `pumpNmea` throws, the catch does not close `sp`. The instance field `port` is now a half-open USB endpoint.

Fix shape: same `try/finally`.

### C4. `SystemLocationSource.start()` — repeat call leaks listener

`requestLocationUpdates(GPS_PROVIDER, _, _, listener, mainLooper)` is called every time `start()` runs. If a caller invokes `start()` twice without an intervening `stop()`, Android registers the listener twice and `onLocationChanged` fires twice per fix. After many cycles, location callbacks pile up and battery drains.

Today, `RoutedLocationSource.select()` does call `stop()` on the previous source before `start()` on the new one, so this is mostly safe — but it's brittle. Any future code path that double-`start()`s the same source crashes the invariant. Add a guard: if `state.value !is Disconnected`, return without re-registering.

### C5. `RoutedLocationSource.select` — no guard against same-type rapid switch

`select(type)` does `if (type == _selected.value) return` only at the *start*. If two `select()` calls land in quick succession with different types and they're allowed to interleave (`viewModelScope.launch { select(SYSTEM) }` followed immediately by `viewModelScope.launch { select(BLE) }`), they can race on `_selected.value` writes. In practice viewModelScope serializes these on Dispatchers.Main, so this is a latent issue rather than active. Worth a `Mutex` if/when the chip taps could ever overlap.

### C6. `NmeaParser` — empty hemisphere field accepted as positive

`parseLatitude(value, hemi)` checks `if (hemi == "S") -signed else signed`. Anything that's not exactly "S" — including `""`, `"X"`, `null`-after-toString — is treated as North. Consumer GPS receivers can emit empty hemi when the fix is degraded. Result: lat reported as +X when it should be -X (a 5,000+ km error). Same for longitude / "W".

Fix shape: validate hemi against `{"N","S"}` (lat) / `{"E","W"}` (lon) and return null on mismatch. Ego marker would then disappear during bad fixes — caller sees Disconnected/Searching, which is correct.

---

## H — High

### H1. `RoutedVehicleGateway` — state read after async send is stale

```kotlin
override suspend fun send(command: VehicleCommand) {
    currentAdapter().send(command)
    _connectionState.update { currentAdapter().state.value }
}
```

The post-send state read is a snapshot at "now," not "right after the send completed." If the adapter's state changes between the send completing and the read happening, the gateway reports stale info. With `FakeTransportAdapter` the send is synchronous so this works; with a real BLE adapter that emits ConnectionState updates from a coroutine, the gateway will sometimes lag by one event.

Fix shape: subscribe to `currentAdapter().state` once and forward via `flatMapLatest + stateIn`, exactly like `RoutedLocationSource` does.

### H2. `FakeTransportAdapter` — exposes intermediate `CONNECTING` state to readers mid-delay

Inside `connect()`, the adapter sets `_state = CONNECTING`, delays 120 ms, then sets `_state = CONNECTED`. Anyone who reads `state.value` during that 120 ms window sees `CONNECTING`. That's actually correct UX behavior (we want to show "connecting…"). Not a bug.

But `RoutedVehicleGateway.connect()` reads `currentAdapter().state.value` *after* `connect()` returns. If the fake's connect completes synchronously to CONNECTED, the gateway sees CONNECTED. Fine. The bug is really H1 — when adapters become async, the flow forwarding pattern is the safe choice.

### H3. `FakeLocationSource.start()` — race-prone re-entry guard

```kotlin
override suspend fun start() {
    if (job?.isActive == true) return
    ...
}
```

Two coroutines passing the check simultaneously will both proceed. On Dispatchers.Main this can't happen, but the source isn't documented as Main-only. In practice, our usage routes through `RoutedLocationSource` which is fine. Fix is the same as C5: a Mutex around `start`/`stop`.

### H4. `CockpitViewModel` — startup flows launched without ordering

`init` fires off seven `viewModelScope.launch { ... }` blocks. The location source's `start()` is one of them; the location source's state collector is another. If the state collector subscribes *before* `start()` runs, the first emission of `Searching` is observed (correct). If `start()` raced ahead and the source emitted both `Searching` and `Active(fix)` before the collector is hot, we'd miss one transition because StateFlow only replays the latest. Acceptable for our use, but worth understanding.

### H5. `CockpitUiState.panEastM` / `panNorthM` — unbounded accumulation

After the drag-pan + RECENTER work, pan offsets are stored in state and accumulated by `panBy`. There's no clamp. A sleep-on-the-finger scenario could drag the camera 50 km off the playa — render still works, but the city is invisible and `RECENTER MAP` is the only escape. A user who doesn't see the chip won't know what to do.

Fix shape: clamp `|panEastM|` and `|panNorthM|` to something like `MAX_PAN_M = 5_000` so the camera can never wander outside the map's authored extent.

### H6. `MainActivity` — permission grants trigger nothing on success

The `LaunchedEffect(Unit)` permission launcher's result callback is `/* …no callback action needed */`. If the user denies, the source emits Error correctly — but if the user grants, the previously-Error source stays in Error until its next `start()` call. Toggling chips will fix it; restart-the-app will fix it. Naively switching from BLE→FAKE→BLE works around this, but it's surprising UX.

Fix shape: on grant, recall `RoutedLocationSource.start()` (or just `select(currentType)`) so the active source picks up the new permission state.

### H7. `AssetsPlayaMapRepository.parseAll()` — no try/catch

`parseAll()` calls `read("art")` etc. that go straight to `assets.open(...)`. If a bundled asset is somehow missing or corrupt, an `IOException` propagates out of `load()`. The VM caller has no try/catch. The result is an uncaught exception in the viewModelScope coroutine, which will be logged and silently swallowed; the map state stays null forever.

Fix shape: wrap `parseAll()` in try/catch, log the exception, transition to a `LoadError` state (new state field) and surface it in the UI.

---

## M — Medium

### M1. Dead types — `VehicleGateway`, `EmergencyStop`, `FakeVehicleGateway` outside tests

- `VehicleGateway.kt` is the older interface; only `VehicleConnectionGateway` (which extends or replaces it) is wired up. Audit the relationship: if `VehicleGateway` is no longer needed, delete it.
- `VehicleCommand.EmergencyStop` has no caller now that `CockpitViewModel.emergencyStop` was removed in commit `bd926af`. Either delete it or wire it back to a UI button.
- `FakeVehicleGateway` is referenced in `CockpitViewModelTest` and `MainActivity` (where it's the only impl provided). Once a real impl exists, the fake should move under `app/src/test/` or `app/src/debug/`.

### M2. `BrcMapRenderer.drawPlayaMap` — per-frame allocation

`drawPolygon` allocates a `Path()` for every polygon, every frame. With 11 street outlines + 256 city blocks + 10 plazas + 45 toilets + 1 fence = 323 polygons, that's 323 GC-heap allocations per frame. At 60fps in TILT mode that's ~20k allocs/sec — Compose's recomposition model usually handles this but it's wasteful.

`List<LatLon>.toScreen(ctx)` also allocates a new ArrayList per polygon. Same story — ~3,000 ArrayList allocs/sec in worst case.

Fix shape: precompute projected pixel paths into per-PlayaMap cache keyed by (heading, zoom, anchorYFrac, panOffset). Invalidate when any of those change. Reuse across frames within the same camera state. With pinch zoom or drag-pan, every frame still re-projects, but `Path` can be `Path()` once and reused with `reset()`.

### M3. `BrcMapRenderer.drawCentroidMarker` — centroid recomputed per frame

Toilets are drawn as centroids of their polygons. `polygon.ring.centroid()` is recomputed every frame. The centroid is purely a function of the polygon's vertex list, which doesn't change.

Fix shape: pre-compute centroids once when the map loads, store on `PolygonRing` (or a separate sidecar). Trivial speedup, real-money savings.

### M4. `drawRetroGrid(viewport)` — 51×2 = 102 projections per frame

The meter-space grid is regenerated every frame. With pan, those endpoints shift, so caching is non-trivial — but the lines spaced at 200 m are deterministic given `(viewport.center, viewport.headingDeg, viewport.pixelsPerMeter, viewport.anchorYFrac)`. Same caching strategy as M2.

For TILT-only rendering this is fine right now (~5% of frame budget on emulator) but will matter on a real Fire tablet.

### M5. `CockpitUiState.egoFix` — recomputed on every state read

```kotlin
val egoFix: GpsFix? get() = (locationState as? LocationSourceState.Active)?.fix
```

Every consumer of `state.egoFix` (the renderer reads it once per frame, the right rail status line reads it once per recomposition, etc.) re-runs the cast. Pretty cheap, but contributes if used in many places.

Fix shape: derive `egoFix` once at state-update time and store it as a regular property.

### M6. Hardcoded colors / no Material3 theme

`themes.xml` exists but the manifest's referenced `Theme.ZodiacControl` is unused at the Compose layer — every color in `CRTVectorScreen.kt` and `BrcMapRenderer.kt` is a literal `Color(0xFFxxxxxx)`. There's no theme switching support, no dark/light awareness, and the design doc proposed three Concepts (A/B/C) with the goal of skinnable themes. Currently re-skinning means editing source.

Fix shape: introduce a `CockpitTheme` data class held in `CompositionLocal`, port the existing palette into it, and pass it from `MainActivity`. Doesn't have to be Material3 — a custom theme object is fine.

### M7. No persisted preferences

Every app launch starts from defaults: `MapMode.TOP`, `tiltDeg = 40`, `pixelsPerMeter = 0.18`, `selectedLocationSource = FAKE`. The user's pinch-zoom level, picked GPS source, last tilt — all forgotten on restart. For a cockpit tablet this is unacceptable on the playa where you re-launch after dust shutdowns.

Fix shape: `androidx.datastore.preferences` keyed by string, written-through on every state change that should persist. Keep it under `data/prefs/CockpitPreferences.kt`.

### M8. Permission rationale missing

`LaunchedEffect(Unit) { permissionLauncher.launch(...) }` fires permission requests cold without a "this is why we need it" rationale. Android's docs and the Material accessibility guidelines both call for shouldShowRequestPermissionRationale checks. Right now if the user denies, they see no explanation; if they deny + dismiss-permanently, they have to dig into Settings.

### M9. Error states truncated to `e.message`

`BleLocationSource` reports `"BT I/O: ${io.message}"`; `UsbLocationSource` does `"USB I/O: ${io.message}"`. `e.message` can be null and is often unhelpful (`"Permission denied"` is fine, `"java.io.IOException"` is not). No stack trace, no chained cause, no categorization.

Fix shape: a `LocationSourceError` enum (`PERMISSION_DENIED`, `ADAPTER_UNAVAILABLE`, `NO_DEVICE_FOUND`, `IO_ERROR`, `UNKNOWN`) plus an optional human detail. UI can render an icon per category.

### M10. No logging at all

There's no `Timber` or `Log.d` anywhere. When something fails on the playa, the only diagnostic is the in-UI status line. We can't postmortem from a remote tablet because nothing is captured. For an event environment, this is a known operational hole.

Fix shape: pull in `timber-android` (~70 KB), tag every source's lifecycle, write to a rolling file under `getExternalFilesDir(...)`. Bonus: a debug screen that displays the last 200 log lines.

### M11. NMEA parser doc misleading

The KDoc on `NmeaParser` says it handles `$GPGGA` and `$GPRMC`. The actual matching is `talkerType.endsWith("GGA") -> ...` so it accepts any talker prefix (`$GLGGA` GLONASS, `$GAGGA` Galileo, `$GBGGA` BeiDou, `$GNGGA` multi-constellation). That's better than the doc claims but the lie hurts when a maintainer thinks they need to add support.

### M12. NMEA parser checksum: tolerates lowercase hex but no padding

Real receivers occasionally emit `*7` (one-digit checksum) when the value is below 0x10. `String.toIntOrNull(16)` on `"7" + sentence[7+1]` would read past the `*` boundary — but the parser explicitly grabs `CHECKSUM_HEX_LEN = 2` chars. So a one-digit checksum gets discarded. That's defensive but rejects valid (if non-conforming) sentences.

### M13. Magic numbers — heading/speed bounds, tilt bounds

`CockpitViewModel.setHeading` clamps to `0..359`. `setSpeed` clamps to `0..160`. `setTiltDeg` uses companion-object constants. The first two should also be in companion-object constants (or a shared `VehicleLimits`) for consistency, and so future vehicle revs don't require touching the VM.

### M14. `RECENTER MAP` chip layout

The `actionChip` we reused for RECENTER was sized for short labels (`CONNECT`, `DISCONNECT`). The label `RECENTER MAP` is 12 chars and runs to the edge of the right rail; on smaller tablets it might wrap or clip. Worth a width hint or a shorter label (`CENTER`).

### M15. Default state values look like placeholders

`headingDeg = 42`, `speedKph = 28`, `thermalC = 60` — these read as "ship's computer" placeholder telemetry from the prototype days. With FakeTelemetry no longer overwriting heading/speed, these initial values stick visibly until the user does anything. Should be 0/0/0 (or load from persisted prefs per M7).

### M16. Fake telemetry tick counter never resets

`FakeTelemetryRepository` uses `var tick = 0` and increments forever. Practical overflow takes 34 years at 500 ms intervals, so it doesn't matter — but it'd matter if someone reused this code elsewhere with a smaller tick interval.

---

## L — Low

### L1. Test coverage gaps

Tests that *should* exist:

- `RoutedVehicleGateway` — only one happy-path test. Missing: select while connected, double-connect, send-after-disconnect.
- `AssetsPlayaMapRepository` — zero tests. The repo is small, the contract is "load + StateFlow"; an instrumented test or at minimum a Robolectric test would catch missing-asset regressions.
- `MapTouchInput.cockpitTouchInput` — pure logic, JVM-testable with a fake `PointerInputScope`. Missing: pinch session reset on finger lift, drag-pan with rotated heading.
- `BleLocationSource` / `UsbLocationSource` / `SystemLocationSource` — Android-only code, but the NMEA pump loop in BLE/USB is testable with a piped stream.
- `CockpitUiState.egoFix` — derived property, no test.
- `wrapHeading()` — pure function, no test.
- The TILT visual stack in `CRTVectorScreen.kt` — no Compose UI tests at all (no `androidx.compose.ui.test` dependency yet).

Total existing JVM test count is 43. With the gaps above, a target of ~60 would feel right.

### L2. NMEA parser coverage holes

- No test for non-GP talker IDs (GL/GA/GB/GN). Per M11, the parser supports them but nothing proves it.
- No test for one-digit checksums (which we currently reject — see M12).
- No test for sentences with empty trailing fields (a real `$GPGGA,,,,,,0,...` no-fix sentence with all fields blank — we reject because fixQuality=0, but the *parsing* path of empties hasn't been exercised).
- No round-trip test (build a sentence with a known GpsFix, parse, expect same fix).

### L3. `PlayaProjection` near-pole behavior

The doc says distortion is sub-meter at 3 km from the origin at BRC's latitude. True. The math degrades at the poles (cos(lat0) → 0, division blows up in `unproject`). Since BRC will never move to 90°N, this is fine in practice. A single-line comment in the file explaining the assumption would save someone time later.

### L4. `PlayaProjection.distanceMeters` does double projection

`distanceMeters(a, b)` projects both points and computes Euclidean distance on the playa-meter plane. That's fine for short distances at BRC. For longer distances on Earth, proper Haversine would be more accurate. Worth noting in the KDoc.

### L5. `PlayaViewport` has no validation of `anchorYFrac`

Accepts any Double. `0.0` puts the camera origin at the very top, `1.0` at the very bottom, but `2.5` would put it off-canvas with no error. Add `require(anchorYFrac in 0.0..1.0)` or clamp.

### L6. No KDoc on most public types

`CockpitUiState`, `CockpitViewModel`, `LocationSourceState`, `MapMode` — no class-level KDoc. Each is small enough to grok by reading, but a one-paragraph "this represents X, owned by Y, mutated by Z" header per file would help onboarding.

### L7. `MainActivity` Composable is a god-function

`zodiacApp()` constructs the registry, the routed source, the view model factory, the permission launcher, and the screen. About 50 LoC, growing. Worth extracting a `rememberCockpitDependencies()` helper function. Currently kept inline because of the function-count ceiling on `CRTVectorScreen.kt`; a separate `MainActivity.kt` is the natural home for the helper.

### L8. `CRTVectorScreen.kt` is 533 lines

11/11 functions in detekt's per-file limit. Every new feature has to choose between extracting a new file or playing tetris with the existing functions. Three reasonable extractions:

- `RightRailControls.kt` — `rightRail`, `transportChip`, `actionChip`, `locationLine`, the `RightRailCallbacks` / `ChipControls` data classes.
- `LeftRailControls.kt` — `leftRail`, `MAP_TOGGLE_IDX`.
- `TopHeader.kt` — `topHeader`, `headerText`.

That'd leave `CRTVectorScreen.kt` with `crtVectorScreen`, `centerViewport`, `scanLineOverlay`, plus state constants. Plenty of room.

### L9. `tasks/open.md` is stale

Last entry says "Compile + run tests locally on each significant change *(blocked in this runtime: no Java/Gradle/Android SDK installed)*". That was true when the repo was first cloned; we resolved it on day one. The task list should reflect the four-phase BRC map work that actually happened (Phase 1–4, plus the TILT polish, plus everything tonight). Move completed items to `done.md`.

### L10. `BleLocationSource` device-name regex is hardcoded

`DEFAULT_NAME_PATTERN = Regex(".*(?i:GPS|Garmin|Bad ?Elf|XGPS|Holux|Qstarz|GNSS).*")`. Works for known consumer dongles but excludes anything with a custom name. Should be configurable via DataStore preference + a "Pick device" picker UI in Phase 4-or-later.

### L11. `usb_gps_device_filter.xml` covers 5 chip families

CH340, FTDI FT232R, CP210x, PL2303, u-blox. Real consumer dongles also appear with FTDI FT232H, CP2105, CH343, MCP2200, and a few others. Common-enough adds:

- FTDI FT232H (vendor 1027, product 24596)
- WCH CH343 (vendor 6790, product 21795)
- Silicon Labs CP2104 (vendor 4292, product 60001)
- MediaTek MT3329-based receivers

Low priority but worth having a checklist when supporting more hardware.

### L12. README is mostly current but missing the audit trail of decisions

Each Phase has a SYNC entry, but the README doesn't reference SYNC. A reader landing on the README sees "what is implemented" + "data sources" but not "see SYNC.md for the decision log". One-line cross-link would pay back fast.

### L13. No `LICENSE` file at the repo root

The data sources have licenses (Innovate ToS, iBurn MIT, usb-serial MIT). The Zodiac Control source itself has none. For the open repo on github.com/pureagave, this is technically "all rights reserved" by default — not what an open-source contributor would expect. Decide whether to add Apache-2.0, MIT, or explicitly mark closed-source.

---

## Architectural notes

These aren't bugs, they're shape-of-the-codebase observations to weigh against future direction.

### A1. The "gateway / adapter / registry" pattern is already replicated three times

- `VehicleConnectionGateway` + `RoutedVehicleGateway` + `TransportAdapter` + `TransportRegistry`
- `LocationSource` + `RoutedLocationSource` + `LocationSourceRegistry`
- `(future) command source` + (future) routed command source + … will repeat

It's a clean shape but it's also rote. A generic `Routed<T>` over `Map<Type, T>` plus a serial Mutex would handle all three. Worth doing once you're sure the pattern stops at three.

### A2. The renderer and the projection are coupled through `PlayaViewport`

`PlayaViewport` is a value object — every render builds a new one. That's fine for correctness but makes "the camera state" implicit (scattered across `pixelsPerMeter`, `state.headingDeg`, `state.panEastM/NorthM`, `state.tiltDeg`, `state.mapMode`). A `MapCameraState` data class held in `CockpitUiState` would make the camera one named thing instead of five floating fields.

### A3. There's no separation between "playa world" and "vehicle UI"

Every cockpit feature today reads `state.playaMap` and projects through one shared `PlayaViewport`. If we add a second view (e.g., the night instrument display, the friend-tracker overlay, the recorded-track replay), each would need its own viewport. Worth refactoring before adding the second consumer: a `PlayaScene` that owns `(map, projection, viewport, ego, panOffset)` and is provided via `CompositionLocal`.

### A4. `viewModelScope` is doing a lot of work

`CockpitViewModel.init` launches 7 coroutines. Each of them is a hot subscription that lives for the VM's lifetime. There's no `Application`-scoped scope for things that should outlast a VM. The location source registry is one of those things — destroying and rebuilding it on Activity recreation is wasteful. See C1 for the path.

### A5. State is denormalized

`CockpitUiState` has 14 properties + 1 derived. Every state mutation does `_uiState.update { it.copy(...) }`, which performs a structural copy of all 14 fields. With 60 fps state updates (drag-pan, telemetry tick), that's ~1k allocations/sec for the state record alone. Compose's diffing is good but the pressure is real on lower-end Fire tablets.

Fix shape: split into multiple smaller state records exposed as separate StateFlows on the VM (`mapState: StateFlow<MapState>`, `connectionState: StateFlow<ConnectionState>`, `egoState: StateFlow<EgoState>`). Compose can subscribe to just what each section needs.

---

## Quick wins

If we want a "tighten everything up" commit before adding features, here's the order I'd ship it:

1. **C1** — move scope ownership out of the Composable. Single largest correctness improvement.
2. **C6** — NMEA hemisphere validation. One-line change with real correctness impact when GPS is bad.
3. **C2 / C3** — `try/finally` socket close on Ble/Usb error paths. Two small edits.
4. **M5** — cache `egoFix` in state instead of computing per-read. Trivial.
5. **M3** — pre-compute centroids on map load. Trivial.
6. **L1** (partial) — add `wrapHeading` test, `RoutedVehicleGateway` switch test, `MapTouchInput` pinch-reset test.
7. **M7** — DataStore for last-selected source / last tilt / last zoom / last map mode. ~80 LoC, big UX win.

Single working session, all of them. None require new architecture; they're rough edges on existing surfaces.

---

## What's NOT in this audit

- Performance under real-tablet workloads (no profiling done; the suspect spots in M2/M3/M4 are educated guesses).
- Cross-tablet behavior (no multi-device scenario was tested; that's `architecture.md`'s job).
- Long-term battery drain.
- Dust / heat / vibration robustness — physical-world hazards specific to the playa.
- Anything in the Pi-side scripts that don't exist yet (covered in `architecture.md`).
