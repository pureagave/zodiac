# Performance — future wins backlog

Reference for the performance opportunities surfaced by the 2026-06-14 render audit
(9-subsystem analysis → adversarial verification → completeness critic; 44 findings,
28 confirmed real + on the hot path). The **behavior-preserving subset already shipped**
— see the `SYNC.md` entry "Perf audit (slow Fire tablets)" and commit `7f6331d`. This
doc captures everything **not yet done**, so it survives outside the workflow's scratch
output.

Target hardware: Amazon Fire HD 10 (weak GPU/CPU, GC-sensitive). The cockpit draws a
live Black Rock City map on a Compose `Canvas` at ~60fps; Concept C (Motion Tracker)
is the worst case — a `withFrameNanos` sweep drives 60fps redraws.

> **Caveat for every item below:** these are *principled static-analysis* wins
> (allocation / recomposition / overdraw reduction), **not device-profiled**. Confirm the
> felt improvement on a real tablet before and after — see [Measuring](#measuring-on-device).

---

## Tier 1 — Biggest remaining win: cache the map's *pixels*, not just its geometry

The audit's central insight: the geometry pipeline is already well-cached (`ProjectedMap`,
viewport, label layouts — see SYNC render-perf rounds 2/3), **but nothing caches the
rasterized pixels**. So under the Concept-C sweep (and in TILT mode) the entire CRT Skia
call-list — `drawProjectedMap` = halo pair ×4 + 4 stroke paths + point batches + ~50 art
circles + endpoint dots — **replays every frame** even though the base map only changes at
the 2Hz GPS cadence. The 2026-06-14 pass removed the *recomposition* cost of this (the
`sweepDeg` draw-scope deferral); the *drawing* cost remains.

The fix family is **GPU layer promotion**: render the static base map into a cached layer
(`Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }` keyed so
it only re-rasterizes on camera change, or an explicit `rememberGraphicsLayer()` +
`drawLayer`), then blit one texture per frame instead of replaying the call list.

| # | Where | What | Risk |
|---|-------|------|------|
| **1.1** | `PlayaMapPanel.mapBaseCanvas` | Promote the base map to a cached layer; re-rasterize only on viewport change. **Dominates 1.2.** | Behavior-preserving **in principle**, but offscreen-compositing the translucent CRT halos changes the blend order (halos composite into the layer, then the layer blends over the background) — **needs a visual sanity check on-device**. |
| **1.2** | `PlayaMapPanel.drawSweptProjectedMap` | The Concept-C "ping": re-blits the **whole** map (incl. full CRT halo + endpoints) clipped to the rotating wedge, every frame. If the lit map is cached once (per `projected` + `litPalette`) and only `clipPath(wedge){ drawLayer }` runs per frame, the per-frame re-issue disappears. | Same offscreen-compositing caveat; antialiasing at stroke edges may differ subtly from a live re-stroke. Validate. |
| **1.3** | `PlayaMapPanel.kt` `clipCircular` (`Modifier.clip(CircleShape)`) | A non-rectangular clip forces a render-to-texture + masked composite for the **entire** stacked subtree (base + swept + ego + arm) on each invalidation. If 1.1 is adopted, apply the circular clip to that single cached layer instead of as an ancestor of the live subtree. | Behavior-preserving (structure). Memory-bandwidth bound. |
| **1.4** | `mapBaseCanvas` TILT branch (`rotationX` graphicsLayer) + `CRTVectorScreen` tilt | In TILT the `graphicsLayer` is used purely for the 3D transform, so it gets the worst of both worlds — an offscreen buffer **and** a full re-record of the map every frame. Same fix: cache the content, transform the cached layer. | Behavior-preserving. Medium impact in TILT only. |
| **1.5** | `PlayaMapPanel.egoOverlayCanvas` (+ `CRTVectorScreen` ego) | The ego marker is a function of `(egoFix, headingDeg, viewRotationDeg, viewport)` — all ≤2Hz — yet its full-screen sibling Canvas re-invalidates at sweep cadence. Promote to its own cached layer, or at minimum keep its draw lambda from capturing `sweepDeg`. Pairs with 1.1. | Behavior-preserving. Low impact. |

**Recommended approach:** prototype 1.1 behind a quick on-device A/B (`dumpsys gfxinfo`
before/after), eyeball the halo blending against the current build, then extend to 1.2–1.5.
Do **not** land blind — this is the one cluster that can shift the tuned CRT look.

---

## Tier 2 — Behavior-preserving (safe; pixel-identical), not yet done

These were verified behavior-preserving but left for a focused pass. None need sign-off.

