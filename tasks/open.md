# Open

What's worth doing next, drawn from `audit.md` (2026-04-26) and a few items surfaced in the M/L sweep. Critical and High audit items are all done — see `done.md`. The remaining items are real but none block shipping.

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
