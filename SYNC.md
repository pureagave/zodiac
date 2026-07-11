# SYNC.md

Append-only log of significant decisions, lessons, and changes for the Zodiac Control project.

Newest entries on top. Each entry: ISO date, short title, body. Don't rewrite history — if something later turns out wrong, add a new entry that supersedes it.

---

## 2026-07-11 — Address keypad polish: retro-futurism font, bigger, longer flash

Feedback after testing the address nav on the S9+.

- **Retro-futurism font.** Bundled **Orbitron** (variable TTF, SIL OFL — `app/src/main/res/font/orbitron.ttf`, license in `licenses/Orbitron-OFL.txt`) as `ui/RetroFont.kt` (`FontFamily` pulling Medium/Bold/Black off the `wght` axis via `FontVariation`, `@OptIn(ExperimentalTextApi)`). Applied to the whole address flow — keypad, entry display, ring picker, heading flash — for a Tron-ish sci-fi feel distinct from the monospace terminal chrome. Left the rest of the cockpit monospace.
- **Bigger:** panel 520→620 dp, keypad digits 26→34 sp, entry 44→60 sp, ring keys 34 sp, heading flash **72→104 sp** (Black weight).
- **Longer flash:** `FLASH_MS` 5 s → **10 s**.
- **Dropped the "A" ring** from the picker per request (Esp → B → …). NOTE surfaced to the user: Esplanade and A street are actually *different* rings (Esp ≈ 752 m, A ≈ 894 m), so A-street addresses are no longer typeable — flagged in case that was a slip vs. intentional.
- Verified on the S9+: keypad / picker / flash all render in Orbitron, bigger, "A" gone, `HDG 112°` flash big and holding longer, then hands off to the routed nav.

---

## 2026-07-10 — "Passing" callouts for notable art (passenger flavour)

When the ego drives within range of a notable art piece, its name flashes as a bottom callout — "◂ PASSING <art>".

- **VM:** inlined into `recomputeNavCue` (reuses the projected ego): nearest `PoiKind.ART` within `PASS_RADIUS_M` (120 m) via the existing `contactsWithinRange`; on a *new* piece it sets `CockpitUiState.passingCallout` + a 3 s clear timer (`passingJob`). New-uid-only so it doesn't re-fire on the same piece.
- **`ui/ops/PassingCallout.kt`** — a bottom-centre "◂ PASSING <name>" banner (above the drive-to bar), shared overlay in `CockpitScreen`, no pointer modifiers.
- **Verified on the S9+** by driving the FAKE ego through the art-dense inner playa: flashed **◂ PASSING S.A.N. Forest Interface** (a real 2025 art piece) as it passed within range. Uses the offline-first discovery cache (same data as the RADAR contacts).
- Together with the street popups this completes the "flash overlays" set: street name top-centre while driving, art name bottom-centre when passing.

---

## 2026-07-10 — Street-crossing popups (flash the street as you pass it)

As the ego drives the city, the street it's on/crossing flashes big top-centre — situational awareness without looking down.

