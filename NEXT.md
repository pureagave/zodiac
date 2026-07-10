# Zodiac — Next Up (execution plan)

**Start here, plus [`HANDOFF.md`](HANDOFF.md) (full current state) and [`SYNC.md`](SYNC.md) (history).** `main` is clean and green. The S9+ is on wifi adb (`192.168.0.253:5555`).

Current focus: **Playa Discovery via the Burning Man API — offline-first.** The user is offline for much of the burn, so the app must **cache the entire burn dataset ahead of time** (sync once at camp over Starlink) and work fully offline, with **≥ nightly** refresh when it has signal.

---

## Done (committed, `4614fc4`)
- BM API key stored in `local.properties` (gitignored) → `BuildConfig.BM_API_KEY`. API = `api.burningman.org`, `X-API-Key` header (details in memory `project_data_ecosystem`).
- `core/ops/PlayaPoi` — art projects via `gps_latitude/longitude`; camps via `frontage`(clock)+`intersection`(street letter) through the measured ring radii. Unit-tested.
- `data/discovery/BmApiClient` — `HttpURLConnection` + `org.json` (no new deps), fetches `/api/art` + `/api/camp`.
- `data/discovery/DiscoveryRepository` — **offline-first**: serves disk cache instantly, refreshes the full dataset on launch + ~nightly, keeps the last-good cache through gaps. Wired into DI (`ZodiacApplication.discoveryRepository`, year=2025).

## Phase 2 — round out the offline cache
- [ ] **Events** — add `/api/event?year=` to the sync + cache (fields: title, description, `occurrence_set` times, `hosted_by_camp`, `located_at_art`). Events have no own location — resolve via their host camp/art. Powers "what's on now".
- [ ] **Freshness indicator + "sync now"** — `DiscoveryRepository` expose `lastSyncedAt` + count; surface a small "SYNCED: N POIs · Xh ago" readout + a manual **SYNC NOW** action, so the driver can confirm the burn is cached before rolling out.
- [ ] **Guaranteed nightly** — wrap `refresh()` in **WorkManager** periodic work (survives app restarts), not just the in-process 24h loop. (Adds the WorkManager dep.)
- [ ] (Optional/later) **image caching** — download+cache thumbnail URLs for offline photos (a few MB). Not needed for the core experience.

## Phase 3 — UI surfacing (the payoff)
- [x] **RADAR contacts (lead)** — nearby `PlayaPoi` plot as blips on the M41A scope (art = pink diamond, camp = purple dot), positioned through the same viewport as the map, proximity-filtered to the scope radius (nearest 40), pulsing with the sweep. Active drive-to target = a ringed blue blip (clipped when beyond scope range). `core/ops/RadarContact.kt` (pure + tested) + `ui/concepts/RadarContactBlips.kt`. **Verified on the S9+ with real 2025 data (2026-07-10).**
- [ ] **Drive-to any POI** — generalize `NavTarget`/`driveToBar` so a camp/art can be a destination ("take me to [camp]"); add a search/pick affordance.
- [ ] **MAP markers** — render POIs as zoom-gated labeled markers on the ground-track map.
- [ ] **"Passing" callouts** — footer callout when the ego enters proximity of notable art (passenger flavor).
- [ ] **Near-me / on-now panel** — a gesture panel (like the burn-in tuning panel) listing nearby POIs + events starting soon.

## Later (separate features)
- **Weather + dust safety** — Open-Meteo (no key, lat 40.7869/lon -119.2042) + NWS alerts (zone NVZ023) → weather strip + dust risk (gusts >25 watch / >40 event) + shelter-bearing to camp. Same offline-first cache pattern (last-good + staleness).
- 2026 **Golden Spike** update (early July) → refine `GoldenSpike`, Camp, Temple, and the ring radii from the BM geocode.
- Burn-in **Phase 5** ledger; **M10** Timber logging; GPU **pixel-cache** perf (see `design/PERFORMANCE.md`).

## Constraints (don't forget)
- **Offline-first**: cache the ENTIRE burn dataset ahead of time; work fully offline; ≥ nightly refresh.
- **Embargo/ToS**: build/test on **2025** data; when 2026 lands, **hide art locations from users until gates open, camp locations until the first Sunday of build week**.
- **Colors**: green chrome / blue status / purple data / red error — **no amber**.
- Don't change navigation, GPS sourcing, or (nonexistent-in-repo) messaging internals.
- **Testing display discipline** (memory `feedback_s9_testing_display`): sleep the display after every screenshot; keep brightness manual + low (20).

## Build / deploy / test quick-ref
- Env inline: `JAVA_HOME=/usr/local/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=/usr/local/share/android-commandlinetools`.
- CI gate: `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug`. Run `ktlintFormat` in a **separate** invocation (it races the check).
- S9+: `adb -s 192.168.0.253:5555 …`; `installDebug` + `am start`; `input` uses **landscape** coords; grant perms via `pm grant`; desktop-mode/taskbar overlaps the app bottom (screencap captures the app framebuffer without it).
- Live-test the BM API: `curl -H "X-API-Key: $(grep '^BM_API_KEY=' local.properties | cut -d= -f2-)" "https://api.burningman.org/api/art?year=2025&search=temple"` (Bash needs `dangerouslyDisableSandbox` for network).

---

## Resume prompt (paste after `/clear`)
> Continue the Zodiac playa-discovery feature. Read HANDOFF.md and NEXT.md first, then start Phase 3: RADAR contacts (nearby art/camps as blips on the scope). Deploy/verify on the S9+ over wifi.
