# Zodiac Control — Handoff / Current State

**Read this first.** Point-in-time snapshot as of **2026-07-06** to get a new session up to speed. The chronological decision log is [`SYNC.md`](SYNC.md) (append-only, newest on top); the public snapshot is [`README.md`](README.md); open work is in [`tasks/`](tasks/). This doc consolidates *where we are* + *what's next*.

`main` is clean and green. Everything below is verified on the Galaxy Tab S9+ over wifi.

---

## What Zodiac is

Android tablet cockpit UI (Kotlin + Jetpack Compose) for **Galactic Relay camp's** mutant vehicle at Burning Man. Package `org.pureagave.zodiac.control`. Landscape, minSdk 30 / targetSdk 35, Kotlin 2.0.21 / JDK 17. Manual DI in `ZodiacApplication` (no Hilt). Reactive: ViewModel → single `StateFlow<CockpitUiState>` → Compose. All transports/telemetry are **fake**; GPS location sources are real (Fake/System/BLE/USB).

## Hardware / fleet (see memory `project_fleet_hardware_targets`)

- **Galaxy Tab S9+** (`SM-X810`, One UI 8 / Android 16, 2800×1752 **OLED**, Adreno) — the **main dashboard**. Currently the only device connected (wifi adb `192.168.0.253:5555`).
- **Amazon Fire HD 10** (`KFTUWI`, Android 11 / API 30, **LCD**) — the **performance floor**; can't burn in.
- Fleet also targets other Samsung Tabs (S9/S10/FE). Fire is the perf floor; an **Adreno OLED** device is needed to certify any GPU-blend visual work.

## The two concepts (`core/model/CockpitConcept`: RADAR, MAP)

Originally A `CRT VECTOR` / B `PERSPECTIVE` / C `TRACKER` / D `BAY`. **A and B were dropped; C→RADAR, D→MAP; letter tags removed.** `CockpitScreen` dispatches; the top-right pill cycles RADAR↔MAP (persisted). Default RADAR.

- **RADAR** (`ui/concepts/MotionTrackerScreen`) — *Aliens* M41A sweep scope: a circular scope whose sweep arm lights up the real BRC map as it passes. Left stat column (BEARING/SPEED), right (RANGE/ZOOM + control strip).
- **MAP** (`ui/concepts/InstrumentBayScreen`) — *Alien* Nostromo gauge wall: bordered tiles (heading dial, speed gauge, ground-track map, throttle trace, cell bars, control strip).

Both share `ui/concepts/ConceptControls` (control strip), `ui/concepts/PlayaMapPanel`, `navCueBar`, `conceptSwitcher`, `recenterButton`, and the ops footer.

## Color system (`ui/concepts/ConceptTheme`, shared constants)

Semantic, amber banned:
- **green `#00FF66`** — all chrome/controls/buttons/borders/labels
- **blue `#00BFFF`** — status only (link/connection/GPS state, *selected* control)
- **purple `#C77DFF`** — live data values (heading/speed/range/zoom, clock, distance, gauge needles, ego marker, map plazas)
- **red `#FF5555`** — faults / extreme warnings only

## Features built

- **OLED burn-in mitigation** (`burnin/`) — `BurnInMitigationManager` idle machine (ACTIVE→DIM→DEEP_IDLE→SLEEP, injectable clock) drives `burnInScaffold`, wrapping the whole cockpit. Pixel-shift (universal), brightness breathe/dim (OLED-only, gated off on the Fire), CRT `standbyScreen`, app-drawn black SLEEP, park gesture, hidden preferences-backed tuning panel. **All four phases verified on the S9+ OLED** (clean `ModulateAlpha` blend on Adreno). Design: [`design/BURN_IN.md`](design/BURN_IN.md).
- **Full-screen kiosk chrome** — `MainActivity` draws edge-to-edge + immersive (hides system bars), required by targetSdk 35 on Android 15+.
- **Operational readout** (`ui/ops/opsReadout`) — first-class palette-driven footer in each concept: BRC clock, sunrise/sunset (local NOAA calc, no API — `core/ops/SunTimes`), and **drive-to guidance**.
- **Drive-to navigation** (`core/ops/NavTarget`) — HOME (camp), MAN (Golden Spike origin), TEMPLE (2025 CPN, ~762 m on the 12:00 axis). Selected via a **prominent full-width `DRIVE TO` bar** (`ui/ops/driveToBar`) above the footer (active = blue). Footer shows `▸ <TARGET> <dist>` + heading-relative arrow (`core/ops/campGuidance`). `CockpitUiState.navTarget` + `CockpitViewModel.setNavTarget`.
- **Home/camp:** `Camp.GALACTIC_RELAY` = **Heiau & 2:15** (H street ∩ 2:15 radial). **Provisional** — computed from 2025 GIS (Herbert H-ring, 1555 m); replace with the BM API geocode / 2026 Golden Spike when published.
- Existing infra (pre-this-arc): pluggable GPS `LocationSource` (FAKE/SYSTEM/BLE/USB; NET planned for shared-WiFi NMEA), BRC playa map (`data/playa/`, `core/geo/PlayaProjection` anchored on `GoldenSpike.Y2025`), `core/navigation/` (PlayaNavigator, clock-bearing), DataStore prefs.

