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
- [x] **M10 — rolling file logs — SHIPPED 2026-08-08.** `core/log/RollingFileLog`
      (bounded, never throws, drops oldest) + `data/log/FileLogTree` on Timber,
      writing to `getExternalFilesDir("logs")` so a tablet's log comes off with
      a plain `adb pull`. Lifecycle tagged: GPS select/failover, vision feed
      state, transport, map load path, uncaught exceptions. Verified on the
      S9+. Remaining: a debug screen over `RollingFileLog.tail(n)` (the method
      exists and is tested), and surfacing `droppedLines`.
- [x] **GPS source restarts once at startup — FIXED 2026-08-08.** Only missing
      permissions are requested now. Verified both directions on the S9+: a
      revoked permission still raises the dialog and logs one `gps: start`;
      granting it produces exactly one stop/start.
- [x] **M9 — `LocationSourceError` — SHIPPED 2026-08-08.** Five actionable
      categories across all 14 sites; category on screen, detail to the log.
- [x] **On-device log viewer — SHIPPED 2026-08-08.** Hidden bottom-right
      long-press; last 400 lines, severity-coloured, with the dropped count.
- [x] **L2 / L11 — SHIPPED 2026-08-08.** No-fix wire format + round-trip tests
      (mutation-verified); USB filter widened to vendor-wide + CDC class.
      L11 is unverifiable until a dongle is plugged in.
- [x] ~~**M14**~~ — stale: the label is already `RECENTER`, not `RECENTER MAP`.
      Still unconfirmed on the Fire (offline).
- [x] **M8 — SHIPPED 2026-08-08.** Rationale panel shown only when Android
      reports a prior decline, because the *second* decline latches to "don't
      ask again". Caught on device: the gate was declared before `cockpitScreen`
      and root siblings stack in declaration order, so the panel rendered
      underneath the whole UI. Verified end to end after the fix.
- [x] **M6 / M16 / L1 / L3 / L4 / L6 / L7 / L10 / L13 — DONE 2026-08-08.**
      See SYNC. Notable: M6's colour literals turned out to be the RADAR sweep
      palette, not theme colours — porting them would have been a visual
      regression dressed as cleanup, so only the four genuine palette
      duplicates moved, plus `LocalCockpitTheme`. L1 produced `PinchSession`
      (12 tests, mutation-verified). L13 is Apache-2.0, with a `NOTICE` carving
      out the Innovate-ToS GIS data.
- [x] **Burn-in stress ledger — DONE 2026-08-08.** `BurnInLedger` accumulates
      on-time per `<concept>/<phase>` and reports to the rolling log. That
      granularity is the honest limit — finer would invent data the app never
      measures.
- [x] ~~**L8**~~ — stale: `CRTVectorScreen.kt` no longer exists. Largest file is
      now `CockpitViewModel.kt` (674) then `PlayaMapPanel.kt` (598), both under
      detekt's thresholds. Nothing to split; revisit if detekt fires.
- [ ] **L10 remainder — needs hardware.** The matching logic and its diagnostic
      are extracted and tested (`BleDeviceMatch`), and a failed match now names
      the paired devices instead of dead-ending on "no device matched". The
      *picker UI* still wants a paired BLE GPS to build against.


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

## Architectural follow-ups — RESOLVED 2026-08-08

Worked through with the code in front of me rather than from the descriptions,
which had drifted. Two were done, two are decided **not** to do, with reasons.

- [x] **A2 — DONE.** `MapCameraState` groups mapMode / tiltDeg /
      pixelsPerMeter / cameraOverride / followMode / viewRotationDeg. Writers
      go through `camera`; the flat names survive as read-only accessors, so
      ~90 render-path call sites are untouched and a renderer *can't* write the
      camera. The compiler confirmed the containment — every error was a write,
      all fifteen in `CockpitViewModel`. (The task said five fields including
      panEastM/NorthM; those had already been replaced by cameraOverride +
      followMode. It's six.)
- [x] **A3 — DONE, in the part that was real.** `MapUiInputs` already *was* the
      "PlayaScene" (map + ego + camera + POIs + route, with `from(state)`).
      The remaining delta was delivery mechanism, and a CompositionLocal would
      make scene state **implicit** in the one file with a documented
      recomposition-storm history — where knowing exactly what invalidates the
      viewport is the whole game. What was worth doing: `MapUiInputs` now holds
      the grouped camera, so the viewport `remember` takes **one key instead of
      five**. Five keys were five chances to add a camera field and forget to
      invalidate on it, which presents as a map that silently stops following.
- [x] **A1 — WON'T DO; the premise doesn't hold.** "Collapse the three
      `Routed<T>` shapes into a single generic over `Map<Type, T>`" assumes
      three routers. There are two. `RoutedThreatSource` is not a router at
      all — it's a fixed two-source `combine` with a demo-fallback policy and
      no selection. And the two real routers have **deliberately opposite**
      switch semantics: `RoutedLocationSource.select()` stops the old source,
      `RoutedVehicleGateway.selectTransport()` deliberately does not (adapter
      lifecycle is owned elsewhere) — documented in both files and pinned by
      `RoutedVehicleGatewayTest`. A generic would need a policy parameter to
      preserve that, which is more machinery than the ~20 lines of duplication
      it removes across two 70-90 line files. Revisit only if a third real
      router appears.
- [x] **A5 — WON'T DO NOW; it trades a guarantee for an unmeasurable win.**
      The stated goal is avoiding a structural copy of (now 35) fields on
      per-frame mutation. Two things changed the calculus:
      1. **The recomposition half is already solved.** `MapUiInputs` exists
         precisely so the map subtree smart-skips when unrelated state changes;
         its KDoc says so. What A5 would still buy is one shallow 35-reference
         copy per update — sub-microsecond, and only at touch rate *during an
         active gesture*. That is a **performance** claim, and this project's
         standing rule is that performance changes get validated on the Fire
         HD 10, which is offline. Changing it blind would be guessing.
      2. **It would cost a real property.** One StateFlow is why all three
         concepts always render the same world and a concept switch can never
         show a different one. Three flows can be observed mid-update in
         inconsistent states — on a display that carries collision alerts.
      If profiling ever justifies it, the targeted move is *more input slices*
      like `MapUiInputs`, which keeps the single source of truth intact.
