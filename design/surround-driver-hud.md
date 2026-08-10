# Surround DRIVER HUD — design (rev 2, post-review)

> **Status 2026-08-10: all phases have landed. This document is now the
> rationale, not a plan.**
>
> | Phase | State | Where it lives |
> |---|---|---|
> | 1 — `SurroundRing` pure logic | ✅ | `core/vision/SurroundRing.kt` |
> | 1.5a — discrete FAR/MID/NEAR bands | ✅ | `Band`, `radiusFraction` (1.00 / 0.66 / 0.34) |
> | 1.5b — angular clustering + hysteresis | ✅ | `CLUSTER_DEG = 15f`, `MAX_BLIPS = 12`, `SWITCH_MARGIN = 0.08f`, `BlipTracker` |
> | 2 — the ring rendering | ✅ | `ui/concepts/SurroundRingCanvas.kt` — split into its own file rather than absorbed into `DriverNightScreen` |
> | 3 — status line + centre banner | ✅ | `SurroundRing.HudStatus` + `statusLineText`; the banner keys off `state.brakeAdvised` as specified |
>
> **Still outstanding: the field check.** Nobody has confirmed a `#00421E` rim and
> small hollow blips are legible at night brightness, through dust, by someone
> actually driving. Until that evening happens, this feature is built but not
> accepted.
>
> Two limitations were documented rather than fixed, as planned — **reversing**
> (the brake/rear logic inverts and there is no gear signal, so it is a spotter
> procedure) and **contact coasting** (a track that drops out on occlusion vanishes
> instantly rather than fading).

Rev 2 folds in a design review (Fable, 2026-08-07) and two decisions from Rob.
Everything below supersedes rev 1.

## Problem

The Jetson puts **full-circle** bearings (`relAzDeg` ∈ ±180) on the fleet bus.
`DriverNightScreen` filters to the forward arc and silently discards the rest.
That filter is load-bearing, not lazy: the forward view places a contact at
`w*0.5 + (relAz/28°)*(w*0.40)`, so a contact astern draws far off-canvas and —
worse — fires `! BRAKE !` for something the driver cannot help by braking.

## Who this is for

A person driving a large, slow, loud art car through a crowd of pedestrians in
the dark. Many are dressed dark, some are intoxicated, and the vehicle is loud
enough that they may not hear it. **The windscreen is the primary instrument.**
This display has exactly two jobs: (a) the aft and blind arcs the driver's eyes
cannot cover, and (b) the machine-computed collision flag. Every element must
be absorbable in a **sub-second glance or in peripheral vision**. Anything that
rewards sustained head-down study is a defect, not a feature.

## Layout decision (Rob, 2026-08-07): hybrid

- **Perspective view (existing):** vanishing-point grid, hollow wireframe
  figures on the ground plane, size = proximity. **Forward arc only.** The
  detail view — where a driver judges one specific person.
- **Surround ring (new):** plan view, **nose-up**, ego at centre, carries
  **every** contact. The awareness view — "something is there", not "here is
  what it looks like".

Forward contacts appear twice, as a figure and as a blip. That redundancy is
the price of keeping the figure work, and it is only legible if the ring marks
*which* arc is duplicated below (see the coverage/wedge rules).

---

# Phase 1 — landed

`SurroundRing`, pure, no Compose. `wrapBearing`, `screenAngleDeg`,
`radiusFraction`, `sectorOf`, `blips`, `brakeAdvised`, `rearAlert`. Rationale
is in the KDoc; do not re-derive. Rev 2 changes several of these — see Phase
1.5.

---

# Phase 1.5 — `SurroundRing` changes (pure, tested, no pixels)

**Commit this on its own, before any rendering.**

## 1.5a Radial encoding → discrete bands *(Rob's decision)*

`radiusFraction = 1 - size` is misleading. Radius on a plan view is read
**metrically**, and apparent thermal size cannot support that: a child at 5 m
and an adult at 12 m have the same apparent size, someone prone or on a bike
does not scale the same way, and apparent size goes as ~1/r so a linear mapping
distorts closure rate into apparent acceleration.

Replace with three ordinal bands. Ordinal reads as "nearer than that one,
roughly" and stops the metric reading cold.

```kotlin
enum class Band { FAR, MID, NEAR }
fun bandOf(size: Float): Band          // <0.33 FAR, <0.66 MID, else NEAR
fun radiusFraction(size: Float): Float // FAR 1.00, MID 0.66, NEAR 0.34
```

