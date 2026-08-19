# RES-P2-1 — Blind-arc signaling

**A down camera must appear on the HUD as a blind sector, never masquerade as
"all clear."**

Status: SPEC (contract). Author: main session, 2026-08-19. Downstream: a plan
agent, an implementation agent, and a validation agent all treat this file as the
source of truth.

## Problem

The DRIVER surround ring renders threat contacts around the vehicle. A camera that
dies mid-drive stops producing contacts for its bearing arc — and on the wire that
is **identical** to "that arc is clear." The ring cannot tell *blind* from
*clear*, so a failed camera silently reads as a safe sector. For a safety feature
this is the dangerous ambiguity. (Identified as RES-P2-1 in
`docs/AUDIT-2026-08-13-resilience.md`.)

Today only the thermal is wired into zvision, so a camera loss = whole feed
`ABSENT` = visible "NO VISION". The silent-blind-arc problem bites once the
**multi-camera ring** is deployed — a single dead camera hides in plain sight.
This work must be correct for **both** the current single-thermal config and the
future ring.

## Goal

Per bearing sector, the pipeline must distinguish three states and the HUD must
render them unmistakably:

1. **COVERED + clear** — a *live, frame-delivering* camera sees that arc, no contact.
2. **COVERED + contact(s)** — existing behavior.
3. **BLIND** — no live camera currently covers that arc (unconfigured, failed, or stalled).

A camera failure must appear as a **visible blind wedge**, never as clear.

## Requirements

- **R1 — zvision computes blind arcs from *liveness*, not config.** The blind set =
  the full circle minus the union of arcs covered by cameras that are **currently
  delivering frames**. A camera that is configured but *not delivering* (failed,
  unplugged, or stalled in `select()` — the CameraStallGuard's own frame-delivery
  signal) must **not** count as covering its arc. Reuse the existing
  liveness/stall signal rather than inventing a parallel one.

- **R2 — timeliness.** A camera that stops delivering must cause its arc to become
  BLIND within a bounded, tested time — target **≤ ~3 s**, and no longer than the
  CameraStallGuard detection interval. A recovered camera flips its arc back to
  covered within the same bound. State a concrete number and pin it with a test.

- **R3 — wire.** The blind-arc information must reach the tablets. **Preferred
  design: a dedicated, low-rate coverage/health signal separate from the per-frame
  `ZTHREAT` format** — coverage changes slowly (only when a camera dies/recovers),
  and this avoids churning the cross-language `ZTHREAT` contract. **If instead you
  extend `ZTHREAT`:** you MUST regenerate `protocol/threat-protocol-golden.json`
  via the differential tool that produced it (do **not** hand-edit the JSON — see
  CLAUDE.md and `docs/PROTOCOLS.md`), update **both** the Python and Kotlin
  parse/format sides, and keep old-format frames parseable (backward compatible).
  The plan must justify its choice.

- **R4 — composes with `VisionFeed`.** Must not regress the existing
  LIVE/DEMO/ABSENT semantics:
  - `ABSENT` (nothing on the wire) → whole ring blind / "NO VISION" as today.
  - `LIVE`, all arcs covered → normal.
  - `LIVE`, some arcs blind → those arcs render blind, the rest render normally.
  - `demoEnabled=false` on the deployed vehicle stays true; no fabricated data.

- **R5 — tablet renders blind distinctly.** `SurroundRing` must mark blind arcs in
  a way that **cannot be mistaken for clear** (e.g. a hatched or coloured wedge).
  Honor the color system in `ui/concepts/ConceptTheme` — **red is faults/warnings**,
  which a blind safety arc arguably is; amber is banned. The *decision* (which arcs
  are blind, how they map to the ring geometry) must live in
  **`core/vision/SurroundRing` (pure, unit-tested)**, not in the Canvas composable.

- **R6 — honesty preserved.** A blind arc must never be silently dropped or
  coalesced into "clear." The CLAUDE.md rule "an empty-but-live feed is a real
  all-clear" must still hold for **COVERED** arcs, while **BLIND** arcs are
  explicitly flagged.

## Non-goals

- Camera identity / mis-wiring detection (a camera re-plugged into the wrong port
  reporting the wrong bearing) — separate item.
- Tracker-light health / `$ZDMX` — separate.
- Any demo or fabricated contacts.

## Constraints

- **Cross-language discipline:** if the wire format changes, follow the
  golden-corpus regeneration process; both suites read the corpus and must stay
  green.
- **Pure decisions, tested:** logic in `core/` (Kotlin) and zvision's pure modules
  (Python), each with tests; **no new logic in a composable**.
- **Gates green, unscoped.** Android: `./gradlew ktlintCheck detekt lintDebug
  testDebugUnitTest assembleDebug` (unscoped — `:app` **and** `:beacon`). Jetson:
  `cd jetson && python3 -m unittest discover -s tests -t .`. (Build env: set
  `JAVA_HOME` / `ANDROID_HOME` inline.)
- **Tests must be able to fail** (this project's rule): pair every negative
  assertion with a positive control; mutation-check the key decisions.
- **Single-thermal correctness:** with only the thermal (`az=0`, `fov=160`,
  `fovref=d` → covers ~±64° horizontal), the blind set is the rest of the circle,
  and it renders as blind on the HUD (i.e. today's fleet would show a large blind
  rear/side arc, correctly).

## Tests required (minimum)

- **zvision:** blind-arc computation — a stalled/non-delivering camera's arc
  becomes blind while a delivering one's does not; the R2 time bound; the
  single-thermal case (blind = circle minus the thermal arc); the all-cameras-live
  case (no blind arcs); mutation-proved.
- **protocol (only if `ZTHREAT` is extended):** golden corpus regenerated by the
  tool; both Python and Kotlin suites parse it; old frames still parse.
- **app:** `SurroundRing` classifies each ring sector as clear / contact / blind;
  blind ≠ clear ≠ contact; `ABSENT` still whole-ring-blind; the parse of the new
  coverage/health signal; mutation-proved. Composable rendering itself is not
  unit-testable here (no Compose harness) — keep the decision in `core/`.

## Acceptance

A camera going down produces a **visible blind wedge** on the surround ring within
R2's time bound, **distinct from a clear arc**, verified on both the single-thermal
config and a simulated multi-camera config. Both test suites green (app +
beacon unscoped; jetson). Wire contract intact — golden corpus regenerated by the
tool if `ZTHREAT` was touched, untouched otherwise. No regression to `ABSENT`/"NO
VISION" or to the `demoEnabled=false` guarantee.
