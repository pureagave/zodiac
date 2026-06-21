# OLED Burn-In Mitigation

Protects the Galaxy Tab S9+ OLED dashboard from burn-in over multi-day playa
deployments, where the cockpit runs for hours/days with mostly static layouts
(rails, gauge frames, scanline overlay, tracker outline, labels). Shipped
2026-06-20 (`burnin/`); see `SYNC.md` for the dated decision log.

## Why OLED-specific

Burn-in is an OLED phenomenon. The fleet is mixed: the **Fire HD 10 is LCD**
(no burn-in, and the slowest GPU), the **S9+ is OLED** (the dashboard, and a
fast Snapdragon 8 Gen 2). So the *visual* mitigation that costs GPU is gated to
OLED; the cheap parts run everywhere.

## Architecture

- **`BurnInMitigationManager`** (process-lifetime, in `ZodiacApplication`'s
  manual DI) — owns the discrete phase machine and live `BurnInConfig`. Pure
  logic on an injectable `clock` (`SystemClock.elapsedRealtime`), so it's fully
  unit-testable with a fake clock and no real delays. Depends only on the
  location/connection `StateFlow`s (read-only) + a narrow `BurnInConfigStore`
  persistence port — the cockpit ViewModel is untouched.
- **`burnInScaffold`** (Compose) — wraps the whole `cockpitScreen` dispatch from
  a single node, so mitigation cascades to all four concepts + overlays.
  Translates phase + config into the actual offset / brightness / window
  backlight. Animation frame ticker is read only inside the
  `offset`/`graphicsLayer` lambdas (layout/draw phase), never the composable
  body — matching the Concept-C recomposition-storm avoidance.
- **`BurnInDeviceProfile`** — the OLED gate (`Build.MANUFACTURER != "Amazon"`).
- **`standbyScreen`**, **`burnInTuningPanel`** — the deep-idle CRT screen and the
  hidden tuning panel.

## State model

| Phase | Enters on | Window backlight | What's drawn |
|---|---|---|---|
| `ACTIVE` | any activity | system | dashboard; pixel-shift + breathe (±4% / 20s) |
| `DIM` | 5 min idle | ~0.40 | dashboard @ content α0.30, breathe frozen |
| `DEEP_IDLE` | 30 min idle | ~0.15 | `standbyScreen` — phosphor "STANDBY" + drifting scan line |
| `SLEEP` | 60 min idle | ~min | app-drawn pure black (OLED pixels off) |

Wake (→ `ACTIVE`) on touch, **real** GPS movement, or a vehicle-link phase
change. `FLAG_KEEP_SCREEN_ON` is held in every phase (no `WAKE_LOCK`); "sleep"
is app-drawn black with the Activity still foreground, so wake-on-touch is
instant and there's no lockscreen to fight — the correct model for a mounted
dashboard.

## Mitigation techniques

- **Pixel-shift** — whole-UI `offset{}` tracing a slow Lissajous path (default
  ±2px / 45s). Placement phase only (no recomposition). Universal (cheap).
- **Brightness breathe / dim** — one global `graphicsLayer` alpha
  (`CompositingStrategy.ModulateAlpha` — cheap, no offscreen composite,
  blend-preserving). Chosen over per-element alpha threading (~40 static call
  sites across four concepts) for the same wear-distribution benefit with far
  less surface area. **OLED-gated.**
- **Idle escalation** — DIM → STANDBY → black, plus backlight stepping (applied
  on every device, including the Fire, for power).

## Key decisions (2026-06-20)

1. **Global breathing, not per-element.** Same wear benefit, one attach point.
2. **OLED-gated visual layer.** Pixel-shift universal; breathe/dim skips the LCD
   Fire (can't burn in, slowest GPU).
3. **Sleep = app-drawn black + min backlight**, not real device display-off —
   instant wake, no `WAKE_LOCK`, no lockscreen.
4. **Hidden tuning panel + preferences-backed config** so every timeout /
   parameter is adjustable on the playa without a rebuild.

## Idle-detection thresholds

A parked car (or a real receiver's GPS jitter) must **not** count as activity,
or the dashboard never idles. Movement = ground speed ≥ 1 kph **or** cumulative
displacement > 3 m from the reference fix (catches slow drift below the speed
threshold). The synthetic `FakeLocationSource` is stationary when parked, so it
idles correctly.

## Config

`BurnInConfig` carries every tunable (shift amp/period, breathe amp/period, the
three timeouts, dim/backlight levels, movement thresholds) and **coerces
itself** (`coerced()`: timeouts forced strictly increasing, amplitudes/alphas
clamped), so a tampered or stale persisted value can never seed a bad config —
mirroring how `DataStoreCockpitPreferences` clamps zoom/tilt on read. Persisted
as individual DataStore keys via `readBurnInConfig`/`setBurnInConfig`.

## Deferred (Phase 5, optional)

Per-region burn-in **stress-accounting ledger** — log cumulative lit-time per
screen region to a file to identify highest-risk regions and rotate UI between
burns. Pairs with the M10 Timber logging task; not built. See `tasks/open.md`.

## Verifying on device

Burn-in itself isn't observable in a session. To exercise the state machine,
temporarily shrink the timeouts in `BurnInConfig` defaults (e.g. 2 / 4 / 12 s)
and `DEFAULT_TICK_MILLIS` to `1_000`, deploy, and screencap at intervals —
revert before committing. Reliable long-press over adb needs a *continuous*
`input swipe x y x+2 y+2 900` (one stream, tiny move within slop); separate
`motionevent` calls or a zero-distance swipe register as a tap.