`0.34` also clears the reticle arms (~0.15 R) with room, which the old `0.18`
floor did not. Keep the existing contract that radius never grows as a contact
closes; keep the non-finite/out-of-range coercion.

State plainly in the KDoc: **radius is sensor prominence, not distance.**
Intended end state (not this pass): with a known camera mounting height, the
elevation of a track's ground-contact point yields real range — a Jetson change
plus a nullable `rangeM` on `ThreatProtocol`.

## 1.5b Angular clustering

Thirty dots tell a driver nothing. Merge contacts that share a `Band` and sit
within `CLUSTER_DEG = 15f` of each other into one mark, so "crowd on the port
bow" renders as *one legible thing*.

```kotlin
data class Blip(
    val threat: DriverThreat,   // the representative (nearest of the group)
    val screenAngleDeg: Float,  // group mean bearing, computed circularly
    val radiusFraction: Float,
    val memberCount: Int,       // 1 = a lone contact
    val collision: Boolean,
)
```

- **Collision contacts are never merged** — each stays individually drawn. The
  existing test guaranteeing a collision survives the cap must keep passing.
- Mean bearing must be computed **circularly** (mean of unit vectors), or a
  group straddling ±180 averages to dead ahead. This is a real case: a crowd
  behind the vehicle.
- `memberCount > 1` renders heavier (see 2c), never as a printed number — text
  is head-down reading.
- Keep `MAX_BLIPS = 12` as a backstop. With clustering it should rarely bind.

## 1.5c Membership hysteresis

`blips()` re-sorts every frame, so with noisy `size` estimates the cap boundary
flickers and marks pop in and out — more attention-grabbing than any real
signal. `DriverThreat.id` is a stable track id and is currently unused here.

Make `blips` a method on a small stateful holder (or take a previous-selection
set) so an incumbent must fall below a challenger by `SWITCH_MARGIN = 0.08f`
before being displaced. Ad-hoc `id = 0` contacts never latch — they have no
stable identity. Mirror `tracker.py`'s `switch_margin`, which solves the same
problem for the DMX head.

## 1.5d Speed-gated braking

People **deliberately walk up to art cars** — that is what art cars are for.
Every one of them is a constant-bearing, looming track. `brakeAdvised` will
latch continuously while parked or boarding, and the driver will have fully
tuned it out by the time it matters at 8 km/h on open playa.

```kotlin
const val BRAKE_MIN_KPH: Float = 5f   // above a brisk walk
fun brakeAdvised(threats: List<DriverThreat>, speedKph: Float): Boolean
```

Below `BRAKE_MIN_KPH` the imperative goes quiet — **contacts still draw, and
collision blips still render red.** Only the instruction is suppressed, because
below a walking pace the driver can stop trivially and the alert costs more
than it buys. `speedKph` is already in scope at `DriverNightScreen.kt:105`.

Known boundary, do not try to fix here: a person walking parallel to the car at
matched speed abeam is a textbook constant-bearing track and will flag. That
belongs Jetson-side.

## 1.5e Vision feed state

`ThreatSource.feedAlive` exists (`data/vision/ThreatSource.kt:25`) and its KDoc
calls the distinction safety-critical — and it stops at the data layer.
`CockpitViewModel:222` collects only `threatsFlow`. So with `demoEnabled=false`
(the deployed-vehicle config) a crashed Jetson or dropped WiFi renders as
`0 CONTACTS CLEAR`, in green. The driver has spent a week learning the ring
shows people behind the car; the night the connector vibrates loose, it says
clear and they reverse.

Tri-state, because "showing demo data" is neither live nor absent:

```kotlin
enum class VisionFeed { LIVE, DEMO, ABSENT }
```

- `RoutedThreatSource` gains `feedState: StateFlow<VisionFeed>` derived from
  `network.feedAlive` + `demoEnabled` (alive → LIVE; else demoEnabled → DEMO;
  else ABSENT). Leave the existing `feedAlive` on the interface untouched.
- `CockpitViewModel` takes a `visionFeedFlow: StateFlow<VisionFeed>` parameter
  defaulting to `MutableStateFlow(VisionFeed.ABSENT)` — a ViewModel with no
  threat source genuinely has no vision — collects it into
  `CockpitUiState.visionFeed`.
- Wire the real flow in `ZodiacApplication`.

**The word CLEAR may only appear when `visionFeed == LIVE`.**

## 1.5f Coverage arcs *(app-side this pass, per Rob)*

