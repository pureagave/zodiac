# SYNC.md

Append-only log of significant decisions, lessons, and changes for the Zodiac Control project.

Newest entries on top. Each entry: ISO date, short title, body. Don't rewrite history — if something later turns out wrong, add a new entry that supersedes it.

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
