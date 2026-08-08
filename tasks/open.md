# Open

What's worth doing next. Critical and High audit items are all done — see `done.md`.

> **Handoff, 2026-08-08.** The two sections immediately below are the live
> picture. Everything under them is older backlog, still valid but not the
> current front. Read the top of `SYNC.md` first — it is the working log and
> carries the *why* behind each decision.

## ⛔ Blocked on Rob / hardware — cannot be done from a keyboard

Ranked by consequence on the playa.

- [ ] **`MotionDetector` resets a track on a single-frame dropout.** A flickering
      blob gets a new id *and* a reset collision baseline, so collision may
      chronically under-fire — a systematic bias toward false **negatives**
      while driving. **Highest-value remaining item.** Deliberately not fixed
      blind: it changes detection behaviour and needs real bodies at real
      distances in front of the real camera.
      ⚠️ **Do not add tablet-side contact coasting until this is fixed** —
      coasting by id while the Jetson churns ids draws one person as two.
- [ ] **Detector tuning** against people at 5 / 10 / 20 m, day and night.
- [ ] **Night legibility of the surround ring on the A54.** The acceptance gate
      the design names, and the one thing no measurement substitutes for: is a
      `#00421E` rim visible at brightness 20 in real darkness, and does a red
      collision blip register **peripherally**? The build is installed on the
      phone. Objectively verified already: rim solid over the covered arc (99%
      of samples lit) vs dashed elsewhere (~34–53%).
- [ ] **DMX fixture.** Whole path proven to the XLR connector — `olad` opens the
      widget, reports "Granularity GOOD", zvision drives pan/tilt/dimmer. Attach
      the moving head and aim it. Then **calibrate `pan_center_deg` / `pan_gain`**
      and `reach_half_deg` (currently 90°, i.e. forward + both sides).
- [ ] **Rig azimuth calibration** per camera against the vehicle nose, once the
      pod is mounted. An error rotates that camera's whole contact set.
- [ ] **Pod assembly** — waiting on the germanium D20 window.
- [ ] **Cross-camera dedup** with two cameras on one moving target (needs a
      second camera mounted).
- [ ] **grr validation tests** — rescue link, `fsck.repair` after reboot, HDMI
      capture. See the memory note; needs Rob's parts and hands.
- [ ] **Fire HD 10** — not on the network; can't check the perf floor.
- [ ] **XCover Pro** — needs USB to give the Beacon app its one-time
      STOP→START so `$ZAUD` picks up the mic grant.
- [ ] **Beacon channels + auto-dim** end-to-end on the tablets.

## 🟢 Doable without Rob — I ran out of session, not options

- [x] ~~**Shock-alert banner.**~~ Already shipped — `opsReadout` draws
      `◆ SHOCK n.ng` in the beacon line (red, faults-only palette), the
      ViewModel clears it after 2 s, and `CockpitViewModelTest` covers the
      re-arm. The backlog entry was stale, not the code.
- [x] **2026 map on-device address check — DONE 2026-08-08, and it found a
      real bug.** The 2026 GIS renamed its street schema, so every street
      parsed kind-less and the whole city model was empty: no street cues, no
      routes. Fixed + `BundledGisTest` now measures the parse, the ring radii,
      and `2:15 & H` against the shipped GeoJSON. Verified on the S9+:
      `2:15 & H → HEADING 112°, 1.6km`, matching the HOME preset exactly. See
      the SYNC entry.
- [ ] **Confirm the FFC fix over a long run.** `~/nightwatch/fp2.log` on the
      Jetson is accumulating. Pre-fix baseline: **104 contact-frames and 10
      phantom collisions per 7.7 h** in an empty room, arriving in 9 bursts.
      Post-fix at 40 min: **0 / 0** (pre-fix had already burst twice by then).
      Needs hours before the rate claim is honest. Listener script lives in
      `~/nightwatch/listen.py`; start it with `setsid`, and remember
      `pkill -f listen.py` over ssh **kills your own session** — use
      `"[l]isten.py"`.
- [ ] **M10 — rolling file logs** (Timber + `getExternalFilesDir`). Still the
      biggest operational gap: without logs we cannot postmortem a tablet that
      misbehaves on the playa.
- [ ] The older M6/M8/M9/M14/M16 UI items below.


## 2026 map migration — DONE in code 2026-07-30 (commit `ca74867`), pending on-device verify

The 2026 Innovate GIS went live; **the city moved ~583 m SW** (axis unchanged at 45.0°). Migrated the base map to 2026 via a single active-year source of truth (`GoldenSpike.ACTIVE`/`ACTIVE_YEAR`). All green.

