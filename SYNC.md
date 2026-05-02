# SYNC.md

Append-only log of significant decisions, lessons, and changes for the Zodiac Control project.

Newest entries on top. Each entry: ISO date, short title, body. Don't rewrite history — if something later turns out wrong, add a new entry that supersedes it.

---

## 2026-05-02 — Render perf: projected-map cache + draw-call batching (audit M2)

Concept C's rotating sweep was visibly stuttering on the Fire HD 10 — the M41A look re-renders the entire BRC map twice per frame (dim base + lit-clipped wedge), and the per-frame draw load was the bottleneck. Two-pass fix:

1. **Projection cache.** New `ProjectedMap` type and `PlayaMap.project(projection, viewport)` that walks every feature once and pre-builds screen-space geometry. Composables in `playaMapPanel` and `centerViewport` use `Modifier.onSizeChanged` + `remember(playaMap, viewport)` to memoise the projection — within a single GPS tick (every 500 ms), all 60 fps frames reuse the same cached result. Cache invalidates on GPS update, pan, pinch, rotate, or canvas resize.
2. **Draw-call batching.** Same-style geometry collapses into one Skia call:
   - All ~600 streets → one `drawPath` with `Stroke(cap = Round, join = Round)`. Previously: ~4000 `drawLine` calls per pass.
   - Street outlines, plazas → one `drawPath` each (consolidated subpaths).
   - Toilets / CPNs / minor art → one `drawPoints` each (filled circles via `PointMode.Points` + round cap).
   - Major art (hollow stroke) stays as ~50 individual `drawCircle` calls — `drawPoints` can't render hollow.

Per-pass call count: ~4400 → ~60. Concept C's double pass: ~9000 → ~120 calls per frame.

Renderer split: `drawProjectedMap(projected, palette, pixelsPerMeter)` is the new pure-raster entry point. `drawPlayaMap` retained as a one-shot wrapper that projects then draws — handy for tests / one-off renders. Old internal helpers (`drawArtMarker`, `drawStreet`, `drawPolygon`, `drawCentroidPoi`, `drawPoi`, `RenderCtx`, `toScreen` extension) deleted.

Closes audit task **M2** ("cache projected `Path` per `(heading, zoom, anchorYFrac, panOffset)`"). Verified visibly smooth on the user's Fire HD 10 13th gen.

CI gates green: ktlint, detekt, testDebugUnitTest, assembleDebug.

---

## 2026-05-01 — Drive-mode fake GPS: chips steer the synthetic ego

Reworked `FakeLocationSource` from a slow-circle / parked-offset hybrid into a proper kinematic sim. Every 500 ms tick:

```
posEastM  += sin(headingDeg) · (speedKph / 3.6) · dt
posNorthM += cos(headingDeg) · (speedKph / 3.6) · dt
```

…and emits a `GpsFix` with the new position + the configured heading + speed. New driver-input methods: `setHeading(deg)`, `setSpeed(kph)`, plus existing `nudgeManualOffset` (teleport) and `resetManualOffset` (zero everything).

VM `setHeading`/`setSpeed` chip handlers now also call into the fake source so the rail acts as a steering wheel + throttle. State `headingDeg` and `speedKph` get re-folded from each subsequent `GpsFix` (the location collector pulls `fix.headingDeg?.toInt()` into state) — i.e. heading is a *physical* property of the ego, sourced from GPS. On real hardware (BLE/USB/SYSTEM) this same path picks up real motion; on the synthetic source the chip→fake→fix round-trip is what makes the cockpit feel like driving.

Initial state is parked at the Spike, heading 0, speed 0 — nothing moves until the user taps a speed chip. The slow-circle default is gone; the sim is more useful for actual debugging.

`FakeLocationSource` constructor lost `pathRadiusMeters` / `periodSeconds` params. Tests rewritten around the new behaviour.

---

## 2026-05-01 — Follow modes: TRACK_UP / FREE + recenter button + auto-revert

The cockpit now models its camera state explicitly. New `FollowMode` enum:

- **TRACK_UP** (default): camera follows the live GPS fix; display rotation tracks the ego's heading; ego marker stays at viewport anchor pointing up.
- **FREE**: camera holds an *absolute* world position (`CockpitUiState.cameraOverride: PlayaPoint?`); display rotation is independent (`viewRotationDeg: Double`); GPS updates slide the ego marker on screen but the map underneath stays put. Standard map-app feel.

State changes:
- Dropped `panEastM`/`panNorthM` (they were "offset from ego" — wrong model for FREE).
- Added `cameraOverride`, `followMode`, `viewRotationDeg`.
- `MAX_PAN_M` → `MAX_CAMERA_OFFSET_M`; clamps |camera − ego| in FREE.

Behaviour:
- One-finger pan: switches to FREE, parks `cameraOverride = ego + Δ`.
- Pinch-zoom: stays in current mode, resets the auto-revert timer when in FREE.
- Two-finger rotate: spins `viewRotationDeg` only — does *not* touch `headingDeg` (heading is a physical property of the ego, the user is rotating the *display*). Ego marker rotates by `(headingDeg − viewRotationDeg)` so it keeps pointing in the real direction of motion on the rotated display.
- Recenter button (ego-shaped, bottom-right of every concept, themed per palette): clears `cameraOverride`, syncs `viewRotationDeg = headingDeg`, returns to TRACK_UP, cancels the timer.
- 60-second auto-revert: any pan / pinch / rotate (re)starts a `viewModelScope.launch { delay(AUTO_RECENTER_MS); recenterPan() }`. If the user is idle past the window the cockpit snaps back on its own.

`MapTouchInput` gained two-finger angle tracking (`atan2` between fingers) and an `onRotate(deltaDeg)` callback. Rotation deadzone of 0.05° suppresses jitter from steady two-finger holds.

---

## 2026-04-30 — Big visible ego + fake-GPS nudge chips for debug

Two debugging quality-of-life additions:

- Ego marker bumped from 14 px → 28 px, filled body + 3 px white outline + halo ring at 1.7× radius. The halo's centre is the actual GPS fix point — i.e. the dead centre of the circle is "you are here." Both triangle and hex variants got the treatment.
- Ego-marker draws at the projected GPS-fix position rather than a fixed viewport anchor — fixes the "drag the map and the ego stays in the same place" bug. Ego now slides with the map when panned. Fixed in both `playaMapPanel` (B / C / D) and `centerViewport` (A); aligned the TILT-mode `MAP_ANCHOR_TILT` to `EGO_ANCHOR_TILT` (both 0.78) so the arcade lower-third framing is preserved.
- `FakeLocationSource` exposes a manual offset; new `> FAKE GPS NUDGE` row on the rail (visible only when source is FAKE) gives `N+100`, `S+100`, `E+100`, `W+100` chips plus `GPS RESET`. Useful for jumping the ego to specific positions to verify on-street snap, nav-cue radial detection, etc.

---

## 2026-04-30 — Nav cue bar (phase 2: UI)

Wired the `NavigationCue` from PlayaNavigator into the cockpit. New `NavCueBar` Composable drops into the top of every concept, themed per palette. VM owns a private `cityModel: PlayaCityModel?` (built once when the BRC map loads) and a `recomputeNavCue()` helper called from the heading and location collectors so the cue stays in sync without per-frame work in the renderer. Cue formats:

- `→ 4:42  1240m` — off-street, heading toward city.
- `← -10:30  3.6km` — deep playa, heading outward (backward ray cast against the trash fence).
- `4:30 → ESPLANADE` — on a radial, inbound.
- `4:30 ← ATWOOD` — on a radial, outbound (last-passed arc).
- `ATWOOD 4:42` — on a named arc, clock ticks as you move along it.
- `—` — no fix yet.

---

## 2026-04-29 — BRC map integration into B/C/D + zoom-gated labels

Pulled the real Black Rock City map (streets, plazas, art, CPNs, toilets, fence) out of concept A and into B/C/D as well, with concept-specific palettes, GPS tracking, pinch-zoom, drag-pan, and a TOP/TILT toggle on the rail. Concept A is intentionally untouched per the directive to leave it as-is.