## Build / deploy / test (see memory `reference_build_deploy_env`)

- Toolchain not on PATH — set inline per command: `JAVA_HOME=/usr/local/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/usr/local/share/android-commandlinetools`.
- **CI gate before every commit:** `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug`. (Quirk: running `ktlintFormat` and `ktlintCheck` in the *same* gradle invocation races — run format, then a separate check.)
- **S9+ deploy over wifi:** `adb -s 192.168.0.253:5555 …`; `installDebug` then `am start -n org.pureagave.zodiac.control/.MainActivity`. Grant perms with `pm grant … ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION/BLUETOOTH_CONNECT/BLUETOOTH_SCAN`. `input` uses **landscape** coords on the S9+ (unlike the Fire's portrait frame).
- **Desktop-mode gotcha:** the S9+ was in Samsung DeX/desktop windowing — apps open freeform with a forced taskbar that overlaps the app bottom, and a reinstall reverts a maximized app to windowed. `screencap` captures the app framebuffer *without* the taskbar overlay. For clean fullscreen: screen-pin (kiosk) or exit desktop mode.
- **Testing display discipline (memory `feedback_s9_testing_display`):** sleep the display after every screenshot (`input keyevent KEYCODE_SLEEP`); keep brightness manual + low (`screen_brightness_mode 0`, `screen_brightness 20`). *"No burn-in until the Burn."*
- **Verifying burn-in phases:** temporarily shrink `BurnInConfig` timeouts + `DEFAULT_TICK_MILLIS`, deploy, screencap at intervals, revert.

## Constraints / do-not-touch

- Per the /remote-control briefing: **don't change navigation, GPS sourcing, or passenger messaging** ("already solved"). (Note: messaging doesn't exist in this repo — the briefing describes the broader product.)
- Offline-first for any live data; don't require always-on connectivity.
- Colors: green/blue/purple/red only; no amber.

## What's next — roadmap

**RADAR/MAP design (the active thread):**
- **RADAR** — plot the active destination (and nearby art/camps) as **contacts/blips** on the sweep scope; ties into the playa-discovery data.
- **MAP** — replace the remaining **fake gauges** (throttle trace = static float array; CELL A/B = fixed 70/45%; `SYS:ZD…` code) with real readouts; mark the destination on the ground-track.

**The likely "next big feature" — the network/data layer** (Starlink is onboard; see memory `project_data_ecosystem`). No network layer exists yet (no Retrofit/OkHttp/Room; `INTERNET` not in manifest). Standing it up (recommend OkHttp + kotlinx-serialization, offline-first, `Flow`-based repos) unlocks:
- **Weather + dust safety** — Open-Meteo (no key, lat 40.7869/lon -119.2042) → ambient strip + dust-risk (gusts >25 watch / >40 event) + NWS alerts (zone NVZ023) + shelter-bearing to camp.
- **Playa discovery** — Burning Man API (key obtained, `playaevents.burningman.org/api/0.2/`) camps/art/events → "what's near me" / passing-art / camp search. Gated on data release + 2026 Golden Spike + a local DB.

**Other open items:**
- 2026 **Golden Spike** publishes early July → update `GoldenSpike`; refine provisional Camp/Temple coords from the BM API geocode.
- Burn-in **Phase 5** stress-accounting ledger (optional; pairs with M10).
- **M10** — Timber + rolling-file logging (operational, do-soonest before playa).
- GPU **pixel-cache** perf win (biggest remaining perf item; needs on-device Adreno validation — partly de-risked now that `ModulateAlpha` renders clean on the S9+). See [`design/PERFORMANCE.md`](design/PERFORMANCE.md).
- Architectural shape calls A1–A5 in `tasks/open.md` (collapse `Routed<T>`, `MapCameraState`, `PlayaScene`, split `CockpitUiState`).