- [x] Base map → 2026 (DI); art layer made optional in `PlayaMapRepository` (2026 GIS ships no `art`).
- [x] Flipped all `Y2025` refs → `GoldenSpike.ACTIVE` (11 files incl. the fake-GPS center).
- [x] `NavTarget.MAN`→ACTIVE, `TEMPLE`→2026 CPN; `Camp` HOME re-projected on the 2026 grid.
- [x] Ring radii **verified unchanged** vs 2026 street data (B=979 m, G=1470 m match exactly).
- [x] art/camps hidden until release (`DiscoveryRepository` year 2026 + `BmApiClient` projection on ACTIVE).
- [x] **On-device verify — DONE 2026-08-08.** Typing `2:15 & H` on the S9+ gives
      `HEADING 112°, 1.6km`, identical to the HOME preset. Getting there exposed
      that the 2026 GIS renamed `type`→`source`/`kind`, `width`→`width_ft` and
      `Name`→`name`, which had left every street kind-less (empty city model:
      no cues, no routes) and every plaza label null. Fixed; `BundledGisTest`
      guards it. Esplanade radius corrected 752.0 → 761.5 m (measured).
- [ ] *(optional)* render the new `dmz` layer; final re-pull of the GIS closer to the event (dataset still being edited).
- [ ] *(watch)* when the GIS is re-pulled, run `BundledGisTest` first — it is
      now the acceptance gate on a new dataset drop.

## Surround vision — zvision rig DONE 2026-08-03, HUD + calibration open

zvision now runs a ring of cameras and merges them into one full-circle contact list (`jetson/zvision/rig.py`), and the ZTHREAT wire carries the full ±180. See the SYNC entry for the four commits.

- [x] Fisheye/rectilinear lens models (`pixel_to_bearing`), `--hfov` default 57 → 160, `--lens`, `--fov-ref`.
- [x] Multi-camera rig: `--camera` specs, mount-angle rotation, per-camera id namespacing, overlap dedup, coverage/blind-arc report, per-camera failure tolerance.
- [x] Wire arc ±90 → ±180 on both sides; `DriverNightScreen` explicitly filters to the forward half so nothing changed on screen.
- [x] **Surround DRIVER HUD — SHIPPED 2026-08-07/08** and verified against real
      bus traffic. Hybrid layout: forward perspective figures (±30°) plus a
      nose-up plan ring carrying every contact. Design doc:
      `design/surround-driver-hud.md` (rev 2, after a Fable review).
- [x] **FOV reference — SETTLED 2026-08-08: the 160° is the DIAGONAL.** The rig
      covers **±64°, not ±80°**. FLIR never states the axis; the board emits
      160×120, and 160° across the width of a 4:3 f-theta frame needs a ~200°
      diagonal, which is not a real lens. `fovref=d` is in the deployed config.
      This feeds `pixel_to_bearing`, so the old reading mis-aimed every edge
      bearing by up to 16°. **Policy set: under uncertainty, under-claim
      coverage.** Widen only against a real measurement or a FLIR figure naming
      the axis.
- [ ] **Calibrate each camera's `az`** against the vehicle's actual nose once mounted — an error there rotates that camera's entire contact set and swings the tracker light onto the wrong person.
- [ ] **Decide RGB count + lens FOV** — that's what closes the ring. The UW already covers the forward 160°, so the RGB cameras mainly own the sides and rear; `zvision -v` prints the resulting blind sectors.

## Operational gap (do soonest)

- [ ] **M10** — pull in Timber, tag every source's lifecycle, write a rolling file under `getExternalFilesDir(...)`. Without logs we can't postmortem a tablet that misbehaves on the playa. Add a debug screen later that displays the last N lines.
- [ ] **Burn-in stress ledger** (deferred Phase 5 of the burn-in feature) — log cumulative on-time per pixel region to a local file so we can spot which regions are at highest burn risk and rotate UI elements between burns. Pairs with M10's rolling-file logging; only worth it if it stays cheap.

## UI / UX rough edges

- [ ] **M6** — introduce a `CockpitTheme` data class held in `CompositionLocal`; port the inline `Color(0xFFxxxxxx)` literals from `CRTVectorScreen.kt` and `BrcMapRenderer.kt`. Doesn't have to be Material3.
- [ ] **M8** — add a permission rationale UI (`shouldShowRequestPermissionRationale`) before launching the cold permission request.
- [ ] **M9** — `LocationSourceError` enum (`PERMISSION_DENIED` / `ADAPTER_UNAVAILABLE` / `NO_DEVICE_FOUND` / `IO_ERROR` / `UNKNOWN`) plus optional human detail; render an icon per category.
- [ ] **M14** — RECENTER MAP chip clips on smaller tablets; either constrain width or shorten the label to `CENTER`.
- [ ] **M16** — `FakeTelemetryRepository.tick` never resets (cosmetic; 34-year overflow at 500 ms).

## Performance (only matters on a real Fire tablet)