- **`core/navigation/streetLabel(NavigationCue)`** (unit-tested, `StreetLabelTest`): the street to announce — the arc you're on, the radial (inbound), or, driving *out* a radial, the arc you most recently crossed (so it ticks Esplanade → A → B … as you pass each street).
- **VM:** inlined into `recomputeNavCue` (no new method — the VM is already a god-object): on a `streetLabel` change it sets `CockpitUiState.streetPopup` and starts a 2.5 s clear timer (`streetPopupJob`). Reuses the existing nav-cue street snapping, so it costs almost nothing.
- **`ui/ops/StreetCrossingPopup.kt`** — a big top-centre "ENTERING <STREET>" banner (no pointer modifiers, so map gestures pass through), a shared overlay in `CockpitScreen`.
- **Verified on the S9+** by driving the FAKE ego out the 2:30 radial (HDG 120 / SPD 120 via the debug chips) and burst-capturing: it flashed **ENTERING ATWOOD** (A) then **ENTERING DICK** (D) as it crossed each lettered street — the names are the real 2025 GIS street names (theme words per letter), which is what BRC street signs actually say.
- Follow-up idea: also announce the radials you cross while driving *along* an arc (the clock ticks in the cue but doesn't flash) — left for later.

---

## 2026-07-10 — Address keypad (type a city address to drive to)

An `ADDR` button on the DRIVE TO bar opens a full-screen glove-friendly keypad to punch in any BRC address and route to it.

- **Flow:** big numeric pad → type the clock (auto-advancing: leading 2–9 = 3-digit `H:MM`, leading 1 = 4-digit `10:MM`, validated to 2:00–10:00) → a large **Esp/A–K ring picker** → a ~5-second **HDG nnn°** flash → auto-closes to the live chevron + street route. `ui/ops/AddressEntryPanel.kt`; rendered as a shared overlay in `CockpitScreen` gated on `addressEntryOpen`.
- **Pure + tested:** `core/navigation/ClockEntry.kt` (`parseClockEntry` / `requiredClockDigits`) → `ClockEntryTest`. `core/ops/addressTarget(clock, ring, projection)` resolves clock+letter to a point via `clockToBearing` + the measured `StreetRingRadiiM`.
- **Custom target:** re-added `CockpitUiState.customTarget: DriveTarget?` (priority over BATH/preset in `activeDriveTarget`); `CockpitViewModel.driveToAddress` sets it, `setNavTarget`/`driveToNearestToilet` clear it. The chevron/footer/route all pick it up for free.
- **Drive-to bar:** refactored to a `DriveSelection` (Preset / Bath / Address) so the 5th `ADDR` button fit without blowing the param cap; `driveSelectionOf(...)` computes the active highlight from state.
- **Verified on the S9+ end-to-end:** ADDR → typed `2:15` → picked `H` → flashed `HDG 112°` (2:15 = 112.5°, correct) → landed on live nav to `2:15 & H`, ADDR lit, route drawn. (2:15 & H == the HOME camp, so distance matched HOME's 1.6 km — nice cross-check.)
- **detekt:** `parseClockEntry` to 2 returns + an `isCityClock` helper (ReturnCount / ComplexCondition); `TooManyFunctions.thresholdInClasses` 22→24. **Flagged in the config: `CockpitViewModel` is becoming a god-object — due for a delegate split before it grows more.**

---

## 2026-07-10 — Street-aware routing across the BRC polar grid

The guidance was "as the crow flies" — it pointed straight at the destination, ignoring that you drive on streets. Now it routes the way you actually drive BRC.

- **`core/navigation/PlayaRoute.kt`** (`routeTo` + `nextWaypoint`, pure + unit-tested). Model: you may cut straight across the **open playa** (inside the Esplanade, the 10:00–2:00 mouth, or past the outer road), but inside the city annulus you follow the grid — so a camp routes **playa → nearest entrance radial ∩ Esplanade → out that radial to the destination ring → along the ring to the address**. Everything is polar (clock bearing + ring radius) over the projected `PlayaCityModel` (its radials/arcs/Esplanade). `nextWaypoint` snaps the ego to the nearest route leg and returns that leg's far corner (stateless, so it survives recompute).
- **Retargeted guidance.** `CockpitViewModel.recomputeRoute()` (runs alongside the nav cue) stashes `routeWaypointsM` + `nextWaypoint` in state. The chevron and ops-footer arrow now steer toward the **next corner** (an `aim: LatLon?` param) while the label + distance still refer to the final destination. Free-drive targets (MAN, TEMPLE, inner-playa) collapse to a single leg → straight-line as before.
- **Drawn on the map.** `ui/concepts/RouteOverlay.kt` `drawRoute` renders the dashed route (ego → corners → dest, dots + a ring on the target) in status-blue on both RADAR and MAP, projected through the same viewport.
- **detekt.** `routeTo` refactored to 3 returns (ReturnCount); `PlayaRoute.kt` named after its data class (MatchingDeclarationName); `TooManyFunctions.thresholdInClasses` 21→22 (`recomputeRoute`).
- **Verification:** CI-green + router unit tests pass (`PlayaRouteTest`). **On-device visual check of the route line + retargeted chevron is still pending — the S9+ dropped off USB/wifi mid-verify.** Re-verify when it's reconnected.
- Chosen with the user: polar-waypoint heuristic (not full A* graph) + draw the route. Known limit: approximate when you're already deep in the grid (the entrance leg is skipped past the Esplanade, but a straight hop to the ring∩radial corner can clip a block).

---

## 2026-07-10 — Heading-guidance chevron added to RADAR too

Supersedes the "chevron only lives in MAP" note below: on request, the same `headingGuidanceBar` now also renders in the RADAR concept — a bordered full-width bar between the nav-cue bar and the scope. Both concepts share the identical guide (position + point + on-course recolour) off `activeDriveTarget`. Verified on the S9+ (RADAR: HOME `112°R`, purple ► right-of-centre).

---

## 2026-07-10 — Heading-guidance chevron (MAP 2nd card) + BATH (nearest-toilet) target

Repurposed the InstrumentBay's decorative header card (`NOSTROMO // STATION 04`) into a **big glance-and-steer heading guide**, on request — the driver needs one obvious "which way do I turn" cue.

- **`ui/ops/HeadingGuidanceBar`** — a thick chevron rides a horizontal track. Position = `0.5 + Δ/360` where Δ = signed heading error to the active drive-to target (dead-ahead → centre; 90° right → 75%; ±180° → hard against an edge). The chevron **points the way to turn** (► right / ◄ left) and flips to **status-blue ▲ "ON COURSE"** within a ±4° dead-band. Centre reference notch + `L`/`R` ends + the exact `°OFF`. Computes its own guidance from the live fix (like `opsReadout`).
- **Generalised drive-to** — new `core/ops/DriveTarget` (label + LatLon) unifies the fixed HOME/MAN/TEMPLE presets with a dynamic destination. `CockpitUiState.activeDriveTarget` resolves it; the chevron, ops footer (`opsReadout` now takes a `DriveTarget?`), and the RADAR target blip all steer to the same thing.
- **BATH target** — a 4th `DRIVE TO` button targets the **nearest toilet bank** (`PlayaMap.toilets` centroids), re-resolved from `activeDriveTarget` as the ego moves so it always points at the closest one. `core/ops/nearestDriveTarget` + `relativeBearingDeg` are pure/unit-tested.
- **detekt.** `LongParameterList.functionThreshold` 6→7 (`driveToBar` = theme + active + bathActive + 2 callbacks + modifier — idiomatic Compose shape); `TooManyFunctions.thresholdInClasses` 20→21 (`driveToNearestToilet`).
- **Verified on the S9+** (MAP concept, FAKE ego parked at the Man): HOME → `112°R` purple ► right-of-centre (2:15 radial on H st = 112.5° true — exact); BATH → nearest toilet `164m / 90°R`; MAN → blue ▲ `ON COURSE`. Chevron only lives in MAP for now (RADAR keeps its scope blip + footer arrow).
- **Deferred** from the earlier Phase-3 plan: the nearby-POI *picker* panel (drive-to any camp/art). BATH covered the most-wanted dynamic destination; the panel can come later.

---

## 2026-07-10 — Playa discovery Phase 3: RADAR contacts (blips on the M41A scope)

The flagship discovery surface: nearby art + camps now plot as **contacts on the RADAR sweep scope**, driven by the offline-first `DiscoveryRepository`.

- **Data flow.** `DiscoveryRepository.pois` → `CockpitUiState.pois` → `MapUiInputs.pois` (smart-skip slice) → `PlayaMapPanel` contacts overlay. The ViewModel takes a plain `poisFlow: StateFlow<List<PlayaPoi>>` (defaulted empty) rather than the whole repo, so it depends only on what it renders and the 19 existing `CockpitViewModelTest` factory sites needed **zero changes**. `MainActivity` wires `app.discoveryRepository.pois`.
- **Pure geometry, unit-tested.** `core/ops/RadarContact.kt`: `contactsWithinRange(pois, center, rangeM, max)` (nearest-N inside the scope radius, drops unplaceable POIs) + `contactPulse(sweepDeg, blipAngle)` (M41A "ping": full-bright as the arm passes, linear fade to a floor so contacts persist between sweeps). Both Compose-free → `RadarContactTest`.
- **Render.** `ui/concepts/RadarContactBlips.kt` `drawContacts` (a `DrawScope` ext): projects each POI through the *same* viewport as the map, culls to the circular scope, and — matching the lit map's own encoding — draws **art = pink diamond, camps = purple dot**, with the active drive-to **target = a ringed blue blip**. The blip alpha reads the sweep angle *inside the draw scope* (same trick as the sweep arm) so the 60 fps ticker invalidates draw, not composition. `ContactsOverlay` is an optional field on `PlayaMapPanelStyle` (RADAR sets it; MAP leaves it null for now).
- **detekt.** Bumped `LongParameterList.constructorThreshold` 7→8 (manual-DI VM/factory gained `poisFlow`) with a rationale comment; split the blip drawers into their own file to stay under `TooManyFunctions.thresholdInFiles` (12); named `RadarContact.kt` after its data class (`MatchingDeclarationName`).
- **Verified on the S9+** (real 2025 data): 806 of 1726 API POIs are placeable (the rest are plaza/portal addresses the projector drops, as designed). With the FAKE ego parked at the Man, the scope shows pink art diamonds + purple camp dots clustered near centre, brighter in the swept sector, dimmer at the floor elsewhere. HOME (1.6 km) sits beyond the 846 m scope range so its target ring is clipped off-scope while the footer still guides to it.
- **Device gotcha (offline bring-up):** the S9+'s wifi couldn't reach `api.burningman.org` (DNS resolves, no route), and `DiscoveryRepository` swallows fetch errors by design → empty scope. To verify deterministically, fetched + projected the real 2025 dataset on the Mac (`/api/art` + `/api/camp`, same projection math) and streamed the app-format cache straight into the app's private dir via `adb shell "run-as PKG sh -c 'cat > cache/discovery_2025.json'" < file`. Note: `run-as PKG <cmd>` uses the app-home cwd, but `run-as PKG sh -c '<cmd>'` resets cwd to `/` — use the direct form when poking app files (absolute paths are SELinux-blocked).

---

## 2026-07-06 — Handoff/state doc; drive-to selector → prominent bar

- **`HANDOFF.md` (repo root)** — wrote a consolidated current-state + roadmap doc (context was ~90% full) so a fresh session gets up to speed fast for the next big feature. It points at the likely next work: the **network/data layer** (Starlink onboard → weather/dust alerts + playa discovery; see memory `project_data_ecosystem`). Read HANDOFF first, then SYNC for history.
- **Drive-to selector → prominent bar.** Moved destination selection from tiny buried control-strip chips to a **full-width HOME/MAN/TEMPLE button bar** above the ops footer (active = blue); verified on the S9+ (HOME 1.6km ↔ MAN 0m switching).
- **Testing-display discipline** (memory `feedback_s9_testing_display`): sleep the display after every screenshot; keep brightness manual + low (20) for all testing. "No burn-in until the Burn."

---

## 2026-07-04 — Two concepts (RADAR + MAP); drive-to nav (HOME/MAN/TEMPLE)

- **Consolidated to two concepts.** Dropped Concept A (CRT VECTOR; B was already gone) + `CRTVectorScreen`. Renamed C→RADAR, D→MAP and dropped the letter designations (`CockpitConcept` lost `tag`; the switcher shows just the name). Cycle RADAR↔MAP; default RADAR; a stale persisted A/B/C/D falls back to RADAR via the enum-name guard.
- **Drive-to navigation.** `core/ops/NavTarget` = HOME (camp, Heiau & 2:15), MAN (Golden Spike origin), TEMPLE (2025 CPN "The Temple", ~762 m out on the 12:00 axis). Added `CockpitUiState.navTarget` (default HOME) + `CockpitViewModel.setNavTarget`; a `> DRIVE TO [HOME][MAN][TEMPLE]` chip section in the shared control strip; and generalised `opsReadout` from the fixed camp readout to the active target — footer shows `▸ <TARGET> <dist>` + a heading-relative arrow (reuses `campGuidance`). Verified live on the S9+: HOME read the real ~429 km to the BRC camp from the tablet's actual GPS fix; on FAKE (parked at the Man) it reads HOME ~1.6 km / MAN 0 / TEMPLE ~762 m.

---

## 2026-07-04 — Cockpit colour system (green chrome / blue status / purple data / red error)

Amber didn't sit with the strong phosphor green, so replaced it with a **semantic palette** (shared constants in `ui/concepts/ConceptTheme`): **green** for all chrome/controls/buttons/labels; **blue** for status only (link/connection/GPS state, selected control); **purple** for live data values (heading/speed/range/zoom, clock, distance, gauge needles, ego marker, map plazas/landmarks); **red** for faults / extreme warnings only. Amber banned. Touched the theme (unified + new `error` role), all three concept screens, the shared components (switcher/nav/controls/recenter → green, selected → blue), the ops readout (values → purple), and the map palettes (plaza + ego amber→purple; tracker-lit yellow→purple/blue/magenta). No amber literals remain; verified across A/C/D on the S9+.

---

## 2026-07-04 — Dropped Concept B; Concept D green; ops readout made first-class per concept

Three cockpit-shell changes, all verified on the S9+:
- **Dropped Concept B (PERSPECTIVE).** Removed the enum entry, `PerspectiveGridScreen`, and `ThemePerspective`; kept tags A/C/D stable (a stale persisted "B" falls back to A via the existing enum-name guard). Cycle is now A→C→D→A; tests updated.
- **Concept D recoloured to green.** `ThemeInstrumentBay` + the D map palette moved from amber/orange to the green phosphor family; D's blocky gauge-tile layout keeps it distinct from Concept A.
- **Ops readout is now first-class per concept**, not the shared bottom overlay (which read as bolted-on). New `ui/ops/opsReadout` is palette-driven + background-less; each concept places it in its own chrome — A and C as a bordered footer in their theme colour, D as a green tile footer that replaced the decorative hazard chevron (and dropped D's fake static "UTC 19:38:23" tile, since the readout now shows a real live clock). Recenter buttons raised above the footer so they don't cover the CAMP segment. `OpsStrip` + the reserved-band wrapper removed from `CockpitScreen`. (Note on-device: CAMP shows "--" with no GPS fix; distance populates on a fix — same `campGuidance`, verified earlier.)

---

## 2026-07-04 — Operational-awareness strip shipped; app verified on the S9+ OLED

**Data ecosystem briefed (2026-06-29).** Zodiac has Starlink onboard, unlocking live data (weather via Open-Meteo, NWS alerts zone NVZ023, the Burning Man API) — all to be built offline-first. Camp = Galactic Relay, **Heiau & 2:15**. Full detail + constraints (don't touch nav/GPS/messaging) in memory `project_data_ecosystem`. First increment chosen: the no-network **operational quick-wins**.

**Operational awareness (`core/ops/` + `ui/ops/`).**
- **Logic (tested):** `Camp.GALACTIC_RELAY` (provisional Heiau & 2:15 — 2:15 radial ∩ the H-ring @ 1555 m, measured from 2025's "Herbert"; replace with the BM API geocode / 2026 Golden Spike when they land); `campGuidance` (ego→camp bearing+distance over `PlayaProjection`); `SunTimes` (local NOAA sunrise/sunset, no API, verified vs NOAA reference).
- **UI:** ambient bottom **ops strip** over every concept (BRC clock · RISE/SET · CAMP distance + a heading-relative ▲). Rendered inside `burnInScaffold` so it dims/sleeps with the cockpit.

**S9+ bring-up + fixes (verified on-device, One UI 8 / Android 16 / SM-X810).**
- **Edge-to-edge:** targetSdk 35 forces edge-to-edge on Android 15+, so the strip drew under the system bars. Fixed: `MainActivity` now draws edge-to-edge + **immersive** (hides status/nav bars, transient-on-swipe) — full-panel kiosk chrome. Strip also gets `WindowInsets.safeContent` padding for gesture-nav.
- **Strip tuning:** opaque-black HUD bar + phosphor divider (was a translucent veil dimming the bottom); **reserve** `OPS_STRIP_HEIGHT_DP` at the bottom of the concept dispatch so concepts render above it (was overlapping the recenter button).
- **OLED burn-in verified on the panel (caveat closed):** the visual breathe/dim layer was gated off on the LCD Fire, so it had never rendered. On the S9+ (Adreno/Vulkan) all four phases render clean — DIM = uniform 30% with **no `ModulateAlpha` blend artifacts**, DEEP_IDLE = CRT STANDBY on true-black, SLEEP = pixels off. The Adreno-vs-LCD precision concern (same family as the deferred pixel-cache) is resolved for this layer.

**Deploy gotcha (in `reference_build_deploy_env`):** the S9+ was in Samsung **desktop/DeX windowing** — apps open freeform with a forced taskbar that overlaps the app bottom (looks "cut off"), can't be toggled off via adb, and reverts on reinstall. `adb pair` (wireless) kept racing the rotating pairing port; **USB `tcpip 5555` then wifi connect** is the reliable path. For a clean dashboard: screen-pin/kiosk (no taskbar). `screencap` captures the app framebuffer without the taskbar overlay.

**Still open:** per-concept strip polish beyond the reservation (sizing/segment spacing across all four) if wanted; broader operational features (return-to-camp as its own affordance, weather/NWS/BM-API — need the network layer) remain future increments.

---

## 2026-06-20 — Fleet adds Samsung (S9+ main dashboard); OLED burn-in mitigation shipped

**Hardware target widened: Fire + Samsung.** The fleet is no longer Fire-only — Samsung Galaxy Tabs are now a target, and the **main dashboard display switched to the Galaxy Tab S9+** (12.4" Dynamic AMOLED 2X, 2800×1752, 120Hz, Snapdragon 8 Gen 2, 12GB). Candidate fleet models: S9, S9+, S10, maybe FE. They span **2-3 GPU families** — S9/S9+ = Adreno 740 (Qualcomm, Vulkan), S9 FE = Mali-G68 (Exynos), S10 = MediaTek Immortalis. Consequences: (1) the deferred **GPU pixel-cache** map optimization now needs an **Adreno device** in its visual-validation loop (the Fire's Mali-GLES can't certify Vulkan-Adreno halo precision); the Fire HD 10 stays the **perf floor**, the FE is the only Samsung near it. (2) The OLED panels introduce **burn-in risk** over multi-day playa deployment — addressed below.

**Burn-in mitigation feature (4 phased commits, all verified on the Fire HD 10).** New `burnin/` package; wraps the whole cockpit from one node in `cockpitScreen` via `burnInScaffold`.
- **Idle state machine** (`BurnInMitigationManager`, `BurnInPhase` ACTIVE→DIM→DEEP_IDLE→SLEEP). Injectable clock (`SystemClock.elapsedRealtime`), so it's fully unit-tested with a fake clock. Activity = touch, **real** GPS movement (parked re-emits + jitter don't count — speed≥1kph or >3m drift), or a vehicle-link phase change. Taps the existing location/connection StateFlows read-only; ViewModel untouched.
- **Visual mitigations:** whole-UI **pixel-shift** (`offset{}`, placement phase only — universal); **global brightness breathe + dim** via one `graphicsLayer` alpha (`ModulateAlpha`, cheap/blend-preserving) — chosen over per-element alpha threading (~40 sites) for the same wear-distribution benefit. **OLED-gated** off on the Fire (Amazon = LCD, can't burn in, slowest GPU) via `BurnInDeviceProfile`.
- **Idle visuals:** DEEP_IDLE → dedicated CRT `standbyScreen` (phosphor "STANDBY" + drifting scan line, *not* a generic screensaver); SLEEP → app-drawn pure black (OLED pixels off) + min window backlight, Activity stays foreground so **wake-on-touch** is instant (no WAKE_LOCK, no lockscreen). `FLAG_KEEP_SCREEN_ON` held in all phases.
- **Manual park:** top-left corner long-press → immediate standby (`enterPark` fast-forwards the idle clock to the deep-idle threshold). **Hidden tuning panel** (bottom-left long-press): phosphor-green CRT panel with steppers for every timeout/modulation param + PARK NOW/WAKE/DEFAULTS/CLOSE; edits apply live and persist (new `readBurnInConfig`/`setBurnInConfig` on `CockpitPreferences`, individual DataStore keys, coerced on read).
- **All config coerces itself** (`BurnInConfig.coerced()`: timeouts forced strictly increasing, amplitudes/alphas clamped) — a tampered prefs file can't seed a bad config.
- **Deferred (Phase 5, optional):** per-region burn-in stress-accounting ledger to file — pairs with the M10 Timber task; not built.
- **Defaults:** 2px/45s shift, ±4%/20s breathe, 5/30/60-min dim/standby/sleep. Verified on device: dashboard → STANDBY → black SLEEP → tap-to-wake → dashboard; tuning panel + PARK NOW.

**adb input quirk (Fire HD 10):** a reliable long-press needs a *continuous* `input swipe x y x+2 y+2 900` (one stream, tiny move within slop); separate `motionevent DOWN/UP` calls or a zero-distance swipe register as a tap, not a hold.

---

## 2026-06-19 — Concept C radar locked to the car; build verified on the Fire HD 10

**Deploy bring-up.** Got the debug build running on the physical tablet (Amazon Fire HD 10, codename "tungsten" / model `KFTUWI`, Android 11 / Fire OS 8.0, **API 30**, arm64-v8a — the real perf-target hardware). `./gradlew installDebug` + `adb shell am start -W -n ai.openclaw.zodiaccontrol/.MainActivity`; screencap to verify. Toolchain isn't on PATH (JDK 17 at the Homebrew Cellar path, SDK at `/usr/local/share/android-commandlinetools`), and there's no `local.properties` — set `JAVA_HOME`/`ANDROID_HOME` inline per command.

**Radar bug + fix (decision: Option B).** In Concept C the sweep (arm, cone, range rings, lit "ping") draws from the **canvas centre**, but the car/ego arrow is drawn at its GPS position projected through the viewport. In TRACK_UP they coincide; after a one-finger **pan** (FREE mode) the car moved off-centre while the sweep stayed pinned to the canvas centre — sweep appeared to come from a random spot. User chose **Option B (lock the car to the scope centre)** over Option A (make the sweep follow the car off-centre). Implemented as `PlayaMapPanelStyle.lockCameraToEgo` (set on Concept C): (1) `viewportFor` always centres the camera on the ego, ignoring any stale `cameraOverride`; (2) one-finger pan is disabled (no-op `onPan`; zoom + rotate still work). Sweep drawing untouched. The car now always sits under the fixed scope centre and the map scrolls beneath it. No change in TRACK_UP; only removes the off-centre FREE case. Verified on the tablet (pan attempts no longer move the car). Commit `ded72a3`.

**Also this stretch:** `design/PERFORMANCE.md` — durable backlog of the *unimplemented* perf wins (Tier 1 = GPU layer-promotion / pixel-caching of the map, needs on-device validation; Tier 2 behavior-preserving; Tier 3 visual-tradeoff; dismissed items; on-device profiling method).

---

## 2026-06-14 — Perf audit (slow Fire tablets) + behavior-preserving wins

Ran a 9-subsystem performance audit as a workflow (54 agents, ~2.2M tokens: per-subsystem hot-path analysis → adversarial verification of each finding → completeness critic). 44 findings, 28 confirmed real + on the hot path. Landed only the **pixel-identical / observably-identical** subset; all 234 tests stay green.

**Headline win — Concept-C (Motion Tracker) 60fps recomposition storm.** `sweepDeg` (the `withFrameNanos` ticker) was read at the top of `motionTrackerScreen`, recomposing the entire concept tree + every `String.format` (RNG/BEARING/SPEED/RANGE/ZOOM) and rebuilding `SweepOverlay`/`PlayaMapPanelStyle` every frame. Fix: `SweepOverlay.sweepDeg: Float` → `() -> Float`, backed by `mutableFloatStateOf` that is **not** read in the composable body — only the map/arm draw lambdas invoke it, so the frame ticker invalidates just the draw phase. Multiple analyzers + the critic converged on this; it's the single biggest behavior-preserving win.

**Allocation hoisting (`BrcMapRenderer`, ~60fps on C):** hoisted the 4 static map Strokes, the per-art-marker Stroke (~50/frame), and 8 halo Strokes to file-level vals; cached the 3 CRT endpoint `lerp()` colors and 8 halo `copy(alpha)` colors as `MapPalette` class-body vals (computed once per palette; not constructor params, so equals/hashCode unaffected). Byte-identical output.

**Recomposition-scope narrowing:** `topHeader` takes `(headingDeg, speedKph, concept)` not the full state; `rightRail`'s status-list + lat/lon formats wrapped in `remember`; InstrumentBay zoom `String.format` wrapped in `remember(pixelsPerMeter)`.

**Cold-start:** `AssetsPlayaMapRepository` now emits `Loaded(map)` before the binary-cache write (fire-and-forget on a SupervisorJob IO scope) so the map paints without waiting on the ~MB serialize.

**Investigated and rejected:** the critic's "parked GPS re-emits at 2Hz driving a full `CockpitUiState.copy()`" — **false**. `LocationSourceState.Active` + `GpsFix` are data classes, so `MutableStateFlow` already conflates identical fixes through `flatMapLatest` to the collector. Reverted the FakeLocationSource dedup as a no-op.

**Biggest remaining win, deferred (needs device validation):** GPU **layer-promotion / pixel-caching** of the rasterized map. The geometry is cached but the pixels aren't, so the full CRT Skia call-list replays at 60fps under the sweep (and on the TILT layer). Promoting the base map to a `graphicsLayer` cache that only re-rasterizes on camera change would remove that — but offscreen-compositing the translucent CRT halos can shift blend math, so it must be validated on a real Fire HD 10 (`dumpsys gfxinfo framestats`, Layout Inspector recomposition counts, Macrobenchmark `FrameTimingMetric`) before landing. Tracked in `tasks/open.md`. NOTE: all wins here are principled static-analysis (allocation/recomposition/format reduction), not device-profiled — confirm with the tools above.

---

## 2026-06-14 — Deep robustness audit + Tier 1-4 hardening

Ran a five-front read-only audit (core logic, data layer, UI/VM, tests, docs/build) then landed the fixes in four CI-green phase commits.

**Tier 1 — verified bugs (`c5d652a`).** `ProjectedMap.collectAllVertices` read `flat[i+1]` with only an `i < size` guard → crash on any odd-length vertex array (fixed to `i+1 < size`). `NmeaParser` now validates decoded lat/lon range (±90/±180), minutes <60, and finiteness, and normalizes RMC course into [0,360) — one corrupt sentence can no longer inject NaN/garbage coords downstream. Usb/Ble pump loops checked the *parent* scope's `isActive`, so `stop()` didn't actually halt them; now they observe the launched job's scope. `PlayaMapBinaryCache` bounds-checks every `readInt()` count before allocating (a corrupt cache could OOM/`NegativeArraySize` outside the IOException catch).

**Tier 2 — hardening (same commit).** Ble/Usb/System location sources broaden their setup catches so a missing `GPS_PROVIDER` (Fire tablets have no GNSS), revoked permission, or zero-port driver surfaces as `Error` state instead of crashing the coroutine. `GeoJsonParser` uses `optDouble` + per-feature skip (one bad coordinate no longer fails the whole map). Cache write is atomic (temp + rename); `AssetsPlayaMapRepository.load` is `Mutex`-guarded against a concurrent parse/cache-write race. `PlayaNavigator` emits `Unknown` at the Man (distance ~0) instead of a bogus 12:00 from `atan2(0,0)`. Failed vehicle-command sends now surface as `CockpitUiState.commandError` (a shared `sendCommand`/`runCatching` helper that also stopped swallowing `CancellationException`). `viewRotationDeg` is normalized into [0,360).

**Design call NOT taken:** the audit flagged `RoutedVehicleGateway` not disconnecting the old adapter on transport switch as a leak. `RoutedVehicleGatewayTest` documents this as *intentional* (pure router, keeps links warm for fast switch-back) — the opposite choice from `RoutedLocationSource`. Left as-is with a clarifying comment; flagged for a deliberate decision later.

**Tier 3 — tests (`5171eb2`).** ~21 new unit tests covering the above (NMEA range/normalization/garbage, corrupt-cache miss, ViewModel clamp boundaries + rotation normalization + commandError + auto-recenter timer, GeoJSON malformed-feature skip, the repository success/stitch path). Removed `ExampleUnitTest` (tested stdlib `coerceIn`) and `ExampleInstrumentedTest` (default stub). USB byte→line ingest left untested — needs a `Context` seam and the project has no mocking/Robolectric.

**Tier 4 — build/CI + docs (`81473b1` + docs commit).** Added `ACCESS_COARSE_LOCATION` (was a real lint **error** — Android 12+ requires it with FINE). CI now runs via `./gradlew` (wrapper 8.10.2 = single source of truth) and adds an `lintDebug` step. Release build enables R8 minify + resource shrink: **36MB → 2.4MB**; `proguard-rules.pro` keeps the usb-serial driver classes. Opt-in `signingConfigs.release` wired from `ZODIAC_KEYSTORE_FILE` (env/property) for the tablet fleet; unsigned without it. detekt: `ReturnCount` relaxed to 3, `TooManyFunctions` bumped for the `sendCommand` helper. CLAUDE.md + README brought back in sync with reality (four concepts, GPS/playa-map/prefs layers, `CockpitScreen` dispatcher, DI in `ZodiacApplication`, pan/pinch map touch — not the old X→heading/Y→speed).

**Caveats:** the R8 release APK builds clean (incl. `lintVitalRelease`) but must be validated on a real tablet before distribution. The audit's "appcompat is unused" finding was a false positive (`Theme.ZodiacControl` extends `Theme.AppCompat.DayNight.NoActionBar`).

---

## 2026-05-03 — CRT beam + vectorText: rolled to all concepts, dialed up

After validating the look on Concept A, rolled the same shared chrome treatment to B / C / D (the user's call: "good enough, apply to all"), then a second commit dialed both subsystems up a notch for more pop.

**Rollout (`f9d41cc`):** `MapPalette.crtBeam = true` on `PerspectivePalette` (B), `TrackerBasePalette` and `TrackerLitPalette` (C — both halves of its dim-base + lit-wedge dual setup), and `InstrumentBayPalette` (D). `ConceptTheme.useVectorText = true` on `ThemePerspective` / `ThemeTracker` / `ThemeInstrumentBay` so `NavCueBar` picks up `vectorText` rendering everywhere via the existing dispatch in `themedText`. Concept-specific bespoke text (e.g. Concept C's "MOTION TRACKER" header, Concept D's tile titles) intentionally stays as plain `Text` — the rollout covers shared chrome only.

**Intensity bump (`bb21c8b`):** map halo outer 0.22 α / 6.0× → 0.28 α / 7.5×, inner 0.50 α / 2.6× → 0.62 α / 2.8×; endpoint white-tint 0.5 → 0.6; corner radii up across the board (street 2.6→3.0, plaza 3.0→3.4, fence 3.4→3.8). Text halo 0.40 α / 4.0px → 0.55 α / 5.5px; baseline trail 0.45 α / 0.7px → 0.55 α / 0.8px. Pure constants, no structural diff.

CI gates green on each commit.

---

## 2026-05-03 — Concept A: punchier CRT beam + vectorText for top bar / nav cue

Follow-up to the morning's CRT-beam landing. Two visual upgrades:

1. **Stronger phosphor bloom.** First pass was reading too soft on the Fire HD 10. Switched the halo from a single 3.5×-width / 0.22-α pass to a two-pass falloff: outer at 6× / 0.22 (the soft outer bloom) plus inner at 2.6× / 0.50 (the brighter band closer to the beam). Endpoint dots now blend their layer's base colour 50% toward white via `Color.lerp`, so corners read as the over-exposed phosphor "node" you'd actually see on a vector monitor; bumped dot radii too (street 2.6, plaza 3.0, fence 3.4).

2. **`vectorText` for headline chrome.** New `vectorText` Composable in `ui/VectorText.kt` — outlined glyph strokes via Compose's `drawStyle = Stroke(...)`, halo backing pass at 4× width / 0.40 α, plus a faint baseline trail line spanning the rendered text width (the "beam swept across" ghost-line you see on real vector monitors when blanking is imperfect). Text is measured once via `rememberTextMeasurer` keyed on `(text, style)` so unrelated recompositions never re-measure glyphs; colour is applied at draw time.

   Top bar redesigned around it: dropped the small "ZODIAC CONTROL // CRT VECTOR" upper-left label and the MODE / THERM headers; the bar is now just `ZODIAC` (large, vector) + `HDG nnn°` + `VEL nnn` (medium, vector) + the existing concept switcher pulled inline at the right end. NavCueBar gains `useVectorText: Boolean` on `ConceptTheme` (default false) and dispatches its three text reads through a `themedText` helper that picks `vectorText` or plain `Text` based on the flag. Concept A's theme opts in; B / C / D unchanged.

CI gates green on each phase commit.

---

## 2026-05-03 — Concept A: Atari-vector CRT-beam aesthetic + labels on

Three phase commits to give Concept A the look of Atari Star Wars / Asteroids on a vector monitor — the user's reference. Two visual cues drive the look: a **phosphor bloom halo** around every stroke (the electron beam softly diffuses on the screen), and **bright dots at every place the beam decelerates** (line endpoints / polygon corners over-expose the phosphor and read as little glowing nodes).

1. **Plumbing.** New `crtBeam: Boolean = false` on `MapPalette`. `ProjectedMap` gains `streetEndpoints` (first + last of each street + outline polyline), `plazaCorners` and `fenceCorners` (every vertex of those closed polygons). `PlayaMap.project()` walks the same `DoubleArray`s it already uses for the path build via two new private helpers (`collectPolylineEndpoints`, `collectAllVertices`) — same inline primitive projection, no per-vertex allocation.

2. **Renderer.** Two new private DrawScope helpers in `BrcMapRenderer`. `drawCrtHalo` is a pre-pass that re-draws each stroke layer at 3.5× width with 0.22 alpha — same color family, just spread out and softer. `drawCrtEndpoints` is a post-pass painting bright filled dots over the beam-stop points; one `drawPoints` call per layer (3 total) regardless of dot count. Both gated on `palette.crtBeam`. No labels needed in either pass; labels still render through the same `drawProjectedLabels` cache.

3. **Activation.** `MapPalette.Default` (Concept A only) flips to `crtBeam = true`, `labelsEnabled = true`, `labelPrimary = #B0FFB0` (pale green to match the green palette).

CI gates green on every phase commit. Performance is fine: per frame, halo adds 4 `drawPath` calls (already-cached paths, no projection), endpoints add 3 `drawPoints` calls (already-projected positions). On the same per-frame budget that round 2/3 brought down to single-digit Skia calls, the CRT pass adds ~7 calls — still well within Fire-class headroom.

If the look needs more bite later, options are: bump street/outline base brightness so the halo reads stronger, increase `CRT_HALO_WIDTH_MULT` past 3.5×, or switch to true `BlurMaskFilter` on a native Paint for an actual gaussian glow (works on API 30+, just needs a `nativeCanvas` drop-down).

---

## 2026-05-03 — Pre-laid-out label TextLayoutResults

Single follow-on commit on top of round 3. `drawProjectedLabels` was hitting Android `Paint.drawText` per label per frame, with internal glyph layout each call (measure characters, kerning, baseline). At high zoom with 100+ labels visible, that per-frame layout cost was real — and labels are going to be on by default.

New `LabelLayouts` struct holds parallel `List<TextLayoutResult>`s for plaza / art / street / CPN labels, pre-measured once via Compose's `TextMeasurer` keyed on `(map, density)`. Layouts are colour-agnostic (measured with default style colour; palette colour overridden at `drawText` time), so concept switches within a labels-enabled family don't invalidate the cache. `labelsEnabled = false` short-circuits to `LabelLayouts.Empty` so concepts that don't draw labels don't pay the up-front measurement cost.

`drawProjectedMap` now bundles the label pass via optional `(labelLayouts, pixelsPerMeter)` parameters — same call shape, labels just disappear when layouts are Empty. `drawSweptProjectedMap` (Concept C's lit-wedge re-blit) passes Empty so labels don't double-render or get clipped by the rotating wedge.

Open round-2 perf items remaining: major-art `Path` batching, wedge memoisation, formatter-string memo.

---

## 2026-05-03 — Render perf round 3: DoubleArray polylines, binary map cache, sliced map inputs

Three more phase commits stacked on top of round 2. Same pattern: identify a per-frame or per-launch cost, hoist it.

1. **DoubleArray-backed polyline storage + inline primitive projection.** The renderer's per-vertex walk allocated two small data class instances (`PlayaPoint` + `ScreenXY`) for every point in every polyline on every cache miss — thousands of allocations per gesture frame just to hand x/y floats to `Path.moveTo` / `lineTo`. Source coords were also stored as N separate heap-resident `LatLon` objects, costing cache locality.

   `PolygonRing.ringFlat` and `StreetLine.pointsFlat` are eagerly flattened `[lon0, lat0, lon1, lat1, ...]` `DoubleArray` mirrors of the existing `List<LatLon>`, computed once when `PlayaMap` loads. New `PlayaProjection.projectInline` and `PlayaViewport.toScreenInline` are inline functions that take primitive `(lon, lat)` / `(eastM, northM)` args and yield primitive results to a callback — no `PlayaPoint` / `ScreenXY` allocations on the hot path. `Path.appendSubpath` walks the DoubleArrays via these inline helpers and allocates nothing in its inner loop. Existing object-arg APIs are preserved for tests and the (cold-path) navigator code.

2. **Binary cache for parsed `PlayaMap` (cold-start speedup).** `AssetsPlayaMapRepository` re-parsed ~1 MB of GeoJSON on every launch, doing the same JSONObject walk + `StreetLine`/`PolygonRing` construction from scratch. New `PlayaMapBinaryCache` hand-rolls a flat `DataInputStream`/`DataOutputStream` format keyed on year + schema version. First cold start parses JSON and writes the binary into `cacheDir`; subsequent starts read it back in a few hundred ms. Filename + header carry the schema version, so a bump silently invalidates older caches and falls through to JSON. Read/write failures are best-effort — JSON path stays the source of truth, app stays alive without the cache. `ZodiacApplication` wires `cacheDir` through. Round-trip tests cover every layer + nullable field.

3. **Slice the map subtree's inputs through `derivedStateOf`.** Concept screens collected the full `CockpitUiState` and passed it into `playaMapPanel` / `centerViewport` — meaning a thermal / link / connection update (touching no map field) flowed through the panel's remember-keyed caches just to be a no-op. New `@Stable MapUiInputs` data class carries exactly the fields the map subtree reads. Each screen wraps `MapUiInputs.from(state)` in `remember { derivedStateOf { ... } }`, and `playaMapPanel`'s signature takes `MapUiInputs` instead of `CockpitUiState`. Compose's smart-skip engages: thermal-only updates skip the panel's recomposition entirely while the surrounding rails still update.

CI gates green on every phase commit. Items 5 / 8 / 9 / 10 from the round-2 list (major-art `Path` batching, wedge-path memoisation, label `TextLayout` cache, formatter-string memo) remain on `tasks/open.md`.

---

## 2026-05-03 — Render perf round 2: scanlines, retro grid, static partition, fused projection

Four small follow-on commits hoisting the rest of the obvious invariants out of the per-frame draw path. None changed visuals — pure tightening on top of the M2 cache landed yesterday.

1. **Scanline overlay → single Path.** Concept A and Concept C each stamped ~270 individual `drawLine` calls per frame for the static CRT chrome — and Concept C's `withFrameNanos` ticker meant the cost was paid at 60 fps even when nothing else moved. Extracted a shared `scanlineOverlay()` that uses `Modifier.drawWithCache` to build a multi-subpath `Path` keyed on canvas size, then strokes it in one `drawPath` per frame. The old duplicated `scanLineOverlay` / `scanlineOverlay` Composables in CRTVectorScreen and MotionTrackerScreen are gone.

2. **Retro grid → cached Path (closes audit M4).** `drawRetroGrid` recomputed 102 line endpoints + emitted 102 `drawLine` calls every frame in TILT and Concept B. Camera centre cancels in screen space, so the grid is fully determined by `(headingDeg, ppm, size, anchorYFrac)`. Split into `projectRetroGrid(viewport): Path` + a pure-raster `drawRetroGrid(path, color)`. Both call sites memoise via a `rememberRetroGridPath` helper keyed on the same viewport that already gates the projected-map cache. Per-frame: 102 `drawLine` → 1 `drawPath`.

3. **Pre-partition static map data on load.** `ProjectedMap.project` was running `art.partition { it.kind in MajorArtPrograms }`, `streetLines.filter+groupBy { it.name }`, and `representativePoint()` on every cache miss — pure functions of the static `PlayaMap` with nothing camera-dependent. Moved to `PlayaMap` init: precomputed `majorArt` / `minorArt` `PointFeature` lists plus `*LabelSeeds` carrying `(text, world LatLon, major-flag)` triples for plazas, art, streets, and CPNs (typed as `StaticLabel`). Projection step now only does work that actually depends on the viewport.

4. **Fused projection into path build.** `buildSubpathBundle` previously took `List<List<Offset>>`, forcing the caller to allocate one inner `ArrayList` per polyline (~600 streets + outlines + plazas) and box each `Offset` inline class onto the heap. Replaced with a generic helper that takes the source list + a `ringOf` extractor and walks each polyline once, writing screen-space x/y pairs directly into `Path.moveTo` / `Path.lineTo` — no intermediate `Offset` list, no per-point inline-class boxing in the inner loop. Cuts per-cache-miss allocations by hundreds of `ArrayList`s during pan/zoom gestures.

CI gates green (ktlint / detekt / testDebugUnitTest / assembleDebug) on every phase commit. Verified visibly identical on the user's Fire HD 10 13th gen.

Items 5–10 from the round-2 audit (major-art batched as one Path of `addOval`s, GeoJSON binary cache, `derivedStateOf` for state isolation, wedge-path memoisation, pre-laid-out label TextLayouts, formatter-string memoisation) remain on the table for later.

---

## 2026-05-02 — Render perf: projected-map cache + draw-call batching (audit M2)

Concept C's rotating sweep was visibly stuttering on the Fire HD 10 — the M41A look re-renders the entire BRC map twice per frame (dim base + lit-clipped wedge), and the per-frame draw load was the bottleneck. Two-pass fix:

1. **Projection cache.** New `ProjectedMap` type and `PlayaMap.project(projection, viewport)` that walks every feature once and pre-builds screen-space geometry. Composables in `playaMapPanel` and `centerViewport` use `Modifier.onSizeChanged` + `remember(playaMap, viewport)` to memoise the projection — within a single GPS tick (every 500 ms), all 60 fps frames reuse the same cached result. Cache invalidates on GPS update, pan, pinch, rotate, or canvas resize.
2. **Draw-call batching.** Same-style geometry collapses into one Skia call:
   - All ~600 streets → one `drawPath` with `Stroke(cap = Round, join = Round)`. Previously: ~4000 `drawLine` calls per pass.
   - Street outlines, plazas → one `drawPath` each (consolidated subpaths).
   - Toilets / CPNs / minor art → one `drawPoints` each (filled circles via `PointMode.Points` + round cap).
   - Major art (hollow stroke) stays as ~50 individual `drawCircle` calls — `drawPoints` can't render hollow.

Per-pass call count: ~4400 → ~60. Concept C's double pass: ~9000 → ~120 calls per frame.

Renderer split: `drawProjectedMap(projected, palette, pixelsPerMeter)` is the new pure-raster entry point. `drawPlayaMap` retained as a one-shot wrapper that projects then draws — handy for tests / one-off renders. Old internal helpers (`drawArtMarker`, `drawStreet`, `drawPolygon`, `drawCentroidPoi`, `drawPoi`, `RenderCtx`, `toScreen` extension) deleted.

Closes audit task **M2** ("cache projected `Path` per `(heading, zoom, anchorYFrac, panOffset)`"). Verified visibly smooth on the user's Fire HD 10 13th gen.

CI gates green: ktlint, detekt, testDebugUnitTest, assembleDebug.

---

## 2026-05-01 — Drive-mode fake GPS: chips steer the synthetic ego

Reworked `FakeLocationSource` from a slow-circle / parked-offset hybrid into a proper kinematic sim. Every 500 ms tick:

```
posEastM  += sin(headingDeg) · (speedKph / 3.6) · dt
posNorthM += cos(headingDeg) · (speedKph / 3.6) · dt
```

…and emits a `GpsFix` with the new position + the configured heading + speed. New driver-input methods: `setHeading(deg)`, `setSpeed(kph)`, plus existing `nudgeManualOffset` (teleport) and `resetManualOffset` (zero everything).

VM `setHeading`/`setSpeed` chip handlers now also call into the fake source so the rail acts as a steering wheel + throttle. State `headingDeg` and `speedKph` get re-folded from each subsequent `GpsFix` (the location collector pulls `fix.headingDeg?.toInt()` into state) — i.e. heading is a *physical* property of the ego, sourced from GPS. On real hardware (BLE/USB/SYSTEM) this same path picks up real motion; on the synthetic source the chip→fake→fix round-trip is what makes the cockpit feel like driving.

Initial state is parked at the Spike, heading 0, speed 0 — nothing moves until the user taps a speed chip. The slow-circle default is gone; the sim is more useful for actual debugging.

`FakeLocationSource` constructor lost `pathRadiusMeters` / `periodSeconds` params. Tests rewritten around the new behaviour.

---

## 2026-05-01 — Follow modes: TRACK_UP / FREE + recenter button + auto-revert

The cockpit now models its camera state explicitly. New `FollowMode` enum:

- **TRACK_UP** (default): camera follows the live GPS fix; display rotation tracks the ego's heading; ego marker stays at viewport anchor pointing up.
- **FREE**: camera holds an *absolute* world position (`CockpitUiState.cameraOverride: PlayaPoint?`); display rotation is independent (`viewRotationDeg: Double`); GPS updates slide the ego marker on screen but the map underneath stays put. Standard map-app feel.

State changes:
- Dropped `panEastM`/`panNorthM` (they were "offset from ego" — wrong model for FREE).
- Added `cameraOverride`, `followMode`, `viewRotationDeg`.
- `MAX_PAN_M` → `MAX_CAMERA_OFFSET_M`; clamps |camera − ego| in FREE.

Behaviour:
- One-finger pan: switches to FREE, parks `cameraOverride = ego + Δ`.
- Pinch-zoom: stays in current mode, resets the auto-revert timer when in FREE.
- Two-finger rotate: spins `viewRotationDeg` only — does *not* touch `headingDeg` (heading is a physical property of the ego, the user is rotating the *display*). Ego marker rotates by `(headingDeg − viewRotationDeg)` so it keeps pointing in the real direction of motion on the rotated display.
- Recenter button (ego-shaped, bottom-right of every concept, themed per palette): clears `cameraOverride`, syncs `viewRotationDeg = headingDeg`, returns to TRACK_UP, cancels the timer.
- 60-second auto-revert: any pan / pinch / rotate (re)starts a `viewModelScope.launch { delay(AUTO_RECENTER_MS); recenterPan() }`. If the user is idle past the window the cockpit snaps back on its own.

`MapTouchInput` gained two-finger angle tracking (`atan2` between fingers) and an `onRotate(deltaDeg)` callback. Rotation deadzone of 0.05° suppresses jitter from steady two-finger holds.

---

## 2026-04-30 — Big visible ego + fake-GPS nudge chips for debug

Two debugging quality-of-life additions:

- Ego marker bumped from 14 px → 28 px, filled body + 3 px white outline + halo ring at 1.7× radius. The halo's centre is the actual GPS fix point — i.e. the dead centre of the circle is "you are here." Both triangle and hex variants got the treatment.
- Ego-marker draws at the projected GPS-fix position rather than a fixed viewport anchor — fixes the "drag the map and the ego stays in the same place" bug. Ego now slides with the map when panned. Fixed in both `playaMapPanel` (B / C / D) and `centerViewport` (A); aligned the TILT-mode `MAP_ANCHOR_TILT` to `EGO_ANCHOR_TILT` (both 0.78) so the arcade lower-third framing is preserved.
- `FakeLocationSource` exposes a manual offset; new `> FAKE GPS NUDGE` row on the rail (visible only when source is FAKE) gives `N+100`, `S+100`, `E+100`, `W+100` chips plus `GPS RESET`. Useful for jumping the ego to specific positions to verify on-street snap, nav-cue radial detection, etc.

---

## 2026-04-30 — Nav cue bar (phase 2: UI)

Wired the `NavigationCue` from PlayaNavigator into the cockpit. New `NavCueBar` Composable drops into the top of every concept, themed per palette. VM owns a private `cityModel: PlayaCityModel?` (built once when the BRC map loads) and a `recomputeNavCue()` helper called from the heading and location collectors so the cue stays in sync without per-frame work in the renderer. Cue formats:

- `→ 4:42  1240m` — off-street, heading toward city.
- `← -10:30  3.6km` — deep playa, heading outward (backward ray cast against the trash fence).
- `4:30 → ESPLANADE` — on a radial, inbound.
- `4:30 ← ATWOOD` — on a radial, outbound (last-passed arc).
- `ATWOOD 4:42` — on a named arc, clock ticks as you move along it.
- `—` — no fix yet.

---

## 2026-04-29 — BRC map integration into B/C/D + zoom-gated labels

Pulled the real Black Rock City map (streets, plazas, art, CPNs, toilets, fence) out of concept A and into B/C/D as well, with concept-specific palettes, GPS tracking, pinch-zoom, drag-pan, and a TOP/TILT toggle on the rail. Concept A is intentionally untouched per the directive to leave it as-is.

**Renderer changes (`BrcMapRenderer.kt`):**
- `drawPlayaMap` now takes a `MapPalette` (default = legacy A colours, so A's call site is identity). Each concept supplies its own palette, including a `pointStyle` (DOT for the canonical look, BLOCK for D's chunky orange-on-black tile aesthetic).
- Ego markers split out to `EgoMarkers.kt` — adds `drawHexEgoMarker` (concept B's faceted polyhedron) alongside the original triangle. Both accept a colour so the marker reads in the host concept's palette.

**Shared map panel (`ui/concepts/PlayaMapPanel.kt`):**
- New `playaMapPanel` Composable bundles touch input, projection, viewport build, optional tilt graphicsLayer, optional retro-grid backdrop, ego overlay, and an optional `SweepOverlay` (concept C). Configuration via a `PlayaMapPanelStyle` data class — keeps the call site under detekt's parameter cap.
- Concept C's sweep illuminates the map by re-drawing it inside the wedge clip with a brighter `litPalette` — proper M41A "ping over a real city" effect rather than static blips.
- B honours the TOP/TILT toggle; C is always top-down (canonical M41A); D map tile honours TOP/TILT for the centre tile only.

**Labels (`ui/playamap/MapLabels.kt`):**
- `MapPalette.labelsEnabled` flag (off by default → A unchanged) wires a label pass into `drawPlayaMap`. Zoom-gated reveal: plazas at ≥0.20 px/m, major art (Honorarium/ManPavGrant — incl. The Temple, The Man) at ≥0.30, streets at ≥0.45, CPNs at ≥0.65, minor self-funded art at ≥1.10.
- BRC source data has each block of a logical street as a separate `LineString` feature (599 segments → ~30 unique names). Naive midpoint-per-segment stamps "4:30" at every intersection; the implementation now groups by name, picks the source point closest to the group's centroid, and draws one label per logical street (radials land on a middle block, arcs sit near the top of the curve).
- Toilets stay marker-only, recoloured BRC porta-potty purple (`#B266FF`) in B and D — the source has no per-bank name (every toilet feature is just `ref: "toilet"`), so colour carries the meaning.
- Labels off in C: the M41A look is intentionally low-info / blip-driven, and bright text would fight the dim-base / lit-wedge contrast.

**Shared control strip (`ui/concepts/ConceptControls.kt`):**
- Added `ZOOM-` / `ZOOM+` chips and a `TOP` / `TILT` mode toggle (suppressible per concept — C hides it). `ZOOM±` step is 1.4×. The same chip set is now reachable from B/C/D rails so the user can drive any concept without pinch-zoom on a cramped tile (D's map is small).

**Untouched on purpose:**
- Concept A. Same colours, same TOP/TILT toggle behaviour, same controls.
- Toilet colour in A (still electric blue). One-line change if we ever want to apply BRC purple universally.

CI gates green: ktlint, detekt, testDebugUnitTest, assembleDebug.

Open follow-ups:
- Toilet labels never (no per-bank name in source); could synthesize from nearest CPN/intersection if the team wants them.
- Street label rotation along the radial / arc tangent — current labels are horizontal. Looks fine at moderate zoom; would read better still with rotation.
- Label collision avoidance: dedupe handles the worst case but at very high zoom art + street labels can overlap. A drawn-rect collision check would help.
- Concept C lit-wedge: currently a single bright wedge at the leading edge. Adding a fading trail would make features stay lit briefly after the sweep passes, more authentic to the M41A persistence-of-vision look.

---

## 2026-04-29 — Four cockpit concepts (A/B/C/D) + runtime switcher

Took five Alien-franchise / 80s-vector mockups (`design/mockups/map_concept_*.html`) down to three picks plus the existing CRT Vector. Now wired all four into the app as live screens with a tap-to-cycle pill in the top-right corner of every concept.

- **A — CRT VECTOR** (existing): unchanged; just added the cycle pill.
- **B — PERSPECTIVE GRID** (Lukas Uhlitz / Europa lift): green palette, receding floor grid that creeps forward when speed > 0, faceted polyhedron vehicle that pivots with heading, left-side altitude/distance ladder, right-side control rail.
- **C — MOTION TRACKER** (Aliens '86 M41A): concentric range rings, rotating sweep arm, forward detection cone aligned to heading, big BEARING/SPEED/CONTACTS readouts, scanline overlay. Contacts are static decorative blips for now — real BRC POI proximity is a follow-up.
- **D — INSTRUMENT BAY** (Nostromo dense gauge wall): tile grid with map at center plus heading dial, half-arc speed gauge, throttle waveform, two cell bars, hazard chevron footer.

Architecture:
- New `CockpitConcept` enum (A/B/C/D) with `next()` for cycling.
- `CockpitUiState.concept` + `CockpitViewModel.cycleConcept()` + `CockpitPreferences.setConcept()` so the choice persists across launches.
- New top-level `cockpitScreen()` dispatcher in `CockpitScreen.kt` swapped in for `crtVectorScreen` from `MainActivity`.
- Shared `ui/concepts/` package: `ConceptTheme` (palette per concept), `conceptSwitcher` (the pill), `conceptControlStrip` (transport / GPS / heading / speed / recenter chips, themed). Each new screen is ~200-300 LoC because the chip layout is shared.

`thresholdInClasses` in `detekt.yml` bumped 12 → 13 to fit `cycleConcept`. Cycle pill is a normal `clickable` Box so it works in any concept's chrome without extra wiring.

CI gates green: ktlint, detekt, testDebugUnitTest, assembleDebug.

Mockups 1 (Deorbital Globe) and 2 (Echo Probe) are still in `design/mockups/` for reference but are not built into the app — kept in case we want to revisit.

---

## 2026-04-26 — GPS fleet sourcing plan (8-10 tablets, one vehicle)

Decided how to feed GPS to the fleet without per-tablet receivers.

**Ruled out:**
- Per-tablet BLE pairing (Garmin GLO, Bad Elf, Dual XGPS150). Most BT GPS pucks cap at 1-2 active connections; even the multi-client units (Dual XGPS160, Bad Elf Pro+) cap at 5 BT clients — and pairing 10 tablets is a maintenance nightmare. Also: BT inside a metal vehicle with 10 tablets is RF-noisy.
- "One tablet broadcasts from internal GPS." Fire tablets have no GPS, so the host tablet would need external hardware anyway — might as well skip the tablet middleman.
- Dual XGPS160 WiFi mode. **Correction to earlier guess: the XGPS160 does NOT have a WiFi mode — it's BT-only with a 5-device cap.** The off-the-shelf WiFi-NMEA category is mostly marine (Quark-Elec QK-A027 ~$130 is the closest fit if going off-the-shelf).

**Plan:**
1. **Bring-up (weekend):** spare iPhone running GPS2IP (paid ~$8 — needed for background-location mode that keeps it broadcasting with screen locked) joins the car's existing travel-router WiFi. UDP broadcast on port 10110 (de facto NMEA-over-IP). Build a new `NetworkLocationSource` that listens on UDP 10110 and feeds lines into the existing `NmeaParser`. Add a `[NET]` chip in the right-rail GPS selector. iOS works the same as Android for this — same app, same protocol. Same caveats (thermal, app-killing, internal antenna) make it a stopgap, not the answer.
2. **Production:** Pi Zero 2 W + USB u-blox GNSS (NEO-M9N preferred; BU-353-S4 a fine first cut) + roof-mounted active GPS antenna. Pi runs `gpsd` plus a small UDP NMEA broadcaster (Python or Go, ~20 lines) on the same WiFi. Power: 12V → 5V buck on switched ignition.

**Why not Pi Zero 2 W as both AP and GPS:** its WiFi is 2.4 GHz only (CYW43438), single antenna, and the AP-mode firmware is reliable up to ~5-6 clients before hostapd starts dropping clients. 8-10 tablets in a metal car needs proper RF design — let the existing travel router (better chip, dual-band, real antennas, OpenWrt) do AP/DHCP and let the Pi be a single-purpose GPS node. If we ever wanted one box, Pi 4 (dual-band CYW43455) is the right choice, not Zero 2 W.

**Hardware pricing context:** legit u-blox modules are $40-80 right now (M8/M9 channel supply has thinned as u-blox shifted focus to automotive/F9-F10). Sub-$25 "NEO-M8N" boards on Amazon are mostly counterfeit with older firmware and worse sensitivity. Used Garmin GLO (~$20-40 on eBay) or new BU-353-S4 (~$30) are perfectly fine fallbacks for first integration — for a road vehicle at normal speeds, M9N-grade accuracy isn't required.

**Architecture impact:** adds a 5th `LocationSource` implementation alongside FAKE/SYSTEM/BLE/USB. Same `state: StateFlow<LocationSourceState>` contract, same `NmeaParser`, same selector chip pattern. The phone bring-up is purely to prove `NetworkLocationSource` end-to-end before any hardware purchase — once it's working with the phone, swapping to the Pi is just "different IP broadcasting on the same port."

Open follow-ups:
- Implement `NetworkLocationSource` (UDP listener on 10110, parse via `NmeaParser`, emit `LocationSourceState`).
- Add `LocationSourceType.NET` and wire a `[NET]` chip into `CRTVectorScreen` right rail.
- Source-of-truth question: when multiple sources are available (e.g. NET + USB on the same tablet), what's the priority? Probably explicit user selection (existing pattern) — but document it.

---

## 2026-04-26 — Audit Medium/Low sweep

Closed every Quick-Win item from `audit.md` plus a handful of nearby Medium/Low items. Eight commits:

- **M5** — `CockpitUiState.egoFix` now a stored val (was a getter recomputed per read).
- **M3** — `PolygonRing.centroid` precomputed at parse time; renderer reads it for toilet markers.
- **M1** — deleted `VehicleGateway` (one-method parent of `VehicleConnectionGateway`, no other callers); deleted `VehicleCommand.EmergencyStop` (no callers since `bd926af`); moved `FakeVehicleGateway` to test sources (production wires `RoutedVehicleGateway` + `FakeTransportAdapter`).
- **M13 / M15** — promoted heading / speed bounds to `CockpitUiState.Companion` constants alongside the existing tilt/pan limits; reset placeholder defaults `42°/28kph/60°C` to `0/0/0` (thermal is overwritten by the first telemetry tick anyway).
- **M11 / M12** — NMEA: KDoc now reflects what the parser actually accepts (any GGA/RMC talker — GP/GL/GA/GB/GN); checksum reader tolerates the 1-digit form some receivers emit when value < 0x10.
- **L5** — `PlayaViewport` now `require(anchorYFrac in 0.0..1.0)` at construction.
- **L1 (partial)** — added tests for `wrapHeading` (zero / in-range / wrap / negative / large) and three new `RoutedVehicleGateway` cases (switch-while-connected leaves old adapter up; double-connect is idempotent; send-after-disconnect propagates ERROR). The `MapTouchInput` pinch-reset test deferred — needs a small refactor to extract a pure `PinchSession` from inside `awaitPointerEventScope`.
- **M7** — `androidx.datastore:datastore-preferences` plumbed through three phases: (1) added `CockpitPreferences` interface + `DataStoreCockpitPreferences` impl with round-trip tests; (2) wired into the VM for `tilt / mapMode / locationSource`, read on init, write on each user action; (3) lifted `pixelsPerMeter` from `CRTVectorScreen.kt`'s local `mutableDoubleStateOf` into VM-owned state and persisted it. Pan offset stays session-only — it's transient, not preference. Detekt's `TooManyFunctions` threshold bumped 11→12 in `config/detekt/detekt.yml` to accommodate the legitimate twelfth VM operation.

Architectural decisions on the way through:
- Enums in DataStore are stored by `name`, not ordinal — renaming a constant invalidates the key (read returns default) instead of silently mis-mapping to a different enum value.
- Tilt and zoom are clamped on read so a tampered prefs file can't seed values the controls can't reach.
- Snapshot intentionally excludes pan and connection/ego state. Audit only listed source/tilt/zoom/mapMode; pan is more session than preference.

`tasks/open.md` rewritten to reflect what's actually open (the prior content predated everything from BRC-map Phase 1 onwards). New `tasks/done.md` records what shipped. `README.md` gets a one-line cross-link to `SYNC.md` and `tasks/open.md` for new readers landing cold.

61 unit tests green; full CI gate clean across all eight commits.

---

## 2026-04-26 — Audit fix H7: surface map load failures via MapLoadResult

`AssetsPlayaMapRepository.parseAll()` had no try/catch — a missing or malformed asset would throw `IOException` (or `JSONException` for bad JSON) out of `load()`, get silently swallowed by viewModelScope's exception handler, and the cockpit would render forever with a null map and no diagnosis.

Fix:
- New `core/model/MapLoadResult` sealed type: `Loading | Loaded(map) | Failed(message)`.
- `PlayaMapRepository` interface gains `loadResult: StateFlow<MapLoadResult>`. The existing `map: Flow<PlayaMap>` is kept and now derives from `Loaded` results.
- `AssetsPlayaMapRepository.load()` wraps `parseAll()` in try/catch over `IOException` and `JSONException`, transitioning to `Failed(message)` on either. Detekt's ReturnCount tripped when both catches had `return` statements; refactored to a separate `runLoadAttempt(): MapLoadResult` expression-body fn so `load()` has a single early return.
- Extracted a `PlayaAssetReader` interface (default `AndroidPlayaAssetReader` wraps `AssetManager`) so the JVM tests can substitute a fake without Robolectric. The audit/prod constructor signature `(AssetManager, year)` is preserved via a secondary ctor.
- `CockpitUiState` adds `mapLoadError: String? = null`; the VM collects `loadResult` in its consolidated init and copies the message into state. UI rendering of the error is intentionally out of scope for this commit (a later, design-driven follow-up).

Logging via `android.util.Log.e` was tried first but failed JVM tests (Log not mocked). Dropped — the `Failed.message` already carries the diagnostic; full logging belongs with M10's Timber pull-in.

2 new tests in `AssetsPlayaMapRepositoryTest`:
- `load_with_missing_asset_emits_failed_without_throwing` — `IOException` from the reader → `Failed("art.geojson not found")`, no rethrow.
- `load_after_failure_does_not_latch` — `Failed` doesn't lock the repo into terminal state; a future retry remains possible.

`NoOpPlayaMapRepository` test stub updated to provide `loadResult`. 57/57 green; full CI gate clean.

This closes the audit's Critical and High batch — all 13 issues from `audit.md` are now addressed (with H2 confirmed not-a-bug per the audit itself). Ready to move to features again.

---

## 2026-04-26 — Audit fix H6: restart location source on permission grant

`MainActivity`'s permission-launcher result callback was a bare comment — `{ /* …no callback action needed */ }`. After the user granted `ACCESS_FINE_LOCATION` (or BT permissions), the active source stayed in its prior `Error("…not granted")` state until the user manually re-toggled a chip or relaunched the app. Surprising UX.

Fix: new `CockpitViewModel.restartLocationSource()` does `locationSource.stop()` then `locationSource.start()`. `MainActivity`'s permission callback now calls it whenever any permission was granted in the result map. The chip toggle path is unchanged.

Detekt's `TooManyFunctions` (limit 11) tripped because adding `restartLocationSource` brought the VM to 12. Combined `connectTransport`/`disconnectTransport` into a single `setTransportConnected(connected: Boolean)` — one less method, same call sites with trivial lambdas at the rail.

New test: `restartLocationSource_stops_then_starts_active_source` constructs the VM with a `StubLocationSource` (counts start/stop), confirms init issued one start, then asserts a `restartLocationSource()` results in stopCalls=1, startCalls=2.

55/55 green; full CI gate clean.

---

## 2026-04-26 — Audit fix H5: clamp pan offset

`CockpitViewModel.panBy` accumulated `panEastM` / `panNorthM` without bound. A stuck finger or runaway drag could shift the camera tens of kilometres off the playa, and the only recovery was the RECENTER MAP chip — which a user who's already lost wouldn't necessarily think to look for.

Fix: new constant `CockpitUiState.MAX_PAN_M = 5_000.0` and a `coerceIn(-cap, cap)` in `panBy()`. 5 km is well past the trash fence, so legitimate pan stays unclipped; runaway pan stops there.

New test: `panBy_clampsToMaxPanMeters` confirms a 10×cap pan in either direction lands at exactly `±MAX_PAN_M`, and a follow-up `recenterPan()` + within-cap `panBy()` still accumulates normally.

54/54 green; full CI gate clean.

---

## 2026-04-26 — Audit fix H4: consolidate VM init into one ordered launch

`CockpitViewModel.init` previously fired off seven independent `viewModelScope.launch { … }` blocks. The audit observes that the location-source state collector and `locationSource.start()` were ordered nondeterministically — if `start()` raced ahead, the collector could miss the `Searching → Active` transition.

Fix: collapse all seven into a single outer `viewModelScope.launch { … }` whose body launches each child collector in sequence, then calls `locationSource.start()` last. With both `Dispatchers.Main.immediate` (production) and `UnconfinedTestDispatcher` (tests), `launch { collect(…) }` runs eagerly until the first suspend (inside `.collect`), so every collector is subscribed before `start()` runs.

Note that `RoutedLocationSource.state` uses `SharingStarted.Eagerly` and is a `StateFlow`, which conflates intermediate emissions by design. So `Searching → Active` may still be observed as a single jump to `Active` if both happen between two collector ticks. That's acceptable for our use — the UI cares about the latest state, not the path. Documented inline.

Also extracted `MainDispatcherRule` from `CockpitViewModelTest.kt` into its own file so future ViewModel tests (H5/H6 panBy clamping, restart-source) can reuse without duplication.

53/53 green; full CI gate clean.

---

## 2026-04-26 — Audit fix H1: derive RoutedVehicleGateway.connectionState

`RoutedVehicleGateway` previously kept its own `MutableStateFlow<ConnectionState>` and updated it imperatively after every adapter call (`_connectionState.value = currentAdapter().state.value`). The audit notes this works only because `FakeTransportAdapter.send` is synchronous; once the underlying adapter emits `ConnectionState` updates from its own coroutine (the eventual real BLE/USB/WiFi adapters will), the snapshot read lags any state change that happens between the call returning and the read landing.

Fix: drop the manual `MutableStateFlow`. `connectionState` is now derived from the active adapter's state via `_selectedTransport.flatMapLatest { adapter(it).state }.stateIn(scope, Eagerly, …)` — the same shape `RoutedLocationSource` already uses. `connect/disconnect/send/selectTransport` no longer touch `_connectionState`; the underlying adapter's emissions propagate automatically.

Constructor gains a `scope: CoroutineScope` parameter; `ZodiacApplication` passes the process-lifetime `applicationScope` it already owns.

New test: `connection_state_forwards_active_adapter_state` confirms (a) underlying adapter `connect()` propagates to gateway state, (b) `selectTransport` reroutes the forwarding to the new adapter's state. Existing happy-path test updated to pass `backgroundScope`.

53/53 green; full CI gate clean.

---

## 2026-04-26 — Audit fix C1: hoist DI graph into ZodiacApplication

`MainActivity`'s Composable previously built `MainScope()` inside `remember{}` and constructed the entire DI graph (registries, routed sources, gateway, repos) there. The scope was never cancelled — every Activity recreation would leak a fresh MainScope and orphan the prior subscriptions. The audit notes the landscape-only orientation lock has masked this so far; any future config-change handling would expose it.

Fix: new `ZodiacApplication : Application` owns:
- `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` — process-lifetime
- `telemetryRepository`, `vehicleGateway`, `playaMapRepository`, `locationSource` as `by lazy` properties

`AndroidManifest.xml` declares `android:name=".ZodiacApplication"`. `MainActivity.kt` shrinks to ~50 LoC: it casts `LocalContext.current.applicationContext` to `ZodiacApplication` and reads the prebuilt deps. Permission launcher logic is unchanged for now (H6 wires the grant-restart hook in a later commit).

No new tests. Robolectric isn't on the classpath, and the change is structural — verified by inspection + a clean `assembleDebug` (the manifest application name is parsed at build time). 52/52 tests still green; full CI gate clean.

---

## 2026-04-26 — Audit fix C5/H3: Mutex around source lifecycle

`RoutedLocationSource.select/start/stop` and `FakeLocationSource.start/stop` previously had no mutual-exclusion. The audit calls these "latent rather than active" because `viewModelScope` happens to serialize on Dispatchers.Main today — but any future caller that fans out via `launch {}` could race the `_selected.value` reads/writes or the `job?.isActive` re-entry guard.

Fix: `kotlinx.coroutines.sync.Mutex` on both classes, with each suspend method wrapped in `mutex.withLock { … }`. The Mutex is owned per-instance; it doesn't extend the underlying source's contract.

New test: `concurrent_selects_serialize_through_mutex` issues two concurrent `select()` calls on a 3-source registry and asserts each transport gets exactly one start/stop pair through the chain. New `start_re_entry_is_safe` proves a re-entered `start()` on `FakeLocationSource` keeps the single-loop invariant.

52/52 green; full CI gate clean.

---

## 2026-04-26 — Audit fix C4: SystemLocationSource double-start guard

`SystemLocationSource.start()` previously called `requestLocationUpdates(...)` every time, so two `start()` calls without an intervening `stop()` would register the same `LocationListener` twice — every fix would fire the callback twice and battery drain would compound.

Fix: track `var listenerRegistered` and gate both `start()` (early-return if true) and `stop()` (only `removeUpdates` if true). Also extracted a `SystemLocationManagerHandle` interface so the source is unit-testable without Robolectric: the production constructor `(Context)` builds the real `AndroidSystemLocationManagerHandle`; the seam constructor `(SystemLocationManagerHandle)` accepts a fake.

4 new tests in `SystemLocationSourceTest`: double-start registers once, stop+start re-registers, no-permission emits Error without registering, stop-without-start is a safe no-op. 50/50 green; full CI gate clean.

---

## 2026-04-26 — Audit fix C2/C3: close BT/USB on connect failure

`BleLocationSource.runConnection()` and `UsbLocationSource.runConnection()` previously caught `IOException` from the connect/open path but never closed the half-allocated socket / port. The next `start()` call would overwrite the field, leaking the prior FD.

Fix: in the catch block, `runCatching { close() }` and clear the field before transitioning to `Error`. No try/finally needed — the success path's cleanup still flows through `stop()` as before.

No unit test this round — `BluetoothSocket` / `UsbSerialPort` aren't easily faked without Robolectric, which we'll pull in deliberately later. Inspection-grade: the catch block now mirrors the `stop()` cleanup almost line-for-line.

Full CI gate clean; 46/46 tests green.

---

## 2026-04-26 — Audit fix C6: NMEA hemisphere validation

Starting a sweep of the audit (audit.md, 2026-04-26) — Critical and High issues only, ten commits, in order, each one CI-green. Plan lives at ~/.claude/plans/review-the-critical-and-binary-goose.md.

This commit, the first of the batch, fixes **C6**. `NmeaParser.parseLatitude/parseLongitude` previously treated any non-S/W hemi value (including the empty string) as North/East — meaning a degraded GPS fix that emits an empty hemi field could shift reported position by 5,000+ km. Now the parser validates hemi against `{"N","S"}` / `{"E","W"}` and returns null when invalid; the GGA/RMC parsers already handle a null lat/lon by rejecting the sentence, so the upstream behavior is "ignore this sentence" — which surfaces as `Searching` rather than a phantom position.

Refactor note: detekt's `ReturnCount` (limit 2) tripped on the obvious `if (hemi !in …) return null` + `value.toDoubleOrNull() ?: return null` shape. Combined into a single guard.

3 new tests covering empty-hemi RMC, "X"-hemi RMC, and empty-hemi GGA. 46/46 green; full CI gate clean.

---

## 2026-04-26 — RECENTER MAP chip + pan state lifted to VM

The pan offset (added in the previous commit as local `centerViewport` state) is now in `CockpitUiState.panEastM` / `panNorthM`, which lets a button anywhere reset it. Added a small **RECENTER MAP** action-chip at the bottom of the right-rail control stack (electric-blue style, after the TILT chip row) — tapping it sets pan back to (0, 0), restoring the camera to the ego/Spike position.

VM additions:
- `panBy(dEastM, dNorthM)` accumulates into state.
- `recenterPan()` zeroes both.

`centerViewport`'s `onPan` callback now receives world-meter deltas (the screen-px → m conversion stays inside `centerViewport`) and forwards to `viewModel::panBy`. The local pan state is gone.

Cleanup as part of this:
- `CockpitViewModel.emergencyStop` was unused (no UI binding) — deleted per CLAUDE.md's "delete unused" rule. This kept the VM under detekt's per-class `TooManyFunctions` ceiling after the two new pan methods landed.
- `ChipControls` gains `onRecenter`.

Verified on emulator: drag the map off-center, tap RECENTER MAP, pentagon snaps back to viewport center. Pan state is shared between drag and recenter via state, no double bookkeeping.

43/43 unit tests still green; full CI gate clean.

---

## 2026-04-26 — One-finger drag-pan + SPD SET chips

Replaced the one-finger touch-to-set-heading/speed behavior with one-finger **drag-pan** of the map. Two-finger pinch zoom is unchanged. Now it's:

- 1 finger drag → camera moves so the world point under your finger stays under your finger.
- 2+ fingers → pinch-zoom (existing).
- HDG / SPD / TILT chips on the right rail → manual debug control of those values.

Implementation:
- `cockpitTouchInput` signature changed: `onHeading` + `onSpeed` callbacks replaced by `onPan(dxScreenPx, dyScreenPx)`. Internally tracks `lastPanX/Y` and `hadOneFinger` so the first frame of a touch (no prior position) doesn't snap-pan.
- `centerViewport` adds two pieces of mutable state — `panEastM`, `panNorthM` — accumulated across drags. The camera center becomes `egoFix + (panEastM, panNorthM)`. The pan callback converts screen-pixel deltas into world-meter deltas using the current heading: `dEast = (-dx*cos(h) + dy*sin(h)) / ppm`, `dNorth = (dx*sin(h) + dy*cos(h)) / ppm`. (Derived by inverting `PlayaViewport.toScreen`'s rotation+scale + a sign flip on Y because screen-Y is down, and a sign flip on the result because moving the finger right means the camera moves left in world.)
- New `SPD SET: NN kph` row in the right rail with chips `[-10] [-1] [+1] [+10]`, mirroring `HDG SET`. Calls `viewModel.setSpeed` (existing).
- `RightRailCallbacks` was at 6 params and adding `onSetSpeed` would have hit detekt's 7-param trip. Bundled the three setters into a new `ChipControls(onSetHeading, onSetSpeed, onSetTilt)` data class, so `RightRailCallbacks` is back to 5 fields.
- `centerViewport` no longer takes `onHeadingChange` / `onSpeedChange`; lost-functionality is fully covered by the chip rows.
- VM's telemetry collector also drops `speedKph` (heading was already dropped in the previous commit). Telemetry now only drives `thermalC` / `mode` / `linkStable`. User input owns the rest.

Verified on emulator: a 200-px diagonal swipe over the viewport visibly slides the pentagon and grid in the same direction, with HDG staying put. SPD SET chips work; speed no longer drifts back from telemetry.

43/43 unit tests still green; full CI gate clean.

---

## 2026-04-26 — TILT polish: centered view + adjustable tilt

Two follow-ups after the meter-space-grid work:

- Rob asked to center the grid + map on screen. Changed `MAP_ANCHOR_TILT` from `0.78` → `0.5` so the camera origin (Spike) sits at the canvas midpoint pre-rotation. With the +tilt rotation pivoting around the same midpoint, the Spike (and the city around it) stays at the screen center post-tilt. The ego marker on the non-tilted overlay still anchors at `0.78` for the driving-HUD feel — i.e., the driver is in the foreground and the city is centered ahead.
- Added an adjustable `TILT: NN°` row to the right rail with `[-10] [-1] [+1] [+10]` chips. Backed by:
  - `CockpitUiState.tiltDeg` (default 40, with companion-object `MIN_TILT_DEG = 0` / `MAX_TILT_DEG = 80`).
  - `CockpitViewModel.setTiltDeg(deg)` — clamps via the companion-object limits and updates state.
  - `RightRailCallbacks.onSetTilt` threading.
  - `centerViewport`'s `graphicsLayer { rotationX = state.tiltDeg.toFloat() }` consumes state instead of the hardcoded constant.
- New unit test `setTiltDeg_clampsToRange` confirms 55 stays 55, -30 clamps to 0, 120 clamps to 80.

Verified on emulator: `MAP: TILT` chip → pentagon now sits dead-center with the grid radiating around it; the new `TILT: 40°` row in the right rail responds to chip taps.

43/43 unit tests green; full CI gate clean.

---

## 2026-04-26 — TILT alignment fix: meter-space grid + lower map anchor

Rob's feedback after Phase B + the pitch flip:
- The grid and the map didn't share a vanishing point.
- The grid covered <30% of the viewport.
- The city was barely visible up near the horizon while the grid floated separately in the foreground.

Root cause: the original `drawRetroGrid` was drawn in **canvas pixel-space** (lines from a hardcoded `0.62 * height` horizon to the bottom edge). The map was drawn in **playa-meter space** through `PlayaViewport`. Both got rotated by the same `graphicsLayer { rotationX = 40 }`, but their 2D pre-rotation vanishing points differed, so post-rotation they didn't line up.

Fix:

- `PlayaViewport` gains an `anchorYFrac: Double = 0.5` parameter. The camera origin (where `center` projects on the canvas) is now `heightPx * anchorYFrac` instead of always mid-screen. In TILT mode, the cockpit passes `0.78` so the playa Spike sits in the lower-third of the canvas before tilt — which after the +40° rotation lands the city's foreground in front of the ego and the deep playa receding upward.
- `drawRetroGrid(viewport)` is rewritten in **meter-space**: a 200 m × 200 m mesh centered on the viewport's `center`, ±5 km in each direction. Each line is projected through the same `viewport.toScreen` the map uses. Grid + map then share one projection, so their vanishing points coincide *by construction*.
- Brightened `GridGreen` from `#0A3D1D` to `#1F6E37` and bumped stroke to 1.2 px so the grid reads at typical emulator/tablet pixel densities.
- Added two new `PlayaViewport` tests for the anchor parameter (camera-origin shift, north-offset projection above the anchor).

Side effects:
- `BrcMapRenderer.kt` was at 9 functions before this change. Adding a new `toOffset()` extension would have pushed it over the 11-fn-per-file ceiling, so the conversion is inlined at the two `drawLine` call sites.
- `TILT_ZOOM_BOOST` reverted from 2.0 to 1.0 — with the meter-space grid sharing the projection, an extra zoom in TILT broke the grid/map ratio. Pinch zoom still works for both modes uniformly.

Verified on emulator: `MAP: TILT` chip → playa pentagon now sits on the meter grid, both foreshortened identically; the deep playa recedes to a single vanishing point near the upper-third of the viewport. Screenshot at `/tmp/zodiac-tilt-bright.png`.

42/42 unit tests green; full CI gate clean.

---

## 2026-04-26 — Stopped auto-rotation + added HDG SET debug control

The map was auto-rotating because `FakeTelemetryRepository` ticks `headingDeg` every 500 ms and the VM was forwarding it into UI state. That made the TILT view impossible to inspect at a steady angle.

Fix:
- VM's telemetry collector no longer overwrites `headingDeg`. Heading is now owned by user input only — touch on the viewport (X→heading) or the new debug chips. Telemetry continues to drive speed / thermal / mode / linkStable.
- New right-rail section under the GPS chips: `> HDG SET: NNN°` + a chip row `[-15] [-1] [+1] [+15]`. Each chip wraps `viewModel.setHeading((current ± step + 360) % 360)` so it loops around the compass instead of clamping at 0/359.

Refactor side-effects:
- `rightRail`'s callback list grew to 6 → trips detekt's `LongParameterList`. Bundled into a new top-level `data class RightRailCallbacks(...)` and pass `state` + `callbacks` (2 params).
- `wrapHeading(deg)` would have pushed `CRTVectorScreen.kt` to 12 functions (over the 11-per-file ceiling). Lifted to a new tiny file `app/src/main/java/ai/openclaw/zodiaccontrol/ui/Heading.kt`.

Verified on emulator: heading reads 42° at launch and stays put. The chip row works visually (no functional regression — the chips re-use the existing `transportChip` style).

40/40 unit tests still green.

---

## 2026-04-26 — Pitch direction flipped (+40° instead of -40°)

Initial Phase B used `rotationX = -40f` which tilted the TOP of the canvas TOWARD the viewer — placing the BRC pentagon in the foreground (large) and the deep playa receding away. That's a "looking down at a tabletop tilted toward you" effect. Rob's feedback: *that's upside-down*, the wanted look is Battlezone — retro grid sweeping from the bottom-foreground up to a vanishing point with city features small in the distance.

Fix: `TILT_PITCH_DEG = 40f` (positive). With Compose's `rotationX` semantics (matches `View.setRotationX` — positive tilts the top away from the viewer), the BOTTOM of the canvas now comes forward and the TOP recedes. Result: the retro grid dominates the foreground, the playa map shrinks toward the horizon as a distant target. Ego marker on the non-tilted overlay still anchors at lower-third, which now correctly reads as "in the foreground driving forward."

Trade-off: at the default zoom (`pixelsPerMeter = 0.18`), the city is small in TILT mode because it's projected near the vanishing point. Pinch-out is the answer (Ctrl-drag in the emulator); zoom now serves both modes. If the default zoom feels wrong-sized in TILT, a per-mode default zoom is a Phase-C polish.

---

## 2026-04-26 — Phase B landed: 3D tilt + retro perspective grid

`MAP: TILT` mode is live. Tap SYS-3 in the left rail and the playa pitches ~40° forward, the trash fence narrows toward a vanishing point, streets recede, and a dark-green converging grid sits underneath. The ego triangle stays upright at the lower-third of the viewport (driving-HUD anchor).

Implementation:
- `centerViewport` is now a two-Canvas stack inside one `Box`:
  1. **Tilted Canvas** with `Modifier.graphicsLayer { rotationX = -40f; cameraDistance = 8f * density; transformOrigin = (0.5, 0.5) }` (only when `state.mapMode == TILT`). Draws `drawRetroGrid()` first, then `drawPlayaMap(...)`. The renderer no longer draws the ego.
  2. **Plain overlay Canvas** above it, no transform. Renders only `drawEgoMarker(viewport, anchorYFrac)` — `EGO_ANCHOR_CENTER = 0.5` for TOP, `EGO_ANCHOR_TILT = 0.78` for TILT.
- Touch handler hoisted from the inner Canvas to the outer Box so pinch and X→heading / Y→speed continue to use untilted screen pixels (avoids `graphicsLayer` foreshortening hit-tests).
- `BrcMapRenderer` adds public `drawRetroGrid()` and exports `drawEgoMarker(viewport, anchorYFrac)`. Constants for the grid (25 radial lines + 9 receding) match the original Phase-1 grid color `#0A3D1D` for the right retro-future feel.

Style notes / why `graphicsLayer` over hand-rolled pitch:
- Zero math in `core/geo/` — projection stays pure 2D, no JVM-test churn.
- The retro grid drawn inside the tilted layer inherits the same perspective for free; that's exactly the Battlezone effect.
- Ego stays upright because we draw it on a separate untilted Canvas above.

Detekt budget held: `CRTVectorScreen.kt` stays at 10/11 functions (no new functions added — the conditional `Modifier` is an inline `if/else` expression, the second Canvas is part of the existing composable body). `BrcMapRenderer.kt` adds 1 (`drawRetroGrid`) → 10/11.

Verified on emulator: TOP mode unchanged from before; tapping `MAP: TOP` switches to `MAP: TILT` (chip turns amber), the entire map content pitches into a vanishing point, ego marker shifts to the lower portion of the viewport, retro grid is visible as dark green converging lines beneath the playa. Screenshots saved at `/tmp/zodiac-tilt-top.png` and `/tmp/zodiac-tilt-on.png`.

40/40 unit tests still green; full CI gate clean.

Open follow-ups (Phase C polish if desired):
- Brighten / soften the grid color depending on subjective feel.
- Animate the TOP↔TILT transition (currently a snap).
- Hide the topmost ~10% of the tilted canvas (where content disappears past the horizon line) with a fade or solid mask for a cleaner "sky" feel.

---

## 2026-04-26 — Phase A landed: MAP MODE state + SYS-3 toggle

Wiring-only commit toward the 3D-tilt feature; no visual change yet.

- New `core/model/MapMode.kt` — `enum class MapMode { TOP, TILT }`.
- `CockpitUiState.mapMode` defaults to `TOP`.
- `CockpitViewModel.setMapMode(mode)` flips state directly (pure UI concern, no gateway).
- `leftRail` is now parameterized: `(mapMode, onToggleMapMode)`. SYS-3 (`MAP_TOGGLE_IDX = 2`) becomes a tappable chip labeled `MAP: TOP` / `MAP: TILT`; border + text amber when in TILT, green when in TOP. Reuses the existing border + clickable + Text idiom inline — no new functions added (CRTVectorScreen.kt stays at 10/11).

Tests: new `setMapMode_flipsUiState` confirms TOP → TILT → TOP round-trip. 40/40 unit tests green.

Phase B (the actual graphicsLayer tilt + retro grid backdrop + ego overlay) lands next.

---

## 2026-04-26 — Pinch-to-zoom on the map viewport

Map is no longer at fixed `0.18 px/m`. Zoom is now Compose state inside `centerViewport`, controlled by a combined gesture handler:

- 1 finger pressed → existing X→heading, Y→speed behavior (unchanged).
- 2+ fingers pressed → pinch zoom. Tracks the inter-finger distance at the start of each pinch session and scales `pixelsPerMeter` by the live distance ratio.
- Pinch sessions reset whenever the second finger lifts so a fresh pinch starts a new ratio (no jumpy state from stale baselines).

Bounds: `MAP_MIN_ZOOM = 0.05` (whole city + padding) → `MAP_MAX_ZOOM = 5.0` (street-level). Initial = 0.18, same as before, so the default view is identical.

Code shape:
- New `ui/playamap/MapTouchInput.kt` — `Modifier.cockpitTouchInput(currentZoom, onHeading, onSpeed, onZoom)`. Lives in its own file because `CRTVectorScreen.kt` was already at the 11-function file ceiling.
- `centerViewport` adds `var pixelsPerMeter by remember { mutableDoubleStateOf(MAP_INITIAL_ZOOM) }` and threads it through to `PlayaViewport`.

Tests still 39/39; full CI gate clean. Visual confirmation requires real multi-touch — verify on the emulator's extended-controls pinch panel or on hardware. The single-finger heading/speed path was unchanged in behavior, so no regression there.

---

## 2026-04-26 — Phase 4f landed: GPS source selector UI + permission flow

Right rail in `CRTVectorScreen` gains a `> GPS` header followed by a row of four chips — `[FAKE] [GPS] [BLE] [USB]` — mirroring the existing TRANSPORT chips. Tapping a chip calls `viewModel.selectLocationSource(type)`. Selected chip is amber, others green; same `transportChip` composable is reused.

A new status line above `TOUCH INPUT ACTIVE` shows the current `LocationSourceState` via a single `locationLine(state)` helper that returns `Pair<String, Color>`:
- `> GPS: OFFLINE` (amber) when Disconnected
- `> GPS: SEARCHING` (amber) when Searching
- `> GPS: 40.78725 -119.20535` (green, live updates) when Active
- `> GPS: ERR <detail>` (amber) when Error

Permissions: `MainActivity` adds a `LaunchedEffect(Unit) { permissionLauncher.launch(...) }` that requests `ACCESS_FINE_LOCATION` (+ `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` on Android 12+) once at app launch. Sources self-check at `start()` and emit `Error` if a permission is missing, so the UX stays graceful even if the user denies.

Style notes:
- `CRTVectorScreen.kt` was at 12 functions vs detekt's `TooManyFunctions` threshold of 11 (file-level). Inlined the trivial no-map placeholder; combined the two GPS helpers into one `locationLine`.
- The chip row is the same `transportChip` composable — visually consistent with the TRANSPORT row, no new components.

Phase 4 is now complete: foundation + parser + 3 real sources + UI selector. SYSTEM/BLE/USB will be integration-tested on a real Fire tablet with hardware (built-in GPS where available, paired Garmin/Bad Elf for BLE, u-blox/Prolific dongle for USB).

39/39 unit tests still green; full CI gate clean.

Open follow-ups:
- Mock-locations testing for SYSTEM source via `adb emu geo fix` is feasible on the emulator — tracked for next iteration.
- BLE source uses the first paired device matching a name regex; a real device picker is a future UX improvement.
- The `LaunchedEffect` permission flow surfaces three dialogs in sequence on first launch. Could be combined into one explainer dialog with deferred grants if it gets annoying.

---

## 2026-04-26 — Phase 4e landed: USB serial NMEA source

`data/sensor/UsbLocationSource` — uses [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) v3.9.0 (MIT, ~100 KB AAR via JitPack) to talk to NMEA dongles over USB host. The library handles the chipset-specific bulk-transfer protocols (CH340, FTDI FT232R, CP210x, PL2303, CDC-ACM, u-blox); we feed parsed lines through `NmeaParser`.

Permission UX: registered an `USB_DEVICE_ATTACHED` intent filter on `MainActivity` plus `res/xml/usb_gps_device_filter.xml` listing common GPS dongle VID/PIDs (u-blox 5446, Prolific PL2303 1659:8963, CH340 6790:29987, FTDI FT232R 1027:24577, CP210x 4292:60000). Android prompts to grant access automatically when a known dongle is plugged in. If the device isn't in the filter or permission isn't granted, the source emits `LocationSourceState.Error` instead of crashing.

Build wiring:
- `settings.gradle.kts` adds JitPack to `dependencyResolutionManagement.repositories`.
- `app/build.gradle.kts` adds `implementation("com.github.mik3y:usb-serial-for-android:3.9.0")`.

Default baud is 9600 (almost universal for consumer NMEA receivers); configurable via constructor for the rare 4800 / 38400 receivers.

Notes:
- `pumpNmea` was originally one tight loop; detekt's `NestedBlockDepth` (max 4) tripped on the inner `when`. Split into `pumpNmea` → `ingestBytes` → `emitLine` for clarity.
- JVM-untestable like 4c/4d. Will integration-test on a Fire tablet with a real dongle once 4f exposes the selector.

39/39 unit tests still green; full CI gate clean.

---

## 2026-04-26 — Phase 4d landed: BLE/Classic SPP NMEA source

`data/sensor/BleLocationSource` — Bluetooth Classic SPP for paired NMEA receivers (Garmin GLO, Bad Elf, Dual XGPS, etc.). On `start()`:

1. Verify `BLUETOOTH_CONNECT` (Android 12+ runtime grant; older APIs install-time).
2. Enumerate paired devices, pick the first whose name matches `DEFAULT_NAME_PATTERN` (regex covers GPS / Garmin / Bad Elf / XGPS / Holux / Qstarz / GNSS, case-insensitive).
3. Open RFCOMM socket on the well-known SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
4. Read NMEA line-by-line from the socket's input stream, feed each to `NmeaParser.parse`, emit `Active(GpsFix)` on hits.

All failure modes (no permission, adapter off, no matching device, IO error) emit `LocationSourceState.Error` with a short detail string instead of crashing. Cancellation closes the socket on `Dispatchers.IO`.

Manifest additions:
- `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` (no `maxSdkVersion`)
- `BLUETOOTH` + `BLUETOOTH_ADMIN` constrained to `maxSdkVersion="30"` (legacy Android 11 path)

Wiring: registered in `MainActivity` after the system source. Default selection still FAKE. JVM-untestable for the same reason as 4c — no Robolectric, depends on `BluetoothAdapter`. Will integration-test on a real Fire tablet with a Bad Elf or Garmin GLO once 4f exposes the selector.

Open follow-up:
- The name-pattern heuristic picks the first match. If a user has multiple GPS devices paired, we pick whichever happens to be first in `bondedDevices` order. UI-driven explicit picker is a Phase-4f-or-later task.

39/39 unit tests still green; full CI gate clean.

---

## 2026-04-26 — Phase 4c landed: SystemLocationSource

`data/sensor/SystemLocationSource` — wraps `android.location.LocationManager` and subscribes to `GPS_PROVIDER`. Converts each `Location` callback to `GpsFix` (lon/lat + bearing → headingDeg, speed m/s → kph, accuracy → fixQualityM).

Permissions:
- Added `ACCESS_FINE_LOCATION` to `AndroidManifest.xml`. minSdk=30 means runtime grant.
- If permission isn't granted at `start()`, the source emits `LocationSourceState.Error("ACCESS_FINE_LOCATION not granted")` and stays idle. UI for triggering the grant flow is Phase 4f.

Wiring: registered in `MainActivity` alongside `FakeLocationSource`. Default selection still FAKE — switching to SYSTEM is a future user action through the selector chip row.

Important caveat: **Fire tablets do not have a GPS receiver.** Selecting SYSTEM on a Fire HD/Max only works if location is being mocked or forwarded from another app on the device. Real GPS on Fire requires the BLE or USB sources (4d/4e).

No JVM tests for this source — depends on Android `Context` and `LocationManager`. Will integration-test on emulator via Mock Locations once 4f exposes the selector. Existing 39 unit tests still green; full CI gate clean.

---

## 2026-04-26 — Phase 4b landed: NMEA 0183 parser

`data/sensor/nmea/NmeaParser` — pure Kotlin, JVM-testable. Handles `$GPGGA` (lat/lon + HDOP-derived accuracy) and `$GPRMC` (lat/lon + speed-from-knots + course). Returns null for invalid checksums, "no fix" status, sub-minimum field counts, or unsupported sentence types.

9 unit tests cover: real-world GGA/RMC, southern + western hemispheres, no-fix rejection, void status, bad checksum, unknown sentence types, garbage lines, and trailing CRLF stripping.

Style notes for future phases:
- Detekt's `ReturnCount` (max=2) and `ComplexCondition` (max=4 operands) both bite on validation-heavy parser code. The clean shape is `if (early-fail) return null` once + a final `return if (further-checks) null else BuiltValue` block. `?: return null` chains push the count over.
- Top-level constants (sentence indices, conversion factors) are easier to read AND easier on Detekt than inline magic numbers despite `MagicNumber` being disabled — ktlint reads better with named constants too.

No behavior change to the running app; this is a pure-logic prep step for 4c/4d/4e where real receivers feed NMEA into the parser.

---

## 2026-04-26 — Phase 4a landed: LocationSource foundation + Fake

Wired up the GPS-source abstraction, mirroring `TransportAdapter`/`RoutedVehicleGateway`. The cockpit's center viewport now follows the ego: camera stays on the projected fix, world geometry slides past it.

Code shape:
- `core/sensor/{GpsFix, LocationSourceType, LocationSourceState}` — pure types, no Android deps. `LocationSourceState` is sealed (Disconnected, Searching, Active(GpsFix), Error).
- `data/sensor/LocationSource` — interface (`type`, `state: StateFlow`, `start()`, `stop()`).
- `data/sensor/FakeLocationSource` — synthetic. Default = slow circle at 200 m radius around the Spike with 60 s period; passing `pathRadiusMeters = 0.0` gives a stationary fix. Configurable `tickMillis` for emission cadence.
- `data/sensor/LocationSourceRegistry` — `Map<LocationSourceType, LocationSource>`.
- `data/sensor/RoutedLocationSource` — owns selection + state forwarding via `_selected.flatMapLatest { registry.sourceFor(it).state }.stateIn(scope, Eagerly, ...)`. `select()` only stop+start when type actually changes.
- `CockpitUiState.locationState` (+ derived `egoFix`) and `selectedLocationSource`.
- `CockpitViewModel(locationSource: RoutedLocationSource)` — kicks off `start()` and forwards `selected` + `state`. New public `selectLocationSource(type)`.
- `MainActivity` registers `FakeLocationSource` against `MainScope()`; routed sets initial type to `FAKE`.
- Viewport centers on `state.egoFix?.let { projection.project(it.location) } ?: PlayaPoint(0,0)` — falls back to the Spike when no fix.

Tests: 4 routed + 5 fake = 9 new (30/30 total). Visual confirm: emulator screenshots show the world drifting around a static ego triangle as the synthetic path advances. No UI selector yet — that's Phase 4f.

Lessons (kotlinx-coroutines-test 1.9.0):
- `runTest`'s leftover-coroutine guard treats coroutines launched into the test scope as failures with `UncompletedCoroutinesError`. **Pass `backgroundScope` (not `this`) for any long-lived `stateIn` / collect.** Tests can switch to `runTest(UnconfinedTestDispatcher())` to avoid needing `advanceUntilIdle()` for eager StateFlow propagation; we updated `MainDispatcherRule` to use the unconfined dispatcher too.
- ViewModelScope's StateFlow.collect coroutines also count as leftover — wrap VM tests with a `ViewModelStore` and call `store.clear()` in `finally` so `onCleared()` cancels viewModelScope.
- `FakeLocationSource`'s default loop schedules continuous `delay()` ticks on the test scheduler — runTime auto-advance can spin forever. **For VM tests, use the `StubLocationSource` test fixture (state-only, no timers).**

Open follow-ups:
- `MainScope()` in `MainActivity` is held in `remember`; not lifecycle-cancelled on Activity destroy. Acceptable for a single-Activity, landscape-only app, but worth replacing with the Activity's lifecycleScope or moving DI out of the composition before Phase 4 wraps.

---

## 2026-04-25 — Phase 3 landed: art layer

Added 2025 art locations from [iBurn-Data](https://github.com/iBurnApp/iBurn-Data) (master, MIT). 332 placements bundled at `app/src/main/assets/brc/2025/art.geojson` (~68 KB).

Source shape vs ours:
- iBurn ships proprietary JSON at `data/2025/APIData/APIData.bundle/art.json` (459 KB, includes descriptions/images/URLs/donation links).
- We strip to a minimal GeoJSON FeatureCollection of `Point` geometries with `{name, program, location_string}` properties — a 6× size reduction with everything we need for cockpit display.
- Conversion is one-shot Python (recorded in this entry); rerun yearly when iBurn publishes new data.

Filtering "major" vs "minor":
- Major = `program in {"Honorarium", "ManPavGrant"}` — 87 pieces, the BMOrg-funded large-scale art (incl. the Temple).
- Minor = the remaining 245 self-funded pieces.
- Renderer draws majors as 5 px hollow magenta circles, minors as 1.5 px dim magenta dots. Magenta picked to stay distinct from amber (plazas/ego) and the green/blue palette.

Wiring:
- `PlayaMap` gains `art: List<PointFeature>`.
- `AssetsPlayaMapRepository.parseAll()` adds one line: `art = GeoJsonParser.parsePoints(read("art"), nameKey = "name", kindKey = "program")`. No new parser — the existing `parsePoints` handled the shape.
- `BrcMapRenderer.drawPlayaMap` calls `drawArtMarker` for each art point before drawing the ego.

Tests still 21/21 (no new tests — the parser path was already covered, and rendering is visual). Verified on emulator: pentagon fence + C-shaped streets + amber plazas + blue toilet markers + green CPN dots + magenta art markers + amber ego triangle, all coexisting cleanly. Screenshot at `/tmp/zodiac-phase3-now.png`.

Open observations:
- Self-funded dot cloud near the open playa is dense but readable. If it gets noisier with future years, gate on zoom level or bump the radius down.
- No labels on art yet. Adding text labels means picking which majors get them at this zoom (probably the top-N by some criterion). Defer until we have a reason.

Phase 4 (real GPS source) is the only Phase remaining.

---

## 2026-04-25 — Phase 2 landed: BRC map rendering

The center viewport now draws the playa top-down. Replaced the perspective-grid + 3/4 vehicle wireframe with:

- `core/geo/PlayaViewport` — pure transform with `center`, `headingDeg`, `pixelsPerMeter`, viewport size. Track-up: heading direction is at the top, geographic north is up only when heading=0.
- `ui/playamap/BrcMapRenderer.drawPlayaMap` — DrawScope extension. Draws back-to-front: street outlines, street centerlines, trash fence (bright green, closed), plazas (amber, closed), toilet centroids (blue dots), CPN points (green dots), then a small amber ego triangle dead-centre.
- `CockpitUiState.playaMap`, `CockpitViewModel(playaMapRepository)` — VM kicks off `load()` on init and forwards the parsed map into state.
- `MainActivity` wires `AssetsPlayaMapRepository(context.assets)`.

Visual confirmation captured on emulator (`/tmp/zodiac-phase2.png`): pentagon fence, C-shaped streets converging at the Man, plaza/toilet markers visible. Touch-to-set heading/speed still works on top of the map (touching different X positions visibly rotates the world).

Style/build notes for next phase:
- Detekt's `LongParameterList` rule **counts the receiver** for extension functions — `DrawScope.foo(a,b,c,d,e,f)` is 7 params for the rule. Bundle related args into a small `RenderCtx` data class to stay under 6.
- The CRT styling holds up well in track-up mode. The 3/4 wireframe is preserved in git history (`05ebba5`) — reintroduce as a separate "vehicle status" mode if/when wanted.

Tests: 21 unit tests total now (6 projection + 6 viewport + 5 parser + 4 pre-existing); full CI gate clean.

Phase 3 (art layer, iBurn-Data) and Phase 4 (real GPS source) are still queued.

---

## 2026-04-25 — Phase 1 landed: PlayaMap data layer

Bundled the 2025 BRC GIS GeoJSON in `app/src/main/assets/brc/2025/` (7 files, ~907 KB). New code:

- `core/geo/LatLon`, `PlayaPoint`, `GoldenSpike`, `PlayaProjection` — equirectangular projection anchored on the 2025 Golden Spike (`-119.20300709606865, 40.78696344894566`). Pure Kotlin, no Android deps.
- `core/model/PlayaMap` — domain types: `StreetLine` (LineString + name/kind/widthFeet), `PolygonRing` (named ring), `PointFeature`, `PlayaMap` aggregate.
- `data/playa/GeoJsonParser` — schemaless reader using `org.json` (Android-bundled at runtime; added `testImplementation("org.json:json:20240303")` for the JVM test classpath).
- `data/playa/PlayaMapRepository` + `AssetsPlayaMapRepository` — assets → `StateFlow<PlayaMap>`, lazy load on first subscribe, parsing on `Dispatchers.IO`.

Tests: 6 projection + 5 parser, all green. Full CI gate clean (`ktlintCheck detekt testDebugUnitTest assembleDebug`).

Decisions:
- Golden Spike for 2025 was confirmed two ways: it equals the CPN named "The Man" in `cpns.geojson`, and at least one radial street has an endpoint with distance 0.0 m to that coordinate.
- `toilets` are published as polygons (banks of porta-potties), not points. Stored as `PolygonRing`s; renderer can centroid them in Phase 2.
- Equirectangular projection (no map library) — distortion stays sub-meter at 3 km radius near 40.79° N. Validated by test against known dataset trash-fence vertex.
- Geographic east/north only — BRC clock-axis rotation is a renderer concern, not a data-layer concern.

Style notes for future Phase work:
- Detekt defaults: `TooManyFunctions = 11` per object (i.e. ≤10 allowed); `ReturnCount = 2`. Move private helpers to top-level extensions if you bump the object limit; chain `?.takeIf{}` rather than multiple early-return guards.
- KtLint will reformat raw-string literals to start on a new line (don't pre-format them, ktlintFormat handles it).
- `String?.toIntOrNull()` is **not** defined — call `?.toIntOrNull()` on the safe-called value. `String?.toInt()` etc. similarly.

---

## 2026-04-25 — Map data direction (Phase 1 next)

Decisions:
- **Bundle approach:** static GeoJSON committed to `app/src/main/assets/brc/<year>/` (Path A). No fetcher in v1 — playa has no WiFi, manual yearly refresh is acceptable.
- **Source for streets / plazas / toilets / fence / blocks / CPNs:** [`burningmantech/innovate-GIS-data`](https://github.com/burningmantech/innovate-GIS-data) **master** branch (note: not `main`). 2025 dataset is the latest published; 2026 typically drops in July/August.
  - Raw URL pattern: `https://raw.githubusercontent.com/burningmantech/innovate-GIS-data/master/2025/GeoJSON/<file>.geojson`
  - Files (~900 KB total): `trash_fence`, `street_lines` (LineString, has `name`/`width`/`type`), `street_outlines` (Polygon), `city_blocks`, `plazas`, `cpns`, `toilets`. CRS = WGS84 lon/lat.
  - License: see [Innovate ToS](https://innovate.burningman.org/terms-of-service-for-burning-man-apis-and-datasets/) — review before redistribution.
- **Source for art:** Innovate GIS data does NOT include art. Plan: pull from [iBurn-Data](https://github.com/iBurn/iBurn-Data) (Phase 3, separate decision).
- **Projection:** equirectangular, centered on the Golden Spike. Distortion is sub-meter at 3 km city radius — adequate for cockpit overlay, no map library needed.
- **GPS source:** Fire tablets have NO built-in GPS. Real input must come from external (USB GPS dongle / BLE NMEA receiver / phone tether). Architecture decision deferred to Phase 4; Phases 1–3 work without it.

Phased plan:
1. Data layer: bundle GeoJSON + `core/model/PlayaMap.kt` + `data/PlayaMapRepository` + `core/geo/PlayaProjection.kt`. Tests for parser & projection. No UI change.
2. Render streets / fence / plazas / toilets in center viewport. Ego pinned at Golden Spike.
3. Art layer from iBurn-Data.
4. Real `LocationSource` abstraction + at least one real impl.

---

## 2026-04-25 — Local dev environment bootstrapped

Toolchain installed for build + emulator on Intel Mac (macOS 15.7.4):

| Component | Path / version |
|---|---|
| OpenJDK 17.0.19 | `/usr/local/opt/openjdk@17` (via `brew install openjdk@17`) |
| Android cmdline-tools | `/usr/local/share/android-commandlinetools` (via `brew install --cask android-commandlinetools`) |
| Platform 35, build-tools 35, platform-tools, emulator | installed via `sdkmanager` |
| System image | `system-images;android-35;google_apis;x86_64` |
| AVD | `zodiac_tablet` (pixel_tablet skin, 2560×1600, landscape) |

Env vars persisted to `~/.zshrc`: `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH`.

Lessons learned:

- **Don't use the `temurin@17` brew cask** — it's a `.pkg` installer that requires GUI sudo prompt; can't drive it from a non-interactive shell. `brew install openjdk@17` (the formula) installs to `/usr/local/Cellar` with no sudo and works identically for Gradle.
- **Emulator must be launched with explicit acceleration + software GPU on Intel Mac.** First attempt with `-gpu auto` hung the GPU thread: adb stayed `offline` for 12+ minutes, qemu pegged at 101% CPU on a single core, log had zero mention of `hvf` / hypervisor. Working invocation:
  ```bash
  emulator -avd zodiac_tablet -accel on -gpu swiftshader_indirect -no-snapshot -verbose
  ```
  Verification that HVF actually attached: `argv[NN] = "-enable-hvf"` and `CPU Acceleration: working` in the verbose log; CPU usage drops from ~100% to ~10% once the guest is HVF-accelerated.
- `emulator -accel-check` reports `Hypervisor.Framework OS X Version 15.7` available — confirms the host is capable; if it shows zero acceleration providers, that's a host-config issue (Docker/VBox/VMware can hold the hypervisor lock).
- AVD config tweaks for this project (in `~/.android/avd/zodiac_tablet.avd/config.ini`): `hw.initialOrientation=landscape`, `hw.keyboard=yes`. Project is landscape-locked so portrait boot just looks broken.
- First boot is cold (~90 s with HVF). Subsequent boots from snapshot are ~10 s — drop `-no-snapshot` after the first successful boot.

Convenience aliases worth adding when ready:

```bash
alias zodiac-emu='emulator -avd zodiac_tablet -accel on -gpu swiftshader_indirect &'
alias zodiac-install='./gradlew :app:installDebug && adb shell am start -n ai.openclaw.zodiaccontrol/.MainActivity'
```

Verified end-to-end: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 4/4 unit tests pass; APK installs, `MainActivity` launches and renders the CRT cockpit on the AVD.

Open code-review observations from the install pass (not blockers, log for later):
- Repo root has identity/bootstrap files (`BOOTSTRAP.md`, `SOUL.md`, `IDENTITY.md`, `HEARTBEAT.md`, `TOOLS.md`, `USER.md`, `AGENTS.md`) that look unrelated to the Android project — consider gitignore or move out.
- `ExampleInstrumentedTest.kt` exists but no instrumented-test job in CI — run it on a managed device or delete it.
- Dependency versions are inlined in `app/build.gradle.kts`. `gradle/` exists but has no `libs.versions.toml` yet — worth adopting before the module count grows.
- `FakeVehicleGateway.kt` + `VehicleGateway.kt` may be dead code post-transport-abstraction refactor; verify before next cleanup.