The rig is **one 160° thermal**, not a closed ring (`jetson/HARDWARE.md:15`).
zvision already computes blind sectors and `ThreatProtocol` puts none of it on
the wire. A complete rim with an empty rear quadrant reads "nothing behind you"
when the truth is "nothing *watching* behind you".

```kotlin
// Bearings the sensor rig actually watches, as (from, to) inclusive in ±180.
// MUST be kept in step with the Jetson's --camera rig spec; there is no wire
// field for this yet, so this constant is the only thing keeping the ring
// honest. Today: one 160° UW thermal facing forward.
val COVERED_ARCS: List<ClosedFloatingPointRange<Float>> = listOf(-80f..80f)
fun isCovered(relAzDeg: Float): Boolean
```

An uncovered sector must **never** render identically to a watched-and-clear
one. Add a `TODO` naming the protocol field that will replace this.

## 1.5g Tests

Everything above is pure and must be unit-tested to the standard already set in
`SurroundRingTest` — including the circular mean across ±180, the band
boundaries, hysteresis not flickering across frames, speed gating at the
threshold, and `isCovered` at the arc edges. Mutation-check each new rule.

---

# Phase 2 — render

## 2a Geometry (numbers, not guidance)

Target is the A54 in landscape, ~2340×1080.

- Centre `c = Offset(w*0.5f, h*0.58f)` — the existing reticle position, so the
  ring is concentric with the sight the driver already uses. One glance target.
- **`R = h * 0.30f`.** Rev 1 said `0.42f * min(w,h)` = 454 px, which puts the
  rim flush to the screen bottom under both text blocks. `0.30f * h` = 324 px;
  ring spans y 0.28h…0.88h, clear of the destination name (top) and the
  MPH/status line (bottom ~0.90h).
- Blip position `c + R * radiusFraction * (cos θ, sin θ)`, `θ = screenAngleDeg`.

## 2b Z-order (this is the answer to ring/figure overlap)

Rear blips land near the bottom of the ring, which is exactly where near
forward figures' feet are drawn (`feetY` lerps to `0.90h`,
`DriverNightScreen.kt:237`). Resolve by paint order, not by moving things:

1. perspective grid
2. ring furniture (rim, ticks, coverage, wedge, hull)
3. non-collision blips
4. wireframe figures
5. **collision blips** — the only thing allowed to punch through a figure
6. reticle, text

A figure occluding its own blip is correct: the figure *is* the detail view for
that contact. A collision mark is never occluded.

## 2c What the ring is made of

1. **Rim** — 1 px `NightGrid`, drawn only over `COVERED_ARCS`. Uncovered arcs
   are drawn as a **dotted** `NightGrid` line at ~40 % of the tick spacing, so
   "not watched" is visibly different from "watched and clear" without adding
   brightness. `VisionFeed.ABSENT` → whole rim dotted `NightRed`.
   `VisionFeed.DEMO` → whole rim dotted `NightGrid` (see 2e).
2. **Ticks** — every 30°, `NightGrid`, inward from the rim; longer at
   0/90/180/270.
3. **Forward wedge** — two faint `NightGrid` radials at
   **±`PERSPECTIVE_ARC_DEG`**, marking the arc that is *also* drawn as figures
   below. **Rev 1 had this wrong at ±90°**, which is (a) degenerate — two
   radials at ±90° from nose-up are just the horizontal diameter, reading as
   crosshair furniture — and (b) a lie, because the perspective view goes off
   canvas at ~±35° (`THERMAL_HALF_FOV_DEG = 28f`, `DriverNightScreen.kt:235`).
   See 3a.
4. **Hull** — replace the point/triangle ego with a dim `NightGreen` rounded
   rectangle roughly in the vehicle's footprint proportions, longer than wide,
   nose up, about `0.12R` wide. The question near the hull is "is that person
   clear of my *side*", not "clear of my centroid", and a hull outline gives
   `Band.NEAR` a meaning.
5. **Blips** — hollow circle, radius `h*0.012f`, stroke 2 px, `NightGreen`, or
   `NightRed` when `collision`. `memberCount > 1` → stroke 3 px and radius
   ×1.4. Collision blips additionally get a **radial spoke of length `0.08R`
   drawn outward** (toward the rim) — i.e. pointing back along the bearing the
   threat is coming *from*, which is deliberately the opposite of a velocity
   vector, so state it in the code comment. The spoke exists so a closing
   contact is findable by **shape**, not only by colour: this display is dim by
   design and red-on-black at low brightness is not a reliable sole channel.

## 2d Night discipline (non-negotiable)

