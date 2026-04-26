# SYNC.md

Append-only log of significant decisions, lessons, and changes for the Zodiac Control project.

Newest entries on top. Each entry: ISO date, short title, body. Don't rewrite history — if something later turns out wrong, add a new entry that supersedes it.

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
