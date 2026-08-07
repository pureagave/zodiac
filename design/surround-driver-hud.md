# Surround DRIVER HUD — render design

Status: **Phase 1 landed** (`core/vision/SurroundRing.kt`, 26 tests, commit
`818e61e`). This document specifies Phase 2 (render) and Phase 3 (integrate).

## Problem

The Jetson edge box fuses a ring of cameras and puts **full-circle** bearings
(`relAzDeg` ∈ ±180) on the fleet bus. `DriverNightScreen` draws only the
forward arc — `state.threats.filter { abs(it.relAzDeg) <= HUD_FORWARD_ARC_DEG }`
with `HUD_FORWARD_ARC_DEG = 90f`. Everything behind the vehicle rides the bus
and is silently discarded. That filter is not laziness; it is load-bearing,
because the forward view places a contact at `w*0.5 + (relAz/28°)*(w*0.40)`,
which draws a contact astern far off-canvas and — worse — would fire
`! BRAKE !` for something the driver cannot help by braking.

## Decision (user, 2026-08-07): hybrid

Two projections on one screen.

- **Perspective view (existing, unchanged in kind):** vanishing-point grid,
  hollow wireframe figures standing on the ground plane, size = proximity.
  Keeps the **forward arc only** — it is the detail view, where a driver looks
  to judge one specific person.
- **Surround ring (new):** plan view, **nose-up**, ego at centre. Carries
  **every** contact including rear. It is the awareness view — "something is
  there", not "here is what it looks like".

A forward contact therefore appears **twice**: as a figure in the middle and as
a blip on the ring. That redundancy is intentional and is the price of keeping
the figure work; the ring's job is that the driver's eye can find *any* contact
in one place regardless of bearing.

Rejected: ring-replaces-perspective (retires the figure/ground-plane work and
converges on the existing RADAR concept), and corner-inset ring (gives rear
contacts the fewest pixels, which is backwards).

## Phase 1 — done

`SurroundRing` (object, pure, no Compose import):

| member | contract |
|---|---|
| `wrapBearing(az)` | folds to (−180, 180]; dead astern resolves to **+180**; non-finite → 0 |
| `screenAngleDeg(az)` | canvas angle (0° = right, 90° = **down**), nose-up: `wrapBearing(az) − 90` |
| `radiusFraction(size)` | `1 − size`, floored at `MIN_RADIUS_FRACTION = 0.18` |
| `sectorOf(az)` | `FORWARD` ≤60°, `SIDE` ≤120°, `REAR` beyond |
| `blips(threats, max)` | capped at `MAX_BLIPS = 12`, returned in **draw order** (least urgent first) |
| `brakeAdvised(threats)` | collision **and not** `REAR` |
| `rearAlert(threats)` | collision **and** `REAR` |

Why each non-obvious choice exists is in the KDoc; do not re-derive.

## Phase 2 — render

### Geometry on canvas

- Ring centre `c = Offset(w*0.5f, h*0.58f)` — the existing reticle position, so
  the ring is concentric with the sight the driver already uses.
- Ring radius `R = min(w, h) * 0.42f`, clamped so the ring never runs under the
  bottom-corner text (`MPH`, contact count) or the top-centre destination name.
- Blip position `c + R * radiusFraction * (cos θ, sin θ)`, `θ = screenAngleDeg`.

### What the ring is made of

1. **Rim** — 1 px `NightGrid` circle. Dim; it is a reference, not information.
2. **Tick marks** — every 30°, 2 px `NightGrid`, drawn inward from the rim.
   Longer tick at 0/90/180/270.
3. **Forward-arc wedge** — the arc the perspective view covers
   (±`HUD_FORWARD_ARC_DEG`) drawn as two faint radial lines in `NightGrid`, so
   the driver can see *which* part of the ring is also being shown in detail
   below. This is what makes the double-drawing legible rather than confusing.
4. **Ego mark** — small filled `NightGreen` triangle at `c`, pointing up.
5. **Blips** — see below.

### Blip appearance

Deliberately **not** wireframe figures — at ring scale they would be a few
pixels of noise, and the figure is the perspective view's job.