- Static furniture is `NightGrid` (`#00421E`) **only** — never `NightGreen`.
- No fills except the hull outline and collision blip interiors.
- Nothing new in white, yellow, or amber. Palette stays green / red / purple.
- The ring must barely raise the steady-state lit area; blips carry the signal.

## 2e Ring is always on

Do not hide the ring when there are zero contacts. An alarm-only ring is a ring
the driver has never learned to read, and — more importantly — the ring's
steady presence is the only place sensor health can live. Ring solid =
watching. Ring dotted = blind or demo. An empty healthy ring **is** information.

## 2f Burn-in

Static geometry in a fixed position on an OLED is exactly what `burnin/` exists
for. The ring sits inside `burnInScaffold` and inherits pixel-shift. Do **not**
add an independent animation.

---

# Phase 3 — integrate

## 3a Fix the forward filter while you are here

Introduce `PERSPECTIVE_ARC_DEG = 30f` (just inside where the `28f` mapping
leaves the canvas). Then:

- figures ← `state.threats.filter { abs(wrapBearing(it.relAzDeg)) <= PERSPECTIVE_ARC_DEG }`
- ring ← `state.threats` (all of them)
- delete `HUD_FORWARD_ARC_DEG`

Today a contact at 50° passes the ±90° filter, is counted, can fire `! BRAKE !`,
and is drawn **off-screen** — an invisible alarm source. The wedge must use the
same constant, or it promises a figure that is not there.

Expected and acceptable: a forward contact's ring angle is its true bearing but
its figure x uses the 28° magnification, so at az 20° the figure is at 79 %
screen width while the blip is 20° off vertical. The two views disagree about
"how far left". Do not "fix" this ad hoc — it needs the perspective mapping
redone, which is not this pass.

## 3b Status line

Currently `if (collisionAny) "! BRAKE !" else "${threats.size} CONTACTS   CLEAR"`
over the *filtered* list. Replace, evaluated over **all** threats:

| condition | text | colour |
|---|---|---|
| `visionFeed == ABSENT` | `NO VISION` | `NightRed` |
| `brakeAdvised(all, speedKph)` | `! BRAKE !   ${all.size} CONTACTS` | `NightRed` |
| `rearAlert(all)` | `! CHECK REAR !   ${all.size} CONTACTS` | `NightRed` |
| `visionFeed == DEMO` | `${all.size} CONTACTS   DEMO` | `NightGreen` |
| else | `${all.size} CONTACTS   CLEAR` | `NightGreen` |

- The count must never disappear during an alert — losing it at the busiest
  moment is backwards, and it is what keeps `MAX_BLIPS`/clustering honest.
- `! CHECK REAR !`, not `! REAR !`: an imperative, matching `! BRAKE !`. A bare
  noun reads as a fragment at 3am.
- A simultaneous forward + rear collision shows `! BRAKE !` — braking is the
  more urgent instruction.

## 3c Centre banner

`! COLLISION COURSE !` (`drawDriverHud`, `DriverNightScreen.kt:168`) keys off
`threats.any { it.collision }`. Change to `brakeAdvised(all, speedKph)`.

The banner is the **primary** alert channel — it is big and preattentive. The
bottom-left status line is secondary; 20 sp in a screen corner is a foveal read
that costs head-down time. A rear collision gets the status line and **no**
centre banner: a centre-screen flash for something astern is precisely the
mistrust-training problem.

---

# Known limitations to document in code, not fix

- **Reversing.** Art cars back up. In reverse the entire brake/rear logic
  inverts and there is no gear signal. State the limitation in the
  `SurroundRing` KDoc so it becomes a spotter-procedure item rather than a
  surprise.
- **Contact coasting.** Thermal tracks drop out on occlusion. A person who
  vanishes at 3 m currently vanishes from the ring instantly. Fading a blip
  over ~1–2 s on `id` continuity would degrade gracefully. Not this pass — note
  it.

# Acceptance

- CI gates: `./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug`.
- detekt has `MagicNumber`/`LongMethod` off but `NestedBlockDepth` live —
  extract helpers rather than nesting.
- **No new logic in the composable.** Every decision lives in `SurroundRing`
  with tests. If something is missing, add it there — do not inline it into
  draw code.
- Phases 1.5 / 2 / 3 are separate commits, each leaving the app runnable.
- **Field check before this is called done:** nobody has verified a `#00421E`
  rim and 13 px hollow blips are legible at night brightness on the A54,
  through dust, by someone driving. One evening on-device at real brightness.