- **Reuse the wedge / cone `Path` objects** — `PlayaMapPanel.wedgePath` and `drawSweepArm`'s
  forward cone allocate a fresh `Path` + 24-point trig loop **inside the draw lambda every
  frame**. Hold a remembered `Path` and `rewind()` + re-trace it instead of `Path()`. The
  geometry depends on `sweepDeg` so it must re-trace per frame, but the **allocation** can go.
  (Concept C only; low individual impact but it's on the 60fps path.)
- **Reuse the ego-marker `Path`** — `EgoMarkers.drawEgoMarkerAt` / `drawHexEgoMarkerAt`
  allocate a `Path()` per draw. Same pattern. Compose draw is single-threaded, so a
  function-scoped reused `Path` (rewind) is safe.
- **Narrow the Motion Tracker format scope** — `MotionTrackerScreen` reads the whole
  `state` at top scope for the RNG / BEARING / SPEED / RANGE / ZOOM `String.format`s, so the
  `derivedStateOf { MapUiInputs.from(state) }` slice buys nothing for those — they still
  re-run on every 2Hz tick (the 60fps churn is already gone after the `sweepDeg` deferral).
  Hoist the formatted strings into a child composable that takes only the fields it reads so
  smart-skip can drop them.
- **Batch major art** — `BrcMapRenderer.drawMajorArt` issues ~50 hollow `drawCircle`s in DOT
  mode. The per-marker `Stroke` is already hoisted (shipped); the calls themselves could
  batch into a single `Path` of `addOval`s → one `drawPath`. (Hollow stroke prevents
  `drawPoints`; a Path works.)
- **`CockpitUiState` stability** — the data class embeds `PlayaMap` (holds `DoubleArray`s)
  and `NavigationCue`, so the Compose compiler treats it as **unstable**, and full-state
  child composables can't smart-skip even when the reference is unchanged. If the loaded
  `PlayaMap` is genuinely never mutated post-construction (it is — built once at load),
  `@Immutable` on `PlayaMap` (and confirming the rest of `CockpitUiState` is deeply
  immutable) would unlock skipping. **Care:** `@Immutable` is a correctness *promise* —
  getting it wrong causes missed recompositions (stale UI) that unit tests won't catch.
  Verify with Compose compiler metrics (`-Pandroidx.enableComposeCompilerMetrics`) before and
  after, and visually confirm nothing goes stale.
- **`TextMeasurer` hygiene** — each `vectorText` call site does its own
  `rememberTextMeasurer()` (default cache size 8); labels use a separate one. The per-glyph
  `remember(text, style)` already neutralizes re-measure cost, so this is mostly allocation
  hygiene — hoist one app-level `TextMeasurer` (larger `cacheSize`) and thread it down.
  Negligible–low; lowest priority in this tier.

---

## Tier 3 — Visual-tradeoff options (require sign-off — NOT pixel-identical)

Cheaper than the Tier-1 layer cache but they **change the rendered look**, so they need a
deliberate call:

- **`crtBeam = false` on `TrackerLitPalette`** (`MotionTrackerScreen.kt`) — drops the 8 halo
  `drawPath`s + 3 endpoint `drawPoints` from the **lit re-blit** inside the wedge. The wedge
  still brightens features via the lit core strokes, so the M41A ping still reads — but the
  swept wedge loses its doubled phosphor bloom and white corner dots. One-line change, biggest
  cost/effort ratio of the re-blit cuts. The cleaner alternative is the 1.2 layer cache (no
  visual change). Pick one after seeing both on-device.

---

## Investigated and dismissed (do not re-chase)

The adversarial-verify pass refuted these — recorded so they aren't re-investigated:

- **Parked-GPS 2Hz state churn** — *false.* `LocationSourceState.Active` and `GpsFix` are
  data classes, so `MutableStateFlow` already conflates identical fixes through
  `flatMapLatest` to the collector; a stationary vehicle does **not** drive
  `CockpitUiState.copy()`. A `FakeLocationSource` dedup was implemented then **reverted** as a
  no-op (commit `7f6331d` message).
- **Concept-B retro grid** — already path-cached and viewport-keyed; no per-frame rebuild.
- **Projection inner loop** — `projectInline` / `toScreenInline` confirmed allocation-free and
  cached; no boxing. No action.
- **`navCueBar` / `vectorText` per-redraw allocations** — flagged but **not on the hot path**
  once the `sweepDeg` deferral stopped 60fps recomposition; the `remember(text, style)` glyph
  cache and 2Hz cadence make them cheap.
- **InstrumentBay full-state read / throttle-trace `floatArrayOf`** — real allocations but
  **not hot** (Concept D has no animation loop; recomposes at 2Hz, not 60fps).

---

## Measuring on-device

Before/after any of the above, on a real Fire HD 10:

- **Jank percentiles:** `adb shell dumpsys gfxinfo org.pureagave.zodiac.control framestats`
  (and the histogram / "Number Janky frames"). Reset with `dumpsys gfxinfo <pkg> reset`,
  exercise Concept C for ~30s, dump.
- **Recomposition counts:** Android Studio **Layout Inspector → Recomposition counts** while
  the sweep runs — the Motion Tracker header/stat composables should now tick at ~2Hz, not
  60fps (regression check for the shipped deferral; baseline for Tier 2).
- **Compose stability:** build with `-Pandroidx.enableComposeCompilerMetrics=true`
  `-Pandroidx.enableComposeCompilerReports=true` to see which composables are skippable /
  restartable and whether `CockpitUiState` is `stable` (relevant to the Tier-2 `@Immutable`
  item).
- **Frame timing in CI-ish form:** a Macrobenchmark with `FrameTimingMetric` over a scripted
  Concept-C session gives a repeatable P50/P90/P99 frame-duration number.
- **GC pressure:** Android Studio Profiler → Memory, watch allocation rate while Concept C
  runs (Tier-2 allocation items target this).

---

_Source: render audit 2026-06-14 (see `SYNC.md`). Line references drift as files change —
navigate by symbol/function name. Active short-list lives in `tasks/open.md` → Performance._