- Base: hollow circle, radius `h*0.012f`, stroke 2 px.
- Colour: `NightGreen`; `NightRed` if `collision`.
- Collision blips additionally get a short radial "spoke" from the blip toward
  the rim, so a closing contact is findable by shape and not only by colour
  (the display is dim by design and red-on-black at low brightness is not a
  reliable sole channel).
- Rear-sector blips: no special treatment on the ring itself — position already
  says it.

### Night discipline (non-negotiable)

This screen exists to preserve the driver's dark adaptation. The ring adds lit
pixels to a mostly-black display, so:

- Rim and ticks in `NightGrid` (`#00421E`) only — the dimmest colour in the
  palette. Never `NightGreen` for the static furniture.
- No fills except the ego triangle and (if used) blip interiors on collision.
- Nothing new in white, yellow, or amber. Palette stays green / red / purple.
- The ring must not increase the *steady-state* lit area much; static furniture
  should read as barely-there and blips should carry the signal.

### Burn-in

The ring is static geometry in a fixed screen position on an OLED — exactly the
burn-in risk `burnin/` exists for. It sits inside `burnInScaffold` and inherits
pixel-shift, so no new mechanism is needed. Do **not** add an independent
animation.

## Phase 3 — integrate

1. **Delete `HUD_FORWARD_ARC_DEG` filtering at the top of `driverNightScreen`.**
   `state.threats` becomes the full list. Instead:
   - `val forward = state.threats.filter { abs(it.relAzDeg) <= HUD_FORWARD_ARC_DEG }` → fed to the perspective figures **only**.
   - `state.threats` (all) → fed to the ring.
2. **Bottom-left status line** currently reads
   `if (collisionAny) "! BRAKE !" else "${threats.size} CONTACTS   CLEAR"`,
   where `collisionAny = threats.any { it.collision }` over the *filtered* list.
   Replace with:
   - `SurroundRing.brakeAdvised(state.threats)` → `"! BRAKE !"` in `NightRed`
   - else `SurroundRing.rearAlert(state.threats)` → `"! REAR !"` in `NightRed`
   - else `"${state.threats.size} CONTACTS   CLEAR"` in `NightGreen`
   The count must be over **all** contacts — the ring's `MAX_BLIPS` cap drops
   marks, not contacts, and the count is what keeps the cap honest.
3. **`! COLLISION COURSE !`** banner (`drawDriverHud`) currently keys off
   `threats.any { it.collision }`. Same treatment — `brakeAdvised` for the
   existing banner; a rear collision gets the `! REAR !` status line and no
   centre banner (a centre-screen flash for something astern is exactly the
   mistrust-training problem).

## Constraints for the implementer

- **No new dependencies.** Canvas + the existing helpers in the file
  (`hudText`, `boxedLabel`, `archPt`, `drawLine`, `lerp`).
- **No new logic in the composable.** Every decision — placement, ordering,
  capping, sector, brake — already exists in `SurroundRing` and is tested. If
  something is missing, add it to `SurroundRing` *with tests*, do not inline it
  into the draw code.
- **Do not modify `SurroundRing`'s existing contracts** without updating
  `SurroundRingTest`; its 26 tests are mutation-verified.
- CI gates: `./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug`.
- detekt has `MagicNumber`/`LongMethod` disabled but `NestedBlockDepth` is live —
  extract helpers rather than nesting.
- Phase 2 and Phase 3 are separate commits, each leaving the app runnable.

## Open questions for review

1. Is `R = 0.42 * min(w,h)` concentric with the reticle actually readable, or
   does the ring collide visually with the perspective grid's horizon lines?
2. Should the ring fade/hide entirely when there are **zero** contacts, to save
   OLED pixels and reduce clutter? Argument against: a ring that appears only
   when something is wrong is a ring the driver has not learned to read.
3. Is a hollow circle the right blip, or should near-vs-far swap shape the way
   the perspective figures swap bust↔walking at `NEAR_SHAPE_THRESHOLD`?
4. `MAX_BLIPS = 12` — plucked from "a ring of forty is noise". Is there a better
   basis?
