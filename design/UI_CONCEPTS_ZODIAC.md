# Zodiac UI Concepts (Aliens/80s Sci‑Fi Inspired)

## App Naming (Android conventions)

- **App name:** `Zodiac Control`
- **Package:** `ai.openclaw.zodiaccontrol`
- **Application ID (proposal):** `ai.openclaw.zodiaccontrol`

(If you want your namespace instead, we can switch to `com.rob.zodiaccontrol`.)

---

## Device Baseline (Amazon Fire tablets, ~last 3 years)

Proposed baseline for V1:
- **minSdk:** 30 (Android 11)
- **targetSdk:** latest stable (currently Android 15 API 35)
- **Orientation:** landscape-first
- **Form factor:** tablet-only UI for first pass

---

## Concept A — "Tactical Grid"

**Mood:** Military command console. Heavy black background, neon cyan grid, amber alerts.

### Palette
- `#050607` near-black
- `#00E5FF` cyan trace
- `#7CFF00` phosphor green
- `#FF9F1C` warning amber
- `#FF2D55` critical red

### Layout
- Left: rotating wireframe vehicle viewport (3D-ish line model)
- Right top: subsystem bars (Power / Drive / Thermal / Comms)
- Right bottom: event log + command soft keys
- Bottom strip: velocity, heading, battery, synthetic status code

### Touch Interactions
- Tap wireframe zones to open details panel
- Pinch/drag to zoom + rotate vehicle model
- Long-press subsystem to open diagnostics drawer

---

## Concept B — "CRT Vector"

**Mood:** 80s vector terminal. Dark with scanline feel and monochrome vector highlights.

### Palette
- `#000000` black
- `#00FF66` vector green
- `#00BFFF` electric blue
- `#FFD166` yellow accents

### Layout
- Center: large wireframe model over perspective grid horizon
- Left rail: clickable system cards with tiny sparklines
- Right rail: mission/status text stream (teletype style)
- Top: clock, mode, environment status

### Touch Interactions
- Swipe left/right on wireframe to rotate in 15° snaps
- Double-tap mode label to change "combat/drive/diagnostic" themes

---

## Concept C — "Dropship Console"

**Mood:** Cinematic ship bridge UI. Angular framing, multi-pane instrumentation.

### Palette
- `#04070C` blue-black
- `#5DE2E7` cool cyan
- `#C7F464` lime indicator
- `#FF6B6B` alert
- `#9D4EDD` auxiliary accent

### Layout
- Main center: wireframe + silhouette overlay + callout tags
- Top-left: faux radar sweep widget
- Bottom-left: propulsion and suspension pseudo-controls
- Right column: stacked telemetry cards with animated edge lines

### Touch Interactions
- Tap callout tags to drill down component cards
- Drag sliders to simulate subsystem changes (visual only for V1)

---

## Vehicle Reference Capture Plan (Judge Dredd Rover Taxi)

1. Gather high-quality front/side/rear images from public references.
2. Build a simplified line-art profile set in SVG.
3. Generate a low-poly/wireframe approximation from traced outlines.
4. Render inside app using Compose Canvas/OpenGL (depends on performance tests).

**V1 shortcut:** start with 2.5D wireframe (stacked SVG layers + transforms), then move to full 3D later.

---

## Recommended Start

Ship **Concept A** first (best signal-to-noise and easiest to make interactive quickly),
then add optional skinning to switch between A/B/C theme packs.