**Renderer changes (`BrcMapRenderer.kt`):**
- `drawPlayaMap` now takes a `MapPalette` (default = legacy A colours, so A's call site is identity). Each concept supplies its own palette, including a `pointStyle` (DOT for the canonical look, BLOCK for D's chunky orange-on-black tile aesthetic).
- Ego markers split out to `EgoMarkers.kt` — adds `drawHexEgoMarker` (concept B's faceted polyhedron) alongside the original triangle. Both accept a colour so the marker reads in the host concept's palette.

**Shared map panel (`ui/concepts/PlayaMapPanel.kt`):**
- New `playaMapPanel` Composable bundles touch input, projection, viewport build, optional tilt graphicsLayer, optional retro-grid backdrop, ego overlay, and an optional `SweepOverlay` (concept C). Configuration via a `PlayaMapPanelStyle` data class — keeps the call site under detekt's parameter cap.
- Concept C's sweep illuminates the map by re-drawing it inside the wedge clip with a brighter `litPalette` — proper M41A "ping over a real city" effect rather than static blips.
- B honours the TOP/TILT toggle; C is always top-down (canonical M41A); D map tile honours TOP/TILT for the centre tile only.

**Labels (`ui/playamap/MapLabels.kt`):**
- `MapPalette.labelsEnabled` flag (off by default → A unchanged) wires a label pass into `drawPlayaMap`. Zoom-gated reveal: plazas at ≥0.20 px/m, major art (Honorarium/ManPavGrant — incl. The Temple, The Man) at ≥0.30, streets at ≥0.45, CPNs at ≥0.65, minor self-funded art at ≥1.10.
- BRC source data has each block of a logical street as a separate `LineString` feature (599 segments → ~30 unique names). Naive midpoint-per-segment stamps "4:30" at every intersection; the implementation now groups by name, picks the source point closest to the group's centroid, and draws one label per logical street (radials land on a middle block, arcs sit near the top of the curve).
- Toilets stay marker-only, recoloured BRC porta-potty purple (`#B266FF`) in B and D — the source has no per-bank name (every toilet feature is just `ref: "toilet"`), so colour carries the meaning.
- Labels off in C: the M41A look is intentionally low-info / blip-driven, and bright text would fight the dim-base / lit-wedge contrast.

**Shared control strip (`ui/concepts/ConceptControls.kt`):**
- Added `ZOOM-` / `ZOOM+` chips and a `TOP` / `TILT` mode toggle (suppressible per concept — C hides it). `ZOOM±` step is 1.4×. The same chip set is now reachable from B/C/D rails so the user can drive any concept without pinch-zoom on a cramped tile (D's map is small).

**Untouched on purpose:**
- Concept A. Same colours, same TOP/TILT toggle behaviour, same controls.
- Toilet colour in A (still electric blue). One-line change if we ever want to apply BRC purple universally.

CI gates green: ktlint, detekt, testDebugUnitTest, assembleDebug.

Open follow-ups:
- Toilet labels never (no per-bank name in source); could synthesize from nearest CPN/intersection if the team wants them.
- Street label rotation along the radial / arc tangent — current labels are horizontal. Looks fine at moderate zoom; would read better still with rotation.
- Label collision avoidance: dedupe handles the worst case but at very high zoom art + street labels can overlap. A drawn-rect collision check would help.
- Concept C lit-wedge: currently a single bright wedge at the leading edge. Adding a fading trail would make features stay lit briefly after the sweep passes, more authentic to the M41A persistence-of-vision look.

---

## 2026-04-29 — Four cockpit concepts (A/B/C/D) + runtime switcher

Took five Alien-franchise / 80s-vector mockups (`design/mockups/map_concept_*.html`) down to three picks plus the existing CRT Vector. Now wired all four into the app as live screens with a tap-to-cycle pill in the top-right corner of every concept.

- **A — CRT VECTOR** (existing): unchanged; just added the cycle pill.
- **B — PERSPECTIVE GRID** (Lukas Uhlitz / Europa lift): green palette, receding floor grid that creeps forward when speed > 0, faceted polyhedron vehicle that pivots with heading, left-side altitude/distance ladder, right-side control rail.
- **C — MOTION TRACKER** (Aliens '86 M41A): concentric range rings, rotating sweep arm, forward detection cone aligned to heading, big BEARING/SPEED/CONTACTS readouts, scanline overlay. Contacts are static decorative blips for now — real BRC POI proximity is a follow-up.
- **D — INSTRUMENT BAY** (Nostromo dense gauge wall): tile grid with map at center plus heading dial, half-arc speed gauge, throttle waveform, two cell bars, hazard chevron footer.

Architecture:
- New `CockpitConcept` enum (A/B/C/D) with `next()` for cycling.
- `CockpitUiState.concept` + `CockpitViewModel.cycleConcept()` + `CockpitPreferences.setConcept()` so the choice persists across launches.
- New top-level `cockpitScreen()` dispatcher in `CockpitScreen.kt` swapped in for `crtVectorScreen` from `MainActivity`.
- Shared `ui/concepts/` package: `ConceptTheme` (palette per concept), `conceptSwitcher` (the pill), `conceptControlStrip` (transport / GPS / heading / speed / recenter chips, themed). Each new screen is ~200-300 LoC because the chip layout is shared.

`thresholdInClasses` in `detekt.yml` bumped 12 → 13 to fit `cycleConcept`. Cycle pill is a normal `clickable` Box so it works in any concept's chrome without extra wiring.

CI gates green: ktlint, detekt, testDebugUnitTest, assembleDebug.

Mockups 1 (Deorbital Globe) and 2 (Echo Probe) are still in `design/mockups/` for reference but are not built into the app — kept in case we want to revisit.

---

## 2026-04-26 — GPS fleet sourcing plan (8-10 tablets, one vehicle)

Decided how to feed GPS to the fleet without per-tablet receivers.

**Ruled out:**
- Per-tablet BLE pairing (Garmin GLO, Bad Elf, Dual XGPS150). Most BT GPS pucks cap at 1-2 active connections; even the multi-client units (Dual XGPS160, Bad Elf Pro+) cap at 5 BT clients — and pairing 10 tablets is a maintenance nightmare. Also: BT inside a metal vehicle with 10 tablets is RF-noisy.
- "One tablet broadcasts from internal GPS." Fire tablets have no GPS, so the host tablet would need external hardware anyway — might as well skip the tablet middleman.
- Dual XGPS160 WiFi mode. **Correction to earlier guess: the XGPS160 does NOT have a WiFi mode — it's BT-only with a 5-device cap.** The off-the-shelf WiFi-NMEA category is mostly marine (Quark-Elec QK-A027 ~$130 is the closest fit if going off-the-shelf).

**Plan:**
1. **Bring-up (weekend):** spare iPhone running GPS2IP (paid ~$8 — needed for background-location mode that keeps it broadcasting with screen locked) joins the car's existing travel-router WiFi. UDP broadcast on port 10110 (de facto NMEA-over-IP). Build a new `NetworkLocationSource` that listens on UDP 10110 and feeds lines into the existing `NmeaParser`. Add a `[NET]` chip in the right-rail GPS selector. iOS works the same as Android for this — same app, same protocol. Same caveats (thermal, app-killing, internal antenna) make it a stopgap, not the answer.
2. **Production:** Pi Zero 2 W + USB u-blox GNSS (NEO-M9N preferred; BU-353-S4 a fine first cut) + roof-mounted active GPS antenna. Pi runs `gpsd` plus a small UDP NMEA broadcaster (Python or Go, ~20 lines) on the same WiFi. Power: 12V → 5V buck on switched ignition.

**Why not Pi Zero 2 W as both AP and GPS:** its WiFi is 2.4 GHz only (CYW43438), single antenna, and the AP-mode firmware is reliable up to ~5-6 clients before hostapd starts dropping clients. 8-10 tablets in a metal car needs proper RF design — let the existing travel router (better chip, dual-band, real antennas, OpenWrt) do AP/DHCP and let the Pi be a single-purpose GPS node. If we ever wanted one box, Pi 4 (dual-band CYW43455) is the right choice, not Zero 2 W.

**Hardware pricing context:** legit u-blox modules are $40-80 right now (M8/M9 channel supply has thinned as u-blox shifted focus to automotive/F9-F10). Sub-$25 "NEO-M8N" boards on Amazon are mostly counterfeit with older firmware and worse sensitivity. Used Garmin GLO (~$20-40 on eBay) or new BU-353-S4 (~$30) are perfectly fine fallbacks for first integration — for a road vehicle at normal speeds, M9N-grade accuracy isn't required.

**Architecture impact:** adds a 5th `LocationSource` implementation alongside FAKE/SYSTEM/BLE/USB. Same `state: StateFlow<LocationSourceState>` contract, same `NmeaParser`, same selector chip pattern. The phone bring-up is purely to prove `NetworkLocationSource` end-to-end before any hardware purchase — once it's working with the phone, swapping to the Pi is just "different IP broadcasting on the same port."

Open follow-ups:
- Implement `NetworkLocationSource` (UDP listener on 10110, parse via `NmeaParser`, emit `LocationSourceState`).
- Add `LocationSourceType.NET` and wire a `[NET]` chip into `CRTVectorScreen` right rail.
- Source-of-truth question: when multiple sources are available (e.g. NET + USB on the same tablet), what's the priority? Probably explicit user selection (existing pattern) — but document it.

---

## 2026-04-26 — Audit Medium/Low sweep

Closed every Quick-Win item from `audit.md` plus a handful of nearby Medium/Low items. Eight commits:

- **M5** — `CockpitUiState.egoFix` now a stored val (was a getter recomputed per read).
- **M3** — `PolygonRing.centroid` precomputed at parse time; renderer reads it for toilet markers.
- **M1** — deleted `VehicleGateway` (one-method parent of `VehicleConnectionGateway`, no other callers); deleted `VehicleCommand.EmergencyStop` (no callers since `bd926af`); moved `FakeVehicleGateway` to test sources (production wires `RoutedVehicleGateway` + `FakeTransportAdapter`).
- **M13 / M15** — promoted heading / speed bounds to `CockpitUiState.Companion` constants alongside the existing tilt/pan limits; reset placeholder defaults `42°/28kph/60°C` to `0/0/0` (thermal is overwritten by the first telemetry tick anyway).
- **M11 / M12** — NMEA: KDoc now reflects what the parser actually accepts (any GGA/RMC talker — GP/GL/GA/GB/GN); checksum reader tolerates the 1-digit form some receivers emit when value < 0x10.
- **L5** — `PlayaViewport` now `require(anchorYFrac in 0.0..1.0)` at construction.
- **L1 (partial)** — added tests for `wrapHeading` (zero / in-range / wrap / negative / large) and three new `RoutedVehicleGateway` cases (switch-while-connected leaves old adapter up; double-connect is idempotent; send-after-disconnect propagates ERROR). The `MapTouchInput` pinch-reset test deferred — needs a small refactor to extract a pure `PinchSession` from inside `awaitPointerEventScope`.
- **M7** — `androidx.datastore:datastore-preferences` plumbed through three phases: (1) added `CockpitPreferences` interface + `DataStoreCockpitPreferences` impl with round-trip tests; (2) wired into the VM for `tilt / mapMode / locationSource`, read on init, write on each user action; (3) lifted `pixelsPerMeter` from `CRTVectorScreen.kt`'s local `mutableDoubleStateOf` into VM-owned state and persisted it. Pan offset stays session-only — it's transient, not preference. Detekt's `TooManyFunctions` threshold bumped 11→12 in `config/detekt/detekt.yml` to accommodate the legitimate twelfth VM operation.

Architectural decisions on the way through:
- Enums in DataStore are stored by `name`, not ordinal — renaming a constant invalidates the key (read returns default) instead of silently mis-mapping to a different enum value.
- Tilt and zoom are clamped on read so a tampered prefs file can't seed values the controls can't reach.
- Snapshot intentionally excludes pan and connection/ego state. Audit only listed source/tilt/zoom/mapMode; pan is more session than preference.

`tasks/open.md` rewritten to reflect what's actually open (the prior content predated everything from BRC-map Phase 1 onwards). New `tasks/done.md` records what shipped. `README.md` gets a one-line cross-link to `SYNC.md` and `tasks/open.md` for new readers landing cold.

61 unit tests green; full CI gate clean across all eight commits.

---

## 2026-04-26 — Audit fix H7: surface map load failures via MapLoadResult

`AssetsPlayaMapRepository.parseAll()` had no try/catch — a missing or malformed asset would throw `IOException` (or `JSONException` for bad JSON) out of `load()`, get silently swallowed by viewModelScope's exception handler, and the cockpit would render forever with a null map and no diagnosis.

Fix:
- New `core/model/MapLoadResult` sealed type: `Loading | Loaded(map) | Failed(message)`.
- `PlayaMapRepository` interface gains `loadResult: StateFlow<MapLoadResult>`. The existing `map: Flow<PlayaMap>` is kept and now derives from `Loaded` results.
- `AssetsPlayaMapRepository.load()` wraps `parseAll()` in try/catch over `IOException` and `JSONException`, transitioning to `Failed(message)` on either. Detekt's ReturnCount tripped when both catches had `return` statements; refactored to a separate `runLoadAttempt(): MapLoadResult` expression-body fn so `load()` has a single early return.
- Extracted a `PlayaAssetReader` interface (default `AndroidPlayaAssetReader` wraps `AssetManager`) so the JVM tests can substitute a fake without Robolectric. The audit/prod constructor signature `(AssetManager, year)` is preserved via a secondary ctor.
- `CockpitUiState` adds `mapLoadError: String? = null`; the VM collects `loadResult` in its consolidated init and copies the message into state. UI rendering of the error is intentionally out of scope for this commit (a later, design-driven follow-up).

Logging via `android.util.Log.e` was tried first but failed JVM tests (Log not mocked). Dropped — the `Failed.message` already carries the diagnostic; full logging belongs with M10's Timber pull-in.

2 new tests in `AssetsPlayaMapRepositoryTest`:
- `load_with_missing_asset_emits_failed_without_throwing` — `IOException` from the reader → `Failed("art.geojson not found")`, no rethrow.
- `load_after_failure_does_not_latch` — `Failed` doesn't lock the repo into terminal state; a future retry remains possible.

`NoOpPlayaMapRepository` test stub updated to provide `loadResult`. 57/57 green; full CI gate clean.

This closes the audit's Critical and High batch — all 13 issues from `audit.md` are now addressed (with H2 confirmed not-a-bug per the audit itself). Ready to move to features again.

---

## 2026-04-26 — Audit fix H6: restart location source on permission grant

`MainActivity`'s permission-launcher result callback was a bare comment — `{ /* …no callback action needed */ }`. After the user granted `ACCESS_FINE_LOCATION` (or BT permissions), the active source stayed in its prior `Error("…not granted")` state until the user manually re-toggled a chip or relaunched the app. Surprising UX.

Fix: new `CockpitViewModel.restartLocationSource()` does `locationSource.stop()` then `locationSource.start()`. `MainActivity`'s permission callback now calls it whenever any permission was granted in the result map. The chip toggle path is unchanged.

Detekt's `TooManyFunctions` (limit 11) tripped because adding `restartLocationSource` brought the VM to 12. Combined `connectTransport`/`disconnectTransport` into a single `setTransportConnected(connected: Boolean)` — one less method, same call sites with trivial lambdas at the rail.

New test: `restartLocationSource_stops_then_starts_active_source` constructs the VM with a `StubLocationSource` (counts start/stop), confirms init issued one start, then asserts a `restartLocationSource()` results in stopCalls=1, startCalls=2.

55/55 green; full CI gate clean.

---

## 2026-04-26 — Audit fix H5: clamp pan offset

`CockpitViewModel.panBy` accumulated `panEastM` / `panNorthM` without bound. A stuck finger or runaway drag could shift the camera tens of kilometres off the playa, and the only recovery was the RECENTER MAP chip — which a user who's already lost wouldn't necessarily think to look for.

Fix: new constant `CockpitUiState.MAX_PAN_M = 5_000.0` and a `coerceIn(-cap, cap)` in `panBy()`. 5 km is well past the trash fence, so legitimate pan stays unclipped; runaway pan stops there.

New test: `panBy_clampsToMaxPanMeters` confirms a 10×cap pan in either direction lands at exactly `±MAX_PAN_M`, and a follow-up `recenterPan()` + within-cap `panBy()` still accumulates normally.

54/54 green; full CI gate clean.

---

## 2026-04-26 — Audit fix H4: consolidate VM init into one ordered launch

`CockpitViewModel.init` previously fired off seven independent `viewModelScope.launch { … }` blocks. The audit observes that the location-source state collector and `locationSource.start()` were ordered nondeterministically — if `start()` raced ahead, the collector could miss the `Searching → Active` transition.

Fix: collapse all seven into a single outer `viewModelScope.launch { … }` whose body launches each child collector in sequence, then calls `locationSource.start()` last. With both `Dispatchers.Main.immediate` (production) and `UnconfinedTestDispatcher` (tests), `launch { collect(…) }` runs eagerly until the first suspend (inside `.collect`), so every collector is subscribed before `start()` runs.

Note that `RoutedLocationSource.state` uses `SharingStarted.Eagerly` and is a `StateFlow`, which conflates intermediate emissions by design. So `Searching → Active` may still be observed as a single jump to `Active` if both happen between two collector ticks. That's acceptable for our use — the UI cares about the latest state, not the path. Documented inline.

Also extracted `MainDispatcherRule` from `CockpitViewModelTest.kt` into its own file so future ViewModel tests (H5/H6 panBy clamping, restart-source) can reuse without duplication.

53/53 green; full CI gate clean.

---

## 2026-04-26 — Audit fix H1: derive RoutedVehicleGateway.connectionState

`RoutedVehicleGateway` previously kept its own `MutableStateFlow<ConnectionState>` and updated it imperatively after every adapter call (`_connectionState.value = currentAdapter().state.value`). The audit notes this works only because `FakeTransportAdapter.send` is synchronous; once the underlying adapter emits `ConnectionState` updates from its own coroutine (the eventual real BLE/USB/WiFi adapters will), the snapshot read lags any state change that happens between the call returning and the read landing.

Fix: drop the manual `MutableStateFlow`. `connectionState` is now derived from the active adapter's state via `_selectedTransport.flatMapLatest { adapter(it).state }.stateIn(scope, Eagerly, …)` — the same shape `RoutedLocationSource` already uses. `connect/disconnect/send/selectTransport` no longer touch `_connectionState`; the underlying adapter's emissions propagate automatically.

Constructor gains a `scope: CoroutineScope` parameter; `ZodiacApplication` passes the process-lifetime `applicationScope` it already owns.

New test: `connection_state_forwards_active_adapter_state` confirms (a) underlying adapter `connect()` propagates to gateway state, (b) `selectTransport` reroutes the forwarding to the new adapter's state. Existing happy-path test updated to pass `backgroundScope`.

53/53 green; full CI gate clean.

---

## 2026-04-26 — Audit fix C1: hoist DI graph into ZodiacApplication

`MainActivity`'s Composable previously built `MainScope()` inside `remember{}` and constructed the entire DI graph (registries, routed sources, gateway, repos) there. The scope was never cancelled — every Activity recreation would leak a fresh MainScope and orphan the prior subscriptions. The audit notes the landscape-only orientation lock has masked this so far; any future config-change handling would expose it.

Fix: new `ZodiacApplication : Application` owns:
- `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` — process-lifetime
- `telemetryRepository`, `vehicleGateway`, `playaMapRepository`, `locationSource` as `by lazy` properties

`AndroidManifest.xml` declares `android:name=".ZodiacApplication"`. `MainActivity.kt` shrinks to ~50 LoC: it casts `LocalContext.current.applicationContext` to `ZodiacApplication` and reads the prebuilt deps. Permission launcher logic is unchanged for now (H6 wires the grant-restart hook in a later commit).

No new tests. Robolectric isn't on the classpath, and the change is structural — verified by inspection + a clean `assembleDebug` (the manifest application name is parsed at build time). 52/52 tests still green; full CI gate clean.

---

## 2026-04-26 — Audit fix C5/H3: Mutex around source lifecycle

`RoutedLocationSource.select/start/stop` and `FakeLocationSource.start/stop` previously had no mutual-exclusion. The audit calls these "latent rather than active" because `viewModelScope` happens to serialize on Dispatchers.Main today — but any future caller that fans out via `launch {}` could race the `_selected.value` reads/writes or the `job?.isActive` re-entry guard.

Fix: `kotlinx.coroutines.sync.Mutex` on both classes, with each suspend method wrapped in `mutex.withLock { … }`. The Mutex is owned per-instance; it doesn't extend the underlying source's contract.

New test: `concurrent_selects_serialize_through_mutex` issues two concurrent `select()` calls on a 3-source registry and asserts each transport gets exactly one start/stop pair through the chain. New `start_re_entry_is_safe` proves a re-entered `start()` on `FakeLocationSource` keeps the single-loop invariant.

52/52 green; full CI gate clean.

---

## 2026-04-26 — Audit fix C4: SystemLocationSource double-start guard

`SystemLocationSource.start()` previously called `requestLocationUpdates(...)` every time, so two `start()` calls without an intervening `stop()` would register the same `LocationListener` twice — every fix would fire the callback twice and battery drain would compound.

Fix: track `var listenerRegistered` and gate both `start()` (early-return if true) and `stop()` (only `removeUpdates` if true). Also extracted a `SystemLocationManagerHandle` interface so the source is unit-testable without Robolectric: the production constructor `(Context)` builds the real `AndroidSystemLocationManagerHandle`; the seam constructor `(SystemLocationManagerHandle)` accepts a fake.

4 new tests in `SystemLocationSourceTest`: double-start registers once, stop+start re-registers, no-permission emits Error without registering, stop-without-start is a safe no-op. 50/50 green; full CI gate clean.

---

## 2026-04-26 — Audit fix C2/C3: close BT/USB on connect failure

`BleLocationSource.runConnection()` and `UsbLocationSource.runConnection()` previously caught `IOException` from the connect/open path but never closed the half-allocated socket / port. The next `start()` call would overwrite the field, leaking the prior FD.

Fix: in the catch block, `runCatching { close() }` and clear the field before transitioning to `Error`. No try/finally needed — the success path's cleanup still flows through `stop()` as before.

No unit test this round — `BluetoothSocket` / `UsbSerialPort` aren't easily faked without Robolectric, which we'll pull in deliberately later. Inspection-grade: the catch block now mirrors the `stop()` cleanup almost line-for-line.

Full CI gate clean; 46/46 tests green.

---

## 2026-04-26 — Audit fix C6: NMEA hemisphere validation

Starting a sweep of the audit (audit.md, 2026-04-26) — Critical and High issues only, ten commits, in order, each one CI-green. Plan lives at ~/.claude/plans/review-the-critical-and-binary-goose.md.

This commit, the first of the batch, fixes **C6**. `NmeaParser.parseLatitude/parseLongitude` previously treated any non-S/W hemi value (including the empty string) as North/East — meaning a degraded GPS fix that emits an empty hemi field could shift reported position by 5,000+ km. Now the parser validates hemi against `{"N","S"}` / `{"E","W"}` and returns null when invalid; the GGA/RMC parsers already handle a null lat/lon by rejecting the sentence, so the upstream behavior is "ignore this sentence" — which surfaces as `Searching` rather than a phantom position.

Refactor note: detekt's `ReturnCount` (limit 2) tripped on the obvious `if (hemi !in …) return null` + `value.toDoubleOrNull() ?: return null` shape. Combined into a single guard.

3 new tests covering empty-hemi RMC, "X"-hemi RMC, and empty-hemi GGA. 46/46 green; full CI gate clean.

---

## 2026-04-26 — RECENTER MAP chip + pan state lifted to VM

The pan offset (added in the previous commit as local `centerViewport` state) is now in `CockpitUiState.panEastM` / `panNorthM`, which lets a button anywhere reset it. Added a small **RECENTER MAP** action-chip at the bottom of the right-rail control stack (electric-blue style, after the TILT chip row) — tapping it sets pan back to (0, 0), restoring the camera to the ego/Spike position.

VM additions:
- `panBy(dEastM, dNorthM)` accumulates into state.
- `recenterPan()` zeroes both.

`centerViewport`'s `onPan` callback now receives world-meter deltas (the screen-px → m conversion stays inside `centerViewport`) and forwards to `viewModel::panBy`. The local pan state is gone.

Cleanup as part of this:
- `CockpitViewModel.emergencyStop` was unused (no UI binding) — deleted per CLAUDE.md's "delete unused" rule. This kept the VM under detekt's per-class `TooManyFunctions` ceiling after the two new pan methods landed.
- `ChipControls` gains `onRecenter`.

Verified on emulator: drag the map off-center, tap RECENTER MAP, pentagon snaps back to viewport center. Pan state is shared between drag and recenter via state, no double bookkeeping.

43/43 unit tests still green; full CI gate clean.

---

## 2026-04-26 — One-finger drag-pan + SPD SET chips

Replaced the one-finger touch-to-set-heading/speed behavior with one-finger **drag-pan** of the map. Two-finger pinch zoom is unchanged. Now it's:

- 1 finger drag → camera moves so the world point under your finger stays under your finger.
- 2+ fingers → pinch-zoom (existing).
- HDG / SPD / TILT chips on the right rail → manual debug control of those values.

Implementation:
- `cockpitTouchInput` signature changed: `onHeading` + `onSpeed` callbacks replaced by `onPan(dxScreenPx, dyScreenPx)`. Internally tracks `lastPanX/Y` and `hadOneFinger` so the first frame of a touch (no prior position) doesn't snap-pan.
- `centerViewport` adds two pieces of mutable state — `panEastM`, `panNorthM` — accumulated across drags. The camera center becomes `egoFix + (panEastM, panNorthM)`. The pan callback converts screen-pixel deltas into world-meter deltas using the current heading: `dEast = (-dx*cos(h) + dy*sin(h)) / ppm`, `dNorth = (dx*sin(h) + dy*cos(h)) / ppm`. (Derived by inverting `PlayaViewport.toScreen`'s rotation+scale + a sign flip on Y because screen-Y is down, and a sign flip on the result because moving the finger right means the camera moves left in world.)
- New `SPD SET: NN kph` row in the right rail with chips `[-10] [-1] [+1] [+10]`, mirroring `HDG SET`. Calls `viewModel.setSpeed` (existing).
- `RightRailCallbacks` was at 6 params and adding `onSetSpeed` would have hit detekt's 7-param trip. Bundled the three setters into a new `ChipControls(onSetHeading, onSetSpeed, onSetTilt)` data class, so `RightRailCallbacks` is back to 5 fields.
- `centerViewport` no longer takes `onHeadingChange` / `onSpeedChange`; lost-functionality is fully covered by the chip rows.
- VM's telemetry collector also drops `speedKph` (heading was already dropped in the previous commit). Telemetry now only drives `thermalC` / `mode` / `linkStable`. User input owns the rest.

Verified on emulator: a 200-px diagonal swipe over the viewport visibly slides the pentagon and grid in the same direction, with HDG staying put. SPD SET chips work; speed no longer drifts back from telemetry.

43/43 unit tests still green; full CI gate clean.

---

## 2026-04-26 — TILT polish: centered view + adjustable tilt

Two follow-ups after the meter-space-grid work:

- Rob asked to center the grid + map on screen. Changed `MAP_ANCHOR_TILT` from `0.78` → `0.5` so the camera origin (Spike) sits at the canvas midpoint pre-rotation. With the +tilt rotation pivoting around the same midpoint, the Spike (and the city around it) stays at the screen center post-tilt. The ego marker on the non-tilted overlay still anchors at `0.78` for the driving-HUD feel — i.e., the driver is in the foreground and the city is centered ahead.
- Added an adjustable `TILT: NN°` row to the right rail with `[-10] [-1] [+1] [+10]` chips. Backed by:
  - `CockpitUiState.tiltDeg` (default 40, with companion-object `MIN_TILT_DEG = 0` / `MAX_TILT_DEG = 80`).
  - `CockpitViewModel.setTiltDeg(deg)` — clamps via the companion-object limits and updates state.
  - `RightRailCallbacks.onSetTilt` threading.
  - `centerViewport`'s `graphicsLayer { rotationX = state.tiltDeg.toFloat() }` consumes state instead of the hardcoded constant.
- New unit test `setTiltDeg_clampsToRange` confirms 55 stays 55, -30 clamps to 0, 120 clamps to 80.

Verified on emulator: `MAP: TILT` chip → pentagon now sits dead-center with the grid radiating around it; the new `TILT: 40°` row in the right rail responds to chip taps.

43/43 unit tests green; full CI gate clean.

---

## 2026-04-26 — TILT alignment fix: meter-space grid + lower map anchor

Rob's feedback after Phase B + the pitch flip:
- The grid and the map didn't share a vanishing point.
- The grid covered <30% of the viewport.
- The city was barely visible up near the horizon while the grid floated separately in the foreground.

Root cause: the original `drawRetroGrid` was drawn in **canvas pixel-space** (lines from a hardcoded `0.62 * height` horizon to the bottom edge). The map was drawn in **playa-meter space** through `PlayaViewport`. Both got rotated by the same `graphicsLayer { rotationX = 40 }`, but their 2D pre-rotation vanishing points differed, so post-rotation they didn't line up.

Fix:

- `PlayaViewport` gains an `anchorYFrac: Double = 0.5` parameter. The camera origin (where `center` projects on the canvas) is now `heightPx * anchorYFrac` instead of always mid-screen. In TILT mode, the cockpit passes `0.78` so the playa Spike sits in the lower-third of the canvas before tilt — which after the +40° rotation lands the city's foreground in front of the ego and the deep playa receding upward.
- `drawRetroGrid(viewport)` is rewritten in **meter-space**: a 200 m × 200 m mesh centered on the viewport's `center`, ±5 km in each direction. Each line is projected through the same `viewport.toScreen` the map uses. Grid + map then share one projection, so their vanishing points coincide *by construction*.
- Brightened `GridGreen` from `#0A3D1D` to `#1F6E37` and bumped stroke to 1.2 px so the grid reads at typical emulator/tablet pixel densities.
- Added two new `PlayaViewport` tests for the anchor parameter (camera-origin shift, north-offset projection above the anchor).

Side effects:
- `BrcMapRenderer.kt` was at 9 functions before this change. Adding a new `toOffset()` extension would have pushed it over the 11-fn-per-file ceiling, so the conversion is inlined at the two `drawLine` call sites.
- `TILT_ZOOM_BOOST` reverted from 2.0 to 1.0 — with the meter-space grid sharing the projection, an extra zoom in TILT broke the grid/map ratio. Pinch zoom still works for both modes uniformly.

Verified on emulator: `MAP: TILT` chip → playa pentagon now sits on the meter grid, both foreshortened identically; the deep playa recedes to a single vanishing point near the upper-third of the viewport. Screenshot at `/tmp/zodiac-tilt-bright.png`.

42/42 unit tests green; full CI gate clean.

---

## 2026-04-26 — Stopped auto-rotation + added HDG SET debug control

The map was auto-rotating because `FakeTelemetryRepository` ticks `headingDeg` every 500 ms and the VM was forwarding it into UI state. That made the TILT view impossible to inspect at a steady angle.

Fix:
- VM's telemetry collector no longer overwrites `headingDeg`. Heading is now owned by user input only — touch on the viewport (X→heading) or the new debug chips. Telemetry continues to drive speed / thermal / mode / linkStable.
- New right-rail section under the GPS chips: `> HDG SET: NNN°` + a chip row `[-15] [-1] [+1] [+15]`. Each chip wraps `viewModel.setHeading((current ± step + 360) % 360)` so it loops around the compass instead of clamping at 0/359.

Refactor side-effects:
- `rightRail`'s callback list grew to 6 → trips detekt's `LongParameterList`. Bundled into a new top-level `data class RightRailCallbacks(...)` and pass `state` + `callbacks` (2 params).
- `wrapHeading(deg)` would have pushed `CRTVectorScreen.kt` to 12 functions (over the 11-per-file ceiling). Lifted to a new tiny file `app/src/main/java/ai/openclaw/zodiaccontrol/ui/Heading.kt`.

Verified on emulator: heading reads 42° at launch and stays put. The chip row works visually (no functional regression — the chips re-use the existing `transportChip` style).

40/40 unit tests still green.

---

## 2026-04-26 — Pitch direction flipped (+40° instead of -40°)

Initial Phase B used `rotationX = -40f` which tilted the TOP of the canvas TOWARD the viewer — placing the BRC pentagon in the foreground (large) and the deep playa receding away. That's a "looking down at a tabletop tilted toward you" effect. Rob's feedback: *that's upside-down*, the wanted look is Battlezone — retro grid sweeping from the bottom-foreground up to a vanishing point with city features small in the distance.

Fix: `TILT_PITCH_DEG = 40f` (positive). With Compose's `rotationX` semantics (matches `View.setRotationX` — positive tilts the top away from the viewer), the BOTTOM of the canvas now comes forward and the TOP recedes. Result: the retro grid dominates the foreground, the playa map shrinks toward the horizon as a distant target. Ego marker on the non-tilted overlay still anchors at lower-third, which now correctly reads as "in the foreground driving forward."

Trade-off: at the default zoom (`pixelsPerMeter = 0.18`), the city is small in TILT mode because it's projected near the vanishing point. Pinch-out is the answer (Ctrl-drag in the emulator); zoom now serves both modes. If the default zoom feels wrong-sized in TILT, a per-mode default zoom is a Phase-C polish.

---

## 2026-04-26 — Phase B landed: 3D tilt + retro perspective grid

`MAP: TILT` mode is live. Tap SYS-3 in the left rail and the playa pitches ~40° forward, the trash fence narrows toward a vanishing point, streets recede, and a dark-green converging grid sits underneath. The ego triangle stays upright at the lower-third of the viewport (driving-HUD anchor).

Implementation:
- `centerViewport` is now a two-Canvas stack inside one `Box`:
  1. **Tilted Canvas** with `Modifier.graphicsLayer { rotationX = -40f; cameraDistance = 8f * density; transformOrigin = (0.5, 0.5) }` (only when `state.mapMode == TILT`). Draws `drawRetroGrid()` first, then `drawPlayaMap(...)`. The renderer no longer draws the ego.
  2. **Plain overlay Canvas** above it, no transform. Renders only `drawEgoMarker(viewport, anchorYFrac)` — `EGO_ANCHOR_CENTER = 0.5` for TOP, `EGO_ANCHOR_TILT = 0.78` for TILT.
- Touch handler hoisted from the inner Canvas to the outer Box so pinch and X→heading / Y→speed continue to use untilted screen pixels (avoids `graphicsLayer` foreshortening hit-tests).
- `BrcMapRenderer` adds public `drawRetroGrid()` and exports `drawEgoMarker(viewport, anchorYFrac)`. Constants for the grid (25 radial lines + 9 receding) match the original Phase-1 grid color `#0A3D1D` for the right retro-future feel.

Style notes / why `graphicsLayer` over hand-rolled pitch:
- Zero math in `core/geo/` — projection stays pure 2D, no JVM-test churn.
- The retro grid drawn inside the tilted layer inherits the same perspective for free; that's exactly the Battlezone effect.
- Ego stays upright because we draw it on a separate untilted Canvas above.

Detekt budget held: `CRTVectorScreen.kt` stays at 10/11 functions (no new functions added — the conditional `Modifier` is an inline `if/else` expression, the second Canvas is part of the existing composable body). `BrcMapRenderer.kt` adds 1 (`drawRetroGrid`) → 10/11.

Verified on emulator: TOP mode unchanged from before; tapping `MAP: TOP` switches to `MAP: TILT` (chip turns amber), the entire map content pitches into a vanishing point, ego marker shifts to the lower portion of the viewport, retro grid is visible as dark green converging lines beneath the playa. Screenshots saved at `/tmp/zodiac-tilt-top.png` and `/tmp/zodiac-tilt-on.png`.

40/40 unit tests still green; full CI gate clean.

Open follow-ups (Phase C polish if desired):
- Brighten / soften the grid color depending on subjective feel.
- Animate the TOP↔TILT transition (currently a snap).
- Hide the topmost ~10% of the tilted canvas (where content disappears past the horizon line) with a fade or solid mask for a cleaner "sky" feel.

---

## 2026-04-26 — Phase A landed: MAP MODE state + SYS-3 toggle

Wiring-only commit toward the 3D-tilt feature; no visual change yet.

- New `core/model/MapMode.kt` — `enum class MapMode { TOP, TILT }`.
- `CockpitUiState.mapMode` defaults to `TOP`.
- `CockpitViewModel.setMapMode(mode)` flips state directly (pure UI concern, no gateway).
- `leftRail` is now parameterized: `(mapMode, onToggleMapMode)`. SYS-3 (`MAP_TOGGLE_IDX = 2`) becomes a tappable chip labeled `MAP: TOP` / `MAP: TILT`; border + text amber when in TILT, green when in TOP. Reuses the existing border + clickable + Text idiom inline — no new functions added (CRTVectorScreen.kt stays at 10/11).

Tests: new `setMapMode_flipsUiState` confirms TOP → TILT → TOP round-trip. 40/40 unit tests green.

Phase B (the actual graphicsLayer tilt + retro grid backdrop + ego overlay) lands next.

---

## 2026-04-26 — Pinch-to-zoom on the map viewport

Map is no longer at fixed `0.18 px/m`. Zoom is now Compose state inside `centerViewport`, controlled by a combined gesture handler:

- 1 finger pressed → existing X→heading, Y→speed behavior (unchanged).
- 2+ fingers pressed → pinch zoom. Tracks the inter-finger distance at the start of each pinch session and scales `pixelsPerMeter` by the live distance ratio.
- Pinch sessions reset whenever the second finger lifts so a fresh pinch starts a new ratio (no jumpy state from stale baselines).

Bounds: `MAP_MIN_ZOOM = 0.05` (whole city + padding) → `MAP_MAX_ZOOM = 5.0` (street-level). Initial = 0.18, same as before, so the default view is identical.

Code shape:
- New `ui/playamap/MapTouchInput.kt` — `Modifier.cockpitTouchInput(currentZoom, onHeading, onSpeed, onZoom)`. Lives in its own file because `CRTVectorScreen.kt` was already at the 11-function file ceiling.
- `centerViewport` adds `var pixelsPerMeter by remember { mutableDoubleStateOf(MAP_INITIAL_ZOOM) }` and threads it through to `PlayaViewport`.

Tests still 39/39; full CI gate clean. Visual confirmation requires real multi-touch — verify on the emulator's extended-controls pinch panel or on hardware. The single-finger heading/speed path was unchanged in behavior, so no regression there.

---

## 2026-04-26 — Phase 4f landed: GPS source selector UI + permission flow

Right rail in `CRTVectorScreen` gains a `> GPS` header followed by a row of four chips — `[FAKE] [GPS] [BLE] [USB]` — mirroring the existing TRANSPORT chips. Tapping a chip calls `viewModel.selectLocationSource(type)`. Selected chip is amber, others green; same `transportChip` composable is reused.

A new status line above `TOUCH INPUT ACTIVE` shows the current `LocationSourceState` via a single `locationLine(state)` helper that returns `Pair<String, Color>`:
- `> GPS: OFFLINE` (amber) when Disconnected
- `> GPS: SEARCHING` (amber) when Searching
- `> GPS: 40.78725 -119.20535` (green, live updates) when Active
- `> GPS: ERR <detail>` (amber) when Error

Permissions: `MainActivity` adds a `LaunchedEffect(Unit) { permissionLauncher.launch(...) }` that requests `ACCESS_FINE_LOCATION` (+ `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` on Android 12+) once at app launch. Sources self-check at `start()` and emit `Error` if a permission is missing, so the UX stays graceful even if the user denies.

Style notes:
- `CRTVectorScreen.kt` was at 12 functions vs detekt's `TooManyFunctions` threshold of 11 (file-level). Inlined the trivial no-map placeholder; combined the two GPS helpers into one `locationLine`.
- The chip row is the same `transportChip` composable — visually consistent with the TRANSPORT row, no new components.

Phase 4 is now complete: foundation + parser + 3 real sources + UI selector. SYSTEM/BLE/USB will be integration-tested on a real Fire tablet with hardware (built-in GPS where available, paired Garmin/Bad Elf for BLE, u-blox/Prolific dongle for USB).

39/39 unit tests still green; full CI gate clean.

Open follow-ups:
- Mock-locations testing for SYSTEM source via `adb emu geo fix` is feasible on the emulator — tracked for next iteration.
- BLE source uses the first paired device matching a name regex; a real device picker is a future UX improvement.
- The `LaunchedEffect` permission flow surfaces three dialogs in sequence on first launch. Could be combined into one explainer dialog with deferred grants if it gets annoying.

---

## 2026-04-26 — Phase 4e landed: USB serial NMEA source

`data/sensor/UsbLocationSource` — uses [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) v3.9.0 (MIT, ~100 KB AAR via JitPack) to talk to NMEA dongles over USB host. The library handles the chipset-specific bulk-transfer protocols (CH340, FTDI FT232R, CP210x, PL2303, CDC-ACM, u-blox); we feed parsed lines through `NmeaParser`.

Permission UX: registered an `USB_DEVICE_ATTACHED` intent filter on `MainActivity` plus `res/xml/usb_gps_device_filter.xml` listing common GPS dongle VID/PIDs (u-blox 5446, Prolific PL2303 1659:8963, CH340 6790:29987, FTDI FT232R 1027:24577, CP210x 4292:60000). Android prompts to grant access automatically when a known dongle is plugged in. If the device isn't in the filter or permission isn't granted, the source emits `LocationSourceState.Error` instead of crashing.

Build wiring:
- `settings.gradle.kts` adds JitPack to `dependencyResolutionManagement.repositories`.
- `app/build.gradle.kts` adds `implementation("com.github.mik3y:usb-serial-for-android:3.9.0")`.

Default baud is 9600 (almost universal for consumer NMEA receivers); configurable via constructor for the rare 4800 / 38400 receivers.

Notes:
- `pumpNmea` was originally one tight loop; detekt's `NestedBlockDepth` (max 4) tripped on the inner `when`. Split into `pumpNmea` → `ingestBytes` → `emitLine` for clarity.
- JVM-untestable like 4c/4d. Will integration-test on a Fire tablet with a real dongle once 4f exposes the selector.

39/39 unit tests still green; full CI gate clean.

---

## 2026-04-26 — Phase 4d landed: BLE/Classic SPP NMEA source

`data/sensor/BleLocationSource` — Bluetooth Classic SPP for paired NMEA receivers (Garmin GLO, Bad Elf, Dual XGPS, etc.). On `start()`:

1. Verify `BLUETOOTH_CONNECT` (Android 12+ runtime grant; older APIs install-time).
2. Enumerate paired devices, pick the first whose name matches `DEFAULT_NAME_PATTERN` (regex covers GPS / Garmin / Bad Elf / XGPS / Holux / Qstarz / GNSS, case-insensitive).
3. Open RFCOMM socket on the well-known SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
4. Read NMEA line-by-line from the socket's input stream, feed each to `NmeaParser.parse`, emit `Active(GpsFix)` on hits.

All failure modes (no permission, adapter off, no matching device, IO error) emit `LocationSourceState.Error` with a short detail string instead of crashing. Cancellation closes the socket on `Dispatchers.IO`.

Manifest additions:
- `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` (no `maxSdkVersion`)
- `BLUETOOTH` + `BLUETOOTH_ADMIN` constrained to `maxSdkVersion="30"` (legacy Android 11 path)

Wiring: registered in `MainActivity` after the system source. Default selection still FAKE. JVM-untestable for the same reason as 4c — no Robolectric, depends on `BluetoothAdapter`. Will integration-test on a real Fire tablet with a Bad Elf or Garmin GLO once 4f exposes the selector.

Open follow-up:
- The name-pattern heuristic picks the first match. If a user has multiple GPS devices paired, we pick whichever happens to be first in `bondedDevices` order. UI-driven explicit picker is a Phase-4f-or-later task.

39/39 unit tests still green; full CI gate clean.

---

## 2026-04-26 — Phase 4c landed: SystemLocationSource

`data/sensor/SystemLocationSource` — wraps `android.location.LocationManager` and subscribes to `GPS_PROVIDER`. Converts each `Location` callback to `GpsFix` (lon/lat + bearing → headingDeg, speed m/s → kph, accuracy → fixQualityM).

Permissions:
- Added `ACCESS_FINE_LOCATION` to `AndroidManifest.xml`. minSdk=30 means runtime grant.
- If permission isn't granted at `start()`, the source emits `LocationSourceState.Error("ACCESS_FINE_LOCATION not granted")` and stays idle. UI for triggering the grant flow is Phase 4f.

Wiring: registered in `MainActivity` alongside `FakeLocationSource`. Default selection still FAKE — switching to SYSTEM is a future user action through the selector chip row.

Important caveat: **Fire tablets do not have a GPS receiver.** Selecting SYSTEM on a Fire HD/Max only works if location is being mocked or forwarded from another app on the device. Real GPS on Fire requires the BLE or USB sources (4d/4e).

No JVM tests for this source — depends on Android `Context` and `LocationManager`. Will integration-test on emulator via Mock Locations once 4f exposes the selector. Existing 39 unit tests still green; full CI gate clean.

---

## 2026-04-26 — Phase 4b landed: NMEA 0183 parser

`data/sensor/nmea/NmeaParser` — pure Kotlin, JVM-testable. Handles `$GPGGA` (lat/lon + HDOP-derived accuracy) and `$GPRMC` (lat/lon + speed-from-knots + course). Returns null for invalid checksums, "no fix" status, sub-minimum field counts, or unsupported sentence types.

9 unit tests cover: real-world GGA/RMC, southern + western hemispheres, no-fix rejection, void status, bad checksum, unknown sentence types, garbage lines, and trailing CRLF stripping.

Style notes for future phases:
- Detekt's `ReturnCount` (max=2) and `ComplexCondition` (max=4 operands) both bite on validation-heavy parser code. The clean shape is `if (early-fail) return null` once + a final `return if (further-checks) null else BuiltValue` block. `?: return null` chains push the count over.
- Top-level constants (sentence indices, conversion factors) are easier to read AND easier on Detekt than inline magic numbers despite `MagicNumber` being disabled — ktlint reads better with named constants too.

No behavior change to the running app; this is a pure-logic prep step for 4c/4d/4e where real receivers feed NMEA into the parser.

---

## 2026-04-26 — Phase 4a landed: LocationSource foundation + Fake

Wired up the GPS-source abstraction, mirroring `TransportAdapter`/`RoutedVehicleGateway`. The cockpit's center viewport now follows the ego: camera stays on the projected fix, world geometry slides past it.

Code shape:
- `core/sensor/{GpsFix, LocationSourceType, LocationSourceState}` — pure types, no Android deps. `LocationSourceState` is sealed (Disconnected, Searching, Active(GpsFix), Error).
- `data/sensor/LocationSource` — interface (`type`, `state: StateFlow`, `start()`, `stop()`).
- `data/sensor/FakeLocationSource` — synthetic. Default = slow circle at 200 m radius around the Spike with 60 s period; passing `pathRadiusMeters = 0.0` gives a stationary fix. Configurable `tickMillis` for emission cadence.
- `data/sensor/LocationSourceRegistry` — `Map<LocationSourceType, LocationSource>`.
- `data/sensor/RoutedLocationSource` — owns selection + state forwarding via `_selected.flatMapLatest { registry.sourceFor(it).state }.stateIn(scope, Eagerly, ...)`. `select()` only stop+start when type actually changes.
- `CockpitUiState.locationState` (+ derived `egoFix`) and `selectedLocationSource`.
- `CockpitViewModel(locationSource: RoutedLocationSource)` — kicks off `start()` and forwards `selected` + `state`. New public `selectLocationSource(type)`.
- `MainActivity` registers `FakeLocationSource` against `MainScope()`; routed sets initial type to `FAKE`.
- Viewport centers on `state.egoFix?.let { projection.project(it.location) } ?: PlayaPoint(0,0)` — falls back to the Spike when no fix.

Tests: 4 routed + 5 fake = 9 new (30/30 total). Visual confirm: emulator screenshots show the world drifting around a static ego triangle as the synthetic path advances. No UI selector yet — that's Phase 4f.

Lessons (kotlinx-coroutines-test 1.9.0):
- `runTest`'s leftover-coroutine guard treats coroutines launched into the test scope as failures with `UncompletedCoroutinesError`. **Pass `backgroundScope` (not `this`) for any long-lived `stateIn` / collect.** Tests can switch to `runTest(UnconfinedTestDispatcher())` to avoid needing `advanceUntilIdle()` for eager StateFlow propagation; we updated `MainDispatcherRule` to use the unconfined dispatcher too.
- ViewModelScope's StateFlow.collect coroutines also count as leftover — wrap VM tests with a `ViewModelStore` and call `store.clear()` in `finally` so `onCleared()` cancels viewModelScope.
- `FakeLocationSource`'s default loop schedules continuous `delay()` ticks on the test scheduler — runTime auto-advance can spin forever. **For VM tests, use the `StubLocationSource` test fixture (state-only, no timers).**

Open follow-ups:
- `MainScope()` in `MainActivity` is held in `remember`; not lifecycle-cancelled on Activity destroy. Acceptable for a single-Activity, landscape-only app, but worth replacing with the Activity's lifecycleScope or moving DI out of the composition before Phase 4 wraps.

---

## 2026-04-25 — Phase 3 landed: art layer

Added 2025 art locations from [iBurn-Data](https://github.com/iBurnApp/iBurn-Data) (master, MIT). 332 placements bundled at `app/src/main/assets/brc/2025/art.geojson` (~68 KB).

Source shape vs ours:
- iBurn ships proprietary JSON at `data/2025/APIData/APIData.bundle/art.json` (459 KB, includes descriptions/images/URLs/donation links).
- We strip to a minimal GeoJSON FeatureCollection of `Point` geometries with `{name, program, location_string}` properties — a 6× size reduction with everything we need for cockpit display.
- Conversion is one-shot Python (recorded in this entry); rerun yearly when iBurn publishes new data.

Filtering "major" vs "minor":
- Major = `program in {"Honorarium", "ManPavGrant"}` — 87 pieces, the BMOrg-funded large-scale art (incl. the Temple).
- Minor = the remaining 245 self-funded pieces.
- Renderer draws majors as 5 px hollow magenta circles, minors as 1.5 px dim magenta dots. Magenta picked to stay distinct from amber (plazas/ego) and the green/blue palette.

Wiring:
- `PlayaMap` gains `art: List<PointFeature>`.
- `AssetsPlayaMapRepository.parseAll()` adds one line: `art = GeoJsonParser.parsePoints(read("art"), nameKey = "name", kindKey = "program")`. No new parser — the existing `parsePoints` handled the shape.
- `BrcMapRenderer.drawPlayaMap` calls `drawArtMarker` for each art point before drawing the ego.

Tests still 21/21 (no new tests — the parser path was already covered, and rendering is visual). Verified on emulator: pentagon fence + C-shaped streets + amber plazas + blue toilet markers + green CPN dots + magenta art markers + amber ego triangle, all coexisting cleanly. Screenshot at `/tmp/zodiac-phase3-now.png`.

Open observations:
- Self-funded dot cloud near the open playa is dense but readable. If it gets noisier with future years, gate on zoom level or bump the radius down.
- No labels on art yet. Adding text labels means picking which majors get them at this zoom (probably the top-N by some criterion). Defer until we have a reason.

Phase 4 (real GPS source) is the only Phase remaining.

---

## 2026-04-25 — Phase 2 landed: BRC map rendering

The center viewport now draws the playa top-down. Replaced the perspective-grid + 3/4 vehicle wireframe with:

- `core/geo/PlayaViewport` — pure transform with `center`, `headingDeg`, `pixelsPerMeter`, viewport size. Track-up: heading direction is at the top, geographic north is up only when heading=0.
- `ui/playamap/BrcMapRenderer.drawPlayaMap` — DrawScope extension. Draws back-to-front: street outlines, street centerlines, trash fence (bright green, closed), plazas (amber, closed), toilet centroids (blue dots), CPN points (green dots), then a small amber ego triangle dead-centre.
- `CockpitUiState.playaMap`, `CockpitViewModel(playaMapRepository)` — VM kicks off `load()` on init and forwards the parsed map into state.
- `MainActivity` wires `AssetsPlayaMapRepository(context.assets)`.

Visual confirmation captured on emulator (`/tmp/zodiac-phase2.png`): pentagon fence, C-shaped streets converging at the Man, plaza/toilet markers visible. Touch-to-set heading/speed still works on top of the map (touching different X positions visibly rotates the world).

Style/build notes for next phase:
- Detekt's `LongParameterList` rule **counts the receiver** for extension functions — `DrawScope.foo(a,b,c,d,e,f)` is 7 params for the rule. Bundle related args into a small `RenderCtx` data class to stay under 6.
- The CRT styling holds up well in track-up mode. The 3/4 wireframe is preserved in git history (`05ebba5`) — reintroduce as a separate "vehicle status" mode if/when wanted.

Tests: 21 unit tests total now (6 projection + 6 viewport + 5 parser + 4 pre-existing); full CI gate clean.

Phase 3 (art layer, iBurn-Data) and Phase 4 (real GPS source) are still queued.

---

## 2026-04-25 — Phase 1 landed: PlayaMap data layer

Bundled the 2025 BRC GIS GeoJSON in `app/src/main/assets/brc/2025/` (7 files, ~907 KB). New code:

- `core/geo/LatLon`, `PlayaPoint`, `GoldenSpike`, `PlayaProjection` — equirectangular projection anchored on the 2025 Golden Spike (`-119.20300709606865, 40.78696344894566`). Pure Kotlin, no Android deps.
- `core/model/PlayaMap` — domain types: `StreetLine` (LineString + name/kind/widthFeet), `PolygonRing` (named ring), `PointFeature`, `PlayaMap` aggregate.
- `data/playa/GeoJsonParser` — schemaless reader using `org.json` (Android-bundled at runtime; added `testImplementation("org.json:json:20240303")` for the JVM test classpath).
- `data/playa/PlayaMapRepository` + `AssetsPlayaMapRepository` — assets → `StateFlow<PlayaMap>`, lazy load on first subscribe, parsing on `Dispatchers.IO`.

Tests: 6 projection + 5 parser, all green. Full CI gate clean (`ktlintCheck detekt testDebugUnitTest assembleDebug`).

Decisions:
- Golden Spike for 2025 was confirmed two ways: it equals the CPN named "The Man" in `cpns.geojson`, and at least one radial street has an endpoint with distance 0.0 m to that coordinate.
- `toilets` are published as polygons (banks of porta-potties), not points. Stored as `PolygonRing`s; renderer can centroid them in Phase 2.
- Equirectangular projection (no map library) — distortion stays sub-meter at 3 km radius near 40.79° N. Validated by test against known dataset trash-fence vertex.
- Geographic east/north only — BRC clock-axis rotation is a renderer concern, not a data-layer concern.

Style notes for future Phase work:
- Detekt defaults: `TooManyFunctions = 11` per object (i.e. ≤10 allowed); `ReturnCount = 2`. Move private helpers to top-level extensions if you bump the object limit; chain `?.takeIf{}` rather than multiple early-return guards.
- KtLint will reformat raw-string literals to start on a new line (don't pre-format them, ktlintFormat handles it).
- `String?.toIntOrNull()` is **not** defined — call `?.toIntOrNull()` on the safe-called value. `String?.toInt()` etc. similarly.

---

## 2026-04-25 — Map data direction (Phase 1 next)

Decisions:
- **Bundle approach:** static GeoJSON committed to `app/src/main/assets/brc/<year>/` (Path A). No fetcher in v1 — playa has no WiFi, manual yearly refresh is acceptable.
- **Source for streets / plazas / toilets / fence / blocks / CPNs:** [`burningmantech/innovate-GIS-data`](https://github.com/burningmantech/innovate-GIS-data) **master** branch (note: not `main`). 2025 dataset is the latest published; 2026 typically drops in July/August.
  - Raw URL pattern: `https://raw.githubusercontent.com/burningmantech/innovate-GIS-data/master/2025/GeoJSON/<file>.geojson`
  - Files (~900 KB total): `trash_fence`, `street_lines` (LineString, has `name`/`width`/`type`), `street_outlines` (Polygon), `city_blocks`, `plazas`, `cpns`, `toilets`. CRS = WGS84 lon/lat.
  - License: see [Innovate ToS](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/) — review before redistribution.
- **Source for art:** Innovate GIS data does NOT include art. Plan: pull from [iBurn-Data](https://github.com/iBurn/iBurn-Data) (Phase 3, separate decision).
- **Projection:** equirectangular, centered on the Golden Spike. Distortion is sub-meter at 3 km city radius — adequate for cockpit overlay, no map library needed.
- **GPS source:** Fire tablets have NO built-in GPS. Real input must come from external (USB GPS dongle / BLE NMEA receiver / phone tether). Architecture decision deferred to Phase 4; Phases 1–3 work without it.

Phased plan:
1. Data layer: bundle GeoJSON + `core/model/PlayaMap.kt` + `data/PlayaMapRepository` + `core/geo/PlayaProjection.kt`. Tests for parser & projection. No UI change.
2. Render streets / fence / plazas / toilets in center viewport. Ego pinned at Golden Spike.
3. Art layer from iBurn-Data.
4. Real `LocationSource` abstraction + at least one real impl.

---

## 2026-04-25 — Local dev environment bootstrapped

Toolchain installed for build + emulator on Intel Mac (macOS 15.7.4):

| Component | Path / version |
|---|---|
| OpenJDK 17.0.19 | `/usr/local/opt/openjdk@17` (via `brew install openjdk@17`) |
| Android cmdline-tools | `/usr/local/share/android-commandlinetools` (via `brew install --cask android-commandlinetools`) |
| Platform 35, build-tools 35, platform-tools, emulator | installed via `sdkmanager` |
| System image | `system-images;android-35;google_apis;x86_64` |
| AVD | `zodiac_tablet` (pixel_tablet skin, 2560×1600, landscape) |

Env vars persisted to `~/.zshrc`: `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`.

Lessons learned:

- **Don't use the `temurin@17` brew cask** — it's a `.pkg` installer that requires GUI sudo prompt; can't drive it from a non-interactive shell. `brew install openjdk@17` (the formula) installs to `/usr/local/Cellar` with no sudo and works identically for Gradle.
- **Emulator must be launched with explicit acceleration + software GPU on Intel Mac.** First attempt with `-gpu auto` hung the GPU thread: adb stayed `offline` for 12+ minutes, qemu pegged at 101% CPU on a single core, log had zero mention of `hvf` / hypervisor. Working invocation:
  ```bash
  emulator -avd zodiac_tablet -accel on -gpu swiftshader_indirect -no-snapshot -verbose
  ```
  Verification that HVF actually attached: `argv[NN] = "-enable-hvf"` and `CPU Acceleration: working` in the verbose log; CPU usage drops from ~100% to ~10% once the guest is HVF-accelerated.
- `emulator -accel-check` reports `Hypervisor.Framework OS X Version 15.7` available — confirms the host is capable; if it shows zero acceleration providers, that's a host-config issue (Docker/VBox/VMware can hold the hypervisor lock).
- AVD config tweaks for this project (in `~/.android/avd/zodiac_tablet.avd/config.ini`): `hw.initialOrientation=landscape`, `hw.keyboard=yes`. Project is landscape-locked so portrait boot just looks broken.
- First boot is cold (~90 s with HVF). Subsequent boots from snapshot are ~10 s — drop `-no-snapshot` after the first successful boot.

Convenience aliases worth adding when ready:

```bash
alias zodiac-emu='emulator -avd zodiac_tablet -accel on -gpu swiftshader_indirect &'
alias zodiac-install='./gradlew :app:installDebug && adb shell am start -n ai.openclaw.zodiaccontrol/.MainActivity'
```

Verified end-to-end: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 4/4 unit tests pass; APK installs, `MainActivity` launches and renders the CRT cockpit on the AVD.

Open code-review observations from the install pass (not blockers, log for later):
- Repo root has identity/bootstrap files (`BOOTSTRAP.md`, `SOUL.md`, `IDENTITY.md`, `HEARTBEAT.md`, `TOOLS.md`, `USER.md`, `AGENTS.md`) that look unrelated to the Android project — consider gitignore or move out.
- `ExampleInstrumentedTest.kt` exists but no instrumented-test job in CI — run it on a managed device or delete it.
- Dependency versions are inlined in `app/build.gradle.kts`. `gradle/` exists but has no `libs.versions.toml` yet — worth adopting before the module count grows.
- `FakeVehicleGateway.kt` + `VehicleGateway.kt` may be dead code post-transport-abstraction refactor; verify before next cleanup.