Rounds 2/3 + label TextLayout cache shipped 2026-05-03. A behavior-preserving pass shipped 2026-06-14 (see SYNC: Concept-C `sweepDeg` draw-scope deferral killed the 60fps recomposition storm; hoisted per-frame Strokes/colors; narrowed `topHeader`/`rightRail`/InstrumentBay recomposition; map paints before the cache write).

**Full future-wins backlog + on-device profiling method:** [`design/PERFORMANCE.md`](../design/PERFORMANCE.md). Short-list of the top actionable items:

- [ ] **TOP / needs device validation** — GPU **layer-promotion / pixel-caching of the rasterized map** (`PlayaMapPanel.mapBaseCanvas`). The geometry is cached but the *pixels* aren't, so the full CRT Skia call-list (halo pair ×4 + strokes + points + ~50 art circles) replays at 60fps under the Concept-C sweep (and on the TILT layer). Promote the base map to a `graphicsLayer`/`rememberGraphicsLayer` cache that only re-rasterizes on camera change, then blit one texture per frame. Biggest remaining win, but offscreen-compositing the translucent halos can shift blend math — **validate on a real Fire HD 10 before landing** (`adb shell dumpsys gfxinfo <pkg> framestats` for jank %, Layout Inspector recomposition counts, a Macrobenchmark `FrameTimingMetric` run).
- [ ] Concept-C lit re-blit: same family — `drawSweptProjectedMap` re-issues the whole map clipped to the wedge every frame. Either render the lit map once into a cached layer and clip+blit, or (cheaper, needs visual sign-off) `crtBeam=false` on `TrackerLitPalette`.
- [ ] Reuse the Concept-C wedge/cone `Path` objects across frames (`PlayaMapPanel.wedgePath` allocates a new `Path` + 24 trig pairs per frame; rewind a remembered `Path` instead). Lower priority now the recomposition storm is gone.
- [ ] Major art batched as one `Path` of `addOval`s (~50 `drawCircle` → 1 `drawPath`). The per-marker `Stroke` alloc is already hoisted.

## Testing

- [ ] **L1 remainder** — extract a pure `PinchSession` from `MapTouchInput` so the gesture state machine is unit-testable without `awaitPointerEventScope`. Test pinch-reset on finger lift, drag-pan with rotated heading.
- [ ] **L2 remainder** — NMEA parser coverage: empty trailing fields (`$GPGGA,,,,,,0,...`); a round-trip test (synthesize from a known `GpsFix`, parse, expect equal). _(Range/minutes/course-normalization/garbage coverage landed 2026-06-14 with the robustness pass; these two cases remain.)_

## Documentation polish

- [ ] **L3** — single-line note in `PlayaProjection` that distortion only stays sub-meter near the BRC origin (cos(lat0) → 0 at the poles).
- [ ] **L4** — KDoc note on `PlayaProjection.distanceMeters` that it's planar and would want Haversine for long distances.
- [ ] **L6** — class-level KDoc on `CockpitUiState`, `CockpitViewModel`, `LocationSourceState`, `MapMode`.
- [ ] **L13** — decide on a `LICENSE` file at the repo root (Apache-2.0, MIT, or explicitly closed-source).

## File-shape cleanup

- [ ] **L7** — extract `rememberCockpitDependencies()` out of `MainActivity.zodiacApp()`.
- [ ] **L8** — split `CRTVectorScreen.kt` (533 LoC, 11/11 detekt function-count limit). Three reasonable extractions: `RightRailControls.kt`, `LeftRailControls.kt`, `TopHeader.kt`.

## Hardware support catch-up

- [ ] **L10** — make `BleLocationSource.DEFAULT_NAME_PATTERN` configurable via DataStore + add a "pick device" picker.
- [ ] **L11** — extend `usb_gps_device_filter.xml` to cover FTDI FT232H, WCH CH343, SiLabs CP2104, MediaTek MT3329-based receivers.

## Architectural follow-ups (decide before adding more features)

These aren't bugs — they're shape calls worth making once before the codebase calcifies around the current layout.

- [ ] **A1** — collapse the three `Routed<T>` shapes (vehicle gateway, location source, future command source) into a single generic over `Map<Type, T>` plus serial Mutex.
- [ ] **A2** — promote camera state into a `MapCameraState` data class held in `CockpitUiState` (today: heading + pixelsPerMeter + panEastM/NorthM + tiltDeg + mapMode are five floating fields).
- [ ] **A3** — extract a `PlayaScene` (`map + projection + viewport + ego + panOffset`) provided via `CompositionLocal`, before adding the second consumer (night display, friend tracker, recorded-track replay).
- [ ] **A5** — split `CockpitUiState` into smaller per-concern StateFlows (`mapState`, `connectionState`, `egoState`) so per-frame state mutations don't structurally copy 14 fields.
