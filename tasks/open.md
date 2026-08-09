# Open

What's worth doing next. Critical and High audit items are all done — see `done.md`.

> **Handoff, 2026-08-08 (evening).** The three sections below are the live
> picture. Everything under them is older backlog. Read the top of `SYNC.md`
> first — it carries the *why*.
>
> **app 719 / beacon 35 / jetson 362 tests, main green.**

## 🔬 Android audit 2026-08-09 — full findings in [`docs/AUDIT-2026-08-09.md`](../docs/AUDIT-2026-08-09.md)

Four Fable reviews (beacon / GPS+fallback / telemetry pipeline / offline
resilience). ✅ = verified against the code by hand. Evidence and `file:line`
for every item are in the audit doc; this is the work list.

### P0 — safety-critical

- [x] **A1 FIXED 2026-08-09. The forward BRAKE alert was dead on a real drive.** The speed gate
      reads `uiState.speedKph`, whose only writer is the debug SPD chip
      (`CockpitViewModel.kt:262,446`). GPS speed never reaches it, so on a real
      drive it is 0 and `! BRAKE !` / `! COLLISION COURSE !` can never fire. The
      rear alert has no speed gate, which is why the bench test passed. Fix
      needs a **single owner for vehicle speed** (GPS fix → `$ZTLM` → debug).
      Fixed: `CockpitUiState.effectiveSpeedKph` is now that owner (measured GPS
      speed beats the commanded chip), GPS speed is folded into state alongside
      heading and held across sentences that omit it (GGA carries none), and the
      alert flow now re-evaluates on **speed change as well as threat frames** —
      `driverAlerts` only recomputed per upstream frame, so a vehicle
      accelerating past the threshold with an unchanged contact list would never
      have re-run the gate. 2 tests, 5 mutants killed.
- [x] **A2 FIXED 2026-08-09. The deployed build showed fabricated contacts.**
      `ZodiacApplication.kt:237` never passes `demoEnabled`, which defaults true,
      so a dead feed silently becomes three invented moving people on the
      driver's HUD — one cycling `collision=true`. **Fix with A1**: fixing the
      brake gate alone makes demo mode flash braking imperatives at phantoms.
      Fixed by **removing the default** from `demoEnabled` so no call site can
      forget to decide; production passes `false`, and the six demo-behaviour
      tests now say `demoEnabled = true` explicitly. The compiler enforces it.
- [x] **A3 FIXED 2026-08-09. The S9+ GPS fallback could freeze `Active` forever.** `FixFreshness`
      appears 4× in BLE and USB and **0× in `SystemLocationSource`** — the
      failover target. A frozen fix satisfies the failover's liveness check
      indefinitely, and the no-badge decision means nothing on screen says you
      are on backup. Fixed: `SystemLocationSource` now carries `FixFreshness`
      plus a watchdog on the `BleLocationSource` pattern, with an injectable
      clock. Provider-disabled is surfaced distinctly as
      `Error(ADAPTER_UNAVAILABLE)` rather than as staleness — staleness is
      transient and may self-heal, a switched-off provider will not — which the
      existing `gpsStatusLabel` already renders, so no UI change. The handle seam
      was reshaped to speak `GpsFix` instead of `android.location.Location`,
      because that type is unconstructible in these JVM tests, which is exactly
      why the previous eight tests never delivered a fix and how this survived
      719 green tests.

### P1 — crash loops and unattended recovery

- [x] **B1 FIXED 2026-08-09. Corrupt DataStore prefs → boot crash-loop**, unrecoverable on a
      kiosked tablet (no Settings). Fixed with a `ReplaceFileCorruptionHandler`
      behind an extracted `cockpitPrefsDataStore()` factory (the test seam), plus
      an `IOException` catch in `read()`/`readBurnInConfig()` for disk errors that
      are not corruption. **Recovery default changed FAKE → NET**: a kiosked
      tablet that comes back silently rendering a plausible synthetic map parked
      at Golden Spike is worse than one that comes back on real GPS or visibly
      SEARCHING. Note: recovery also resets `passengerMode`, so a corrupted
      passenger tablet returns as a driver cockpit — it comes back *running*, and
      the role flip is one hidden-panel action.
- [x] **B2 FIXED 2026-08-09. `BurnInConfig.coerced()` threw at the ceiling** —
      `coerceIn(MAX+1, MAX)` is an empty range. Fixed by reserving headroom
      (`MAX_DIM = MAX-2`, `MAX_DEEP = MAX-1`) so the invariant
      `1 <= dim < deep < sleep <= 86_400` holds across all of `Long`, with the
      totality argument in a comment. Pinned by a 1000-case edge grid.
- [ ] **B3 ✅ Beacon has no boot receiver** — a brownout leaves the fleet with no
      GPS until a human taps START.
- [ ] **B4 ✅ `WAKE_LOCK` declared, never acquired** — Doze stalls the beacon's
      tick loop; heading freezes and `$ZBCN` gaps read as a dead beacon.
- [ ] **B5 Beacon FGS types vs permissions** — location-type FGS restarted while
      backgrounded gets no fixes; mic type on Android 14+ throws when denied.
- [x] **B6 FIXED 2026-08-09.** One exception killed every synthesized beacon
      channel, silently, while GNSS passthrough kept flowing and the phone still
      said "Broadcasting". Guarded via an extracted `TickLoop` (rethrows
      `CancellationException`, lets `Error` propagate to a process restart), plus
      a pure `tickHealthLine` and a second watchdog coroutine — because the loop
      must not be the only thing able to report the loop's death. Honest limit:
      a Doze stall freezes the watchdog too (that is B4), so the banner appears
      on wake. 7 tests, all mutation-verified.

### P2 — silently wrong data

- [ ] **C1 ✅ Beacon lux defaults to 0.0 and broadcasts it** → no light sensor
      pins the whole fleet to 5% brightness in daylight.
- [ ] **C2 Auto-dim and burn-in fight over screen brightness**; auto-dim wins, so
      burn-in mitigation is defeated whenever the beacon is live. Needs an
      arbiter — one write site.
- [ ] **C3 Burn-in animation clock is an accumulating `Float`** — pixel shift
      silently freezes after ~3 days (S9+) during a 14-day burn.
- [ ] **C4 NET `stop()` freezes beacon sensors as live-looking state** — stale
      night lux holds the screen at 5% through the next day.
- [ ] **C5 ViewModel init double-starts the persisted source and leaks a
      `MulticastLock`** — on every fleet launch.
- [ ] **C6 `VTG`/`HDG` parsed as compass heading**, and a test asserts it.
- [ ] **C7 Shock events double-count** (multicast + broadcast both received);
      `ShockDetector` reports first crossing, not peak.
- [ ] **C8 Odometer counts multipath teleports** and persists them permanently.
- [ ] **C9 Discovery cache** in purgeable `cacheDir`, non-atomic write, clobbered
      by a partial fetch, no plausibility gate, zero logging.
- [ ] **C10 `mapLoadError` is dead state** — a failed map load is a blank
      viewport with no message and no retry.

### P3 — hygiene, and the structural items

- [ ] Log overflow silently uncounted; `rotate()` ignores rename failures.
- [ ] **`ThreatProtocol` drift is enforced socially** — two hand-mirrored
      implementations, zero shared artifacts. A checked-in golden corpus read by
      both suites is an afternoon and removes the category.
- [ ] NET/BLE `Error` states are terminal; no multicast rejoin after a router
      reboot.
- [ ] Beacon network targets computed once; socket never bound to the WiFi
      `Network`, so traffic can leave the wrong interface silently.
- [ ] Kiosk: provisioning with a debug APK is a signature trap and the documented
      escape hatch likely does not work; `ota_disable_automatic_update` is not an
      allowed key on API 34.
- [ ] **Beacon is exempt from the lint gate** (`abortOnError = false`) — on the
      one module where manifest/permission mistakes are fleet-fatal.
- [ ] **Seams worth extracting**: `SystemLocationSource` clock,
      `NetworkLocationSource.ingest()`, a BLE/USB `SppHandle`, and continuing the
      `BeaconNet` extraction out of the 408-line `TelemetryBroadcaster` object.

### Tests that agree with the code they test

- [x] **`Nmea.checksum` validated against itself** — `xor` → `or` passed all 35
      beacon tests. FIXED 2026-08-09 with published NMEA 0183 vectors.
- [ ] `NmeaParserTest.kt:74-78` asserts VTG parses as compass — locks in C6.
- [ ] `PlayaMapBinaryCacheTest.kt:79` reads a different filename than it writes,
      so the header/magic/truncation checks are entirely unverified.
- [ ] `NavTargetTest.kt:22-26` repeats the Temple coordinate literal.
- [ ] `SystemLocationSourceTest` never delivers a `Location` — which is how A3
      survives 719 green tests.

## ⛔ Blocked on Rob / hardware

Ranked by consequence on the playa.

- [ ] **`MotionDetector` resets a track on a single-frame dropout.** Biases
      toward false **negatives** while driving. Still the highest-value
      remaining item. Needs real bodies at real distances in front of the real
      camera. ⚠️ **Do not add tablet-side contact coasting until this is
      fixed** — coasting by id while the Jetson churns ids draws one person as
      two.
- [ ] **Detector tuning** against people at 5 / 10 / 20 m, day and night.
- [ ] **Night legibility of the surround ring on the A54** — the acceptance
      gate no measurement replaces.
- [ ] **DMX fixture — POWERED AND RESPONDING 2026-08-08.** Full chain verified
      end to end: Jetson → olad (`ftdidmx`, universe 0) → FT232R `BG03OCDS` →
      3-pin XLR → head. Pan, tilt, dimmer and strobe all confirmed under
      command. Details + the traps in `jetson/MOVING-HEAD.md` §8.
      - [x] **Mode: arrived in 9-CHANNEL, switched to 11 and re-verified.**
            Found as 9ch three ways (strobe ch5, tilt ch2, dimmer mutation ch6),
            switched via the `CHnd` menu item, then confirmed as 11ch two more
            ways (lamp lit on ch8 with ch6 at zero; ch3 sweeping ~190° with ch1
            held constant). **`TrackerConfig`'s defaults are now correct.**
            The top-level menu is undocumented in the manual and is now mapped
            in `MOVING-HEAD.md` §4.3.
      - [x] **`rPAn = no`** — pan not reversed at the fixture, so `pan_gain`
            stays **+1.0**. A sign problem during calibration is a real azimuth
            error, not a mirrored yoke.
      - [x] **Pan 540° confirmed, TILT IS 180° NOT 270° — the manual is wrong**
            (`MOVING-HEAD.md` §8.6b/§8.6e). Measured three ways: 64 DMX = 45°,
            half-scale is dead vertical, end stops antiparallel. `deg_to_dmx16`
            divides by the span, so the manual's figure scaled **every tilt
            command by 1.5×** on the axis that sets how high up a body the beam
            lands. `tilt_range_deg` now 180.0, pinned by tests that fail if the
            manual's number returns.
      - [ ] Attach the head to the vehicle, aim it, calibrate
            `pan_center_deg` / `pan_gain` / `reach_half_deg`. **Also re-derive
            `tilt_far_deg` / `tilt_near_deg`** — they were rescaled 135/160 →
            90/106.7 to preserve the physical aim across the span correction,
            but 90° is straight up on this fixture, so they are placeholders,
            not aims.
      - [ ] Turn on `--dmx ola` in `ZVISION_ARGS` (`/etc/default/zvision`).
            zvision currently runs with **no `--dmx` flag**, so it defaults to
            `none` and nothing automated drives the head. Correct until aimed.
      - [x] **Colour + gobo wheels mapped by measurement 2026-08-08**, using the
            rig's own RGB camera pointed at the ceiling (`MOVING-HEAD.md`
            §8.6/§8.7). Best on-palette slots: **blue `ch5=34` (3° off, near
            exact)**, red 14, purple 78, green 128. ~a third of the colour wheel
            is amber/yellow and **banned by the palette rule**. Gobos are 8 slots
            of 8 units; only the **triangle (28)** and **six-point star (36)**
            read as vector geometry.
      - [ ] **Optional: give the idle sound show colour.** `sound_reactive`
            currently drives only the master dimmer, so the idle head pulses
            white. The on-palette slots above are now known. Keep the **tracker**
            on open white / open gobo regardless — colour and patterns both cost
            visibility, and measured brightness (white peaked 236 at dimmer 86;
            green needed 128 to reach 154) says a filter is expensive.
      - [x] **ch9 speed direction ANSWERED 2026-08-09 — 0 is FAST.** Timed by
            camera: 0.36 s at ch9=0, 0.42 s at 128, 1.04 s at 255 — monotonic,
            2.9x spread. **We already send 0, so the default was right.** The old
            note had it backwards: raising ch9 makes the head *slower*, so it is
            the knob for making it gentler, never faster. A head that lags a
            walking contact is `pan_slew_dps`/`tilt_slew_dps` in our software.
            Took three attempts; the two failed methods are written up in
            `MOVING-HEAD.md` §8.6d because both returned plausible wrong answers.
      - [x] **`BLnd = blac` VERIFIED by camera 2026-08-09** (§8.6a) — beam gone
            and stays gone, no reset-then-auto-show. **But not instant:** still
            fully lit 1.5 s after DMX stops, dark by 3.0 s. So `blac` does not
            replace the crash fail-safe or a hardware switch — three mechanisms,
            three different failures.

- [x] **⚠️ P0 — FIXED AND DEPLOYED 2026-08-09.** `zvision/dmxpark.py`
      zeroes all 512 slots and is wired as `ExecStopPost=-...` on the zvision
      unit, which systemd runs on crash and kill — not just a clean stop. Same
      module doubles as the **software operator kill**:
      `python3 -m zvision.dmxpark`. 5 tests; the "zero everything" behaviour is
      mutation-verified (zeroing only 8 slots fails).
      - [x] **Deployed and proven 2026-08-09.** Unit copied by hand (not
            `install.sh`, which also rewrites `/etc/default/zvision` and would
            have clobbered the live camera config — md5 verified unchanged).
            End-to-end test: hot universe (3 slots, dimmer 255) → `systemctl
            kill -s SIGKILL zvision` → **0 slots hot**, beam out, service back
            up 2 s later. The crash path is real, not just wired.
      - [ ] **Hardware kill switch** — a labelled inline switch on the fixture
            within the driver's reach. The software kill and `BLnd=blac` cover
            *different* failures (zvision dead vs DMX stopped); neither covers a
            wedged Jetson still streaming a valid universe. Only a switch does.
            Document it in `MOVING-HEAD.md` §1 once fitted.

- [x] **DMX signal-loss fallback — fixed in hardware 2026-08-08, but see the P0
      above for the case it does NOT cover.** Measured:
      cut the DMX and the head resets, then runs its internal auto program —
      sweeping, cycling gobos/colours, full brightness — until signal returns.
      On the playa a Jetson reboot, an `olad` crash or a knocked XLR would turn
      the tracker light into a disco while the driver's HUD still assumes it is
      aimed at people. Cause: the `BLnd` menu item shipped set to `auto`.
      **Set `BLnd = blac`** (options are `hold` / `auto` / `blac` / `Sound`).
      Chosen over `hold` because the vehicle moves: a held beam keeps a
      fixture-frame position while the car turns, so it rakes across bystanders
      at full brightness, uncommanded, and reads as purposeful. No software
      keepalive was written — the hardware setting also covers the case where
      the Jetson itself is what died, which a keepalive cannot.
      - [x] **Both settings VERIFIED to survive a power cycle 2026-08-09**
            (`MOVING-HEAD.md` §8.6a2), re-derived by measurement rather than by
            reading the menu back. So this stays a checklist item and **not** a
            software concern — zvision does not need to detect the fixture's
            mode at startup. Blackout latency reproduced exactly (lit at 1.5 s,
            dark by 3.0 s).
      - [ ] **Add `BLnd = blac` + `CHnd = 11` to a pre-burn checklist** anyway —
            a *factory reset* is a different operation from a power cut and was
            not tested.
- [ ] **Rig azimuth calibration** per camera against the vehicle nose.
- [ ] **Pod assembly** — waiting on the germanium D20 window.
- [ ] **Cross-camera dedup** with two cameras on one moving target.
- [ ] **grr validation tests** — rescue link, `fsck.repair`, HDMI capture.
- [ ] **XCover Pro** — needs USB for the Beacon's one-time STOP→START so
      `$ZAUD` picks up the mic grant.
- [ ] **Beacon channels + auto-dim** end-to-end on the tablets.

## 🔦 Tracker-light backlog — from the Fable design review, 2026-08-09

Deliberately **not** built: Rob scoped that session to the P0 + tests. These are
behaviour changes to how the light reads to people, and that is his call, not a
defect list. Each is verified against the code, with `file:line`.

**Latent bugs (no behaviour change to decide — just wrong):**

- [x] **DONE 2026-08-09 — pan seam equivalence was applied AFTER the clamp.**
      Masked at the shipped `pan_center_deg = 270`, where every azimuth lands
      mid-range. **It bites the moment the head is calibrated** — a mount that
      puts centre at 60 clamps az −80 to 0 and aims 20° wrong, when the
      mechanically correct equivalent 340 is well inside travel. Compute
      unclamped, take the ±360 equivalent into `[0, pan_range]`, *then* clamp.
      Fixed in both halves (the caller's order *and* the seeded search in
      `nearest_equivalent_pan`, which alone still returned the unreachable
      value); 2 tests, both mutation-verified. **`pan_range_deg = 540` also
      physically verified** — end-stop to end-stop is one full turn plus a half.
      See `MOVING-HEAD.md` §8.6b/§8.6c.
- [ ] **`select_best` is dead code** (`tracker.py:111`) — no production caller;
      the shipped selector is `Tracker._pick` (`tracker.py:348`), a parallel
      reimplementation with extra rules. **~12 tests certify a function the
      light never runs.** Either delete it or have `_pick` delegate its
      stateless core to it. Until then the two can silently diverge, which is
      the "test agrees with the bug" shape this project keeps getting bitten by.

**Behaviour changes (Rob's call):**

- [ ] **Hysteresis is keyed on track id, and ids churn** (`tracker.py:364`).
      With `MotionDetector` resetting tracks on a single-frame dropout, the held
      id vanishes routinely and `_pick` falls through to a stateless `max(size)`
      — so `switch_margin` never votes and the beam ping-pongs between two
      similar-sized people. Fix without breaking the no-coasting rule:
      re-associate by **bearing** (nearest live contact to the current commanded
      azimuth, ~10-15° gate). Chooses only among contacts that exist this frame,
      so it cannot draw one person as two.
- [ ] **Douse while slewing.** Dimmer goes to 255 the same frame a target is
      picked (`tracker.py:323`) while the slew takes seconds, dragging open
      white across the crowd *and* across the driving axis. Hold dark above
      ~10-15° of pan error, fade up on arrival.
- [ ] **The "idle" sound show runs at the last tracked aim** (`tracker.py:300-307`),
      so it beat-flashes full brightness at the person it was just tracking.
      Needs a dimmer grace period on target loss, then slew to park *before*
      pulsing.
- [ ] **No dwell limit and no crowd gate.** `_pick` will pin the nearest
      bystander indefinitely, and at every stop the car will be surrounded.
      Suggested: max dwell ~8-10 s then suppress that *bearing sector* (not id),
      and above ~5 reachable contacts stop singling anyone out.
- [ ] **No reach-boundary hysteresis** (`tracker.py:108`) — someone orbiting the
      vehicle at ±90° gets dropped and re-acquired per frame. Acquire ≤85°,
      release ≥95°.
- [ ] **Rate limiting is not smoothing** (`tracker.py:39-49`) — 12°/frame at
      10 Hz passes bbox jitter straight through to a 3 kg head. Alpha-beta on
      the selected contact's azimuth, reset on target switch.
- [ ] **Tilt aims a size proxy at an unverified height** (`tracker.py:316-320`)
      with `dimmer_track == dimmer_collision == 255`, i.e. brightest at closest
      range. Consider aiming at the *feet* and derating the dimmer as size → 1.
- [ ] **Observability**: nothing distinguishes "idle, correctly dark" from
      "olad dead" from "tracking someone behind the vehicle". A rate-limited
      state line to the journal, and/or a `$ZDMX` sentence on the telemetry
      group so the cockpit can grow a tracker-light health tile. The sink
      already counts `sends`/`errors` and nothing reads them.
- [ ] **Calibration knobs have no flags** — only `--dmx-pan-center` /
      `--dmx-pan-gain` exist (`app.py:178-179`). `reach_half_deg`,
      `tilt_far/near`, park position, slew rates and `switch_margin` are all
      field numbers that currently require editing `tracker.py`. Route them
      through `config_for_channel_mode(**overrides)`, which already accepts
      them. A `--dmx-aim az=X` one-shot would make the on-vehicle calibration a
      two-number job.

## 🔌 USB device identity — measured 2026-08-09

Today every camera is pinned **by port** (`/dev/v4l/by-path/...usb-0:2.3:1.0...`,
where `2.3` is the physical port chain). Measured what identity is actually
available:

| Device | Serial | Can it be addressed by identity? |
|---|---|---|
| PureThermal | `000b0026-5103-3133-3332-3733...` | **yes** — real per-unit UID |
| FTDI / DMX | `BG03OCDS` | **yes**, and already is — olad patches it as `13-BG03OCDS-O-1`, so the dongle already survives a re-plug into any port |
| Arducam RGB | **`SN0001`** | **no** — a firmware constant, identical on every unit of this model |

**What port-pinning actually buys us:** the `az=` binding. A camera spec ties a
device to a *bearing on the vehicle*, and the port is what makes that true. If
identical cameras can move ports, a camera's entire contact set silently rotates
onto the wrong bearing — wrong threat bearings on the driver's HUD, tracker light
on the wrong person — and it fails **silently**: everything opens, frames flow,
nothing errors. So for the ring, the port is not a limitation to route around;
it is the only identity the hardware offers.

- [ ] **Move thermal + DMX to `by-id`.** Free robustness, no downside, shorter
      `ZVISION_ARGS`. The ring stays on ports.
- [ ] **Name the ports via udev** — `/dev/zodiac-cam-front` / `-left` / `-rear`.
      Same pinning, but the port→bearing map lives in one readable file instead
      of `2.3` chains in the config, and a mis-plug shows up as a *missing*
      device rather than a working camera pointing the wrong way.
- [ ] **Startup identity check**: log each camera's vid:pid + serial + resolved
      path + az; assert all specs resolve to **distinct** devices; let a spec
      declare its expected model (`expect=1e4e:0100`) so swapping a thermal for
      an RGB refuses to start rather than quietly inverting the picture.
- [ ] **Label the cables.** The realistic failure is not ports renumbering on
      their own — it is someone unplugging everything to fix something else and
      re-plugging differently. No code replaces this.

## 🎛️ Stream Deck as a physical control surface — scoped 2026-08-09

Rob has a 6–8 key USB Stream Deck. **Host it on the Jetson**: it is the always-on
box that already owns olad and the fleet bus, Linux support is `pip install
streamdeck` + a udev rule, and a tablet host would mean Android USB-host HID on
the one device the driver needs.

**No hub required.** Measured: the devkit's four USB-A connectors are fed by one
onboard 4-port hub; three are used (RGB 500 mA, DMX 90 mA, thermal 100 mA) and
**one is free**. Bus 002 is the same four connectors over SuperSpeed, not extra
sockets. **Do not re-cable the cameras to make room** — inserting a hub upstream
changes every `by-path` in `ZVISION_ARGS` (`2.3` → `2.3.1`) and the cameras stop
resolving. The camera ring is what will force the hub decision; do that and the
`by-path` re-derivation together, once, with a *powered* hub.

Being HID, the Stream Deck is found by USB vid:pid by the library — immune to the
`/dev/videoN` reshuffle trap.

- [x] **Phase 1 — BUILT 2026-08-09.** `jetson/zdeck/` + `jetson/DECK.md`.
      Six keys: BLACKOUT / LAMP / HOME / DIM- / DIM+ / COLOUR, driving the head
      through olad. Bring-up verified on the real deck (Stream Deck Mini
      `0fd9:0063`, fw 3.03.002, 6 keys 2x3, 80x80 BMP) — input and output both,
      and Rob drove the head with it. Structure mirrors zvision: pure model,
      surface behind a Protocol with a fake, hardware isolated so CI needs
      neither the library nor a deck. Fixture channel numbers are borrowed from
      `TrackerConfig` rather than restated. 42 tests, mutation-verified.
      - [ ] **Install the service on the Jetson** — `DECK.md` §2 has the runbook.
            Built but not enabled; see the arbitration blocker below first.
      - [ ] **Strain relief** on the USB connector. This rig loses USB devices
            over bumps and the runner re-enumerates, but a cable that unplugs
            itself in the dark is still a lost control surface.

- [ ] **⚠️ BLOCKER — deck vs tracker authority over the light.** The deck works
      today **only because `zvision` runs `--dmx none`** and nothing else writes
      the universe. At 8 Hz the tracker overwrites the deck within ~125 ms, so
      BLACKOUT would **flicker rather than kill** — worse than having no key,
      because the operator would believe the light was off. **Do not enable
      `--dmx ola` on both services at once.** Needs a real mechanism: a kill flag
      the tracker honours each frame, or the deck stopping the tracker outright.
      Constraint: whatever is chosen must not let a control-surface fault stall
      the threat broadcaster.
      **Fable's proposed mechanism, for when this is picked up:** a mode latch
      at `/run/zodiac/light-mode`, written atomically by zdeck only, read by
      zvision once per frame (a `stat()` on tmpfs — nanoseconds, no locks,
      cannot block, so constraint 1 holds). `TRACK`/absent = tracker owns;
      `MANUAL` = deck owns; `KILL` = nobody lights anything. In MANUAL/KILL the
      tracker **skips its send entirely** rather than interleaving frames, so
      there is no flicker. KILL is belt-and-braces: latch *and* a direct
      retrying `dmxpark` write. Staleness is deliberately asymmetric — a stale
      `MANUAL` reverts to TRACK (a dead deck must not freeze the beam), a stale
      `KILL` **stays killed** (a kill must never un-kill itself because its
      author died). `/run` clears on reboot, so cold start is TRACK.
      Rejected: olad port merging (HTP takes max, so a kill of 0 structurally
      cannot win) and deck-sends-intents-to-zvision (the kill would depend on
      the liveness of the process being overridden).
- [ ] **Deck backlog — from the Fable review 2026-08-09.** Four bugs it found
      were fixed before commit (dead `connected()`, no park on disconnect,
      untrustworthy kill, missing `StartLimitIntervalSec`); these were not:
      - [ ] **Heartbeat republish (~1 Hz while owning the light).** Today
            `publish()` is press-driven only, so anything that stomps the
            universe — notably a `systemctl restart zvision`, whose
            `ExecStopPost` parks universe 0 — kills the light while the panel
            still says LAMP ON, and it stays wrong until the next key press.
      - [ ] **Watchdog.** No `WatchdogSec`/`sd_notify`. A loop wedged inside a
            blocking vendor USB call looks healthy to systemd forever.
            `Type=notify` + `WatchdogSec` has the nice property that a hang
            becomes a kill, which runs `ExecStopPost`, which parks — it fails
            *dark*.
      - [ ] **Startup slew.** `start()` publishes immediately with pan/tilt
            128/128, so every service restart physically slews the head to
            centre unprompted. Harmless on a bench, rude on a vehicle, and it
            would stomp the tracker's aim once arbitration exists. Also
            `128/128` duplicates `home_pan_frac`/`home_tilt_frac` — derive one.
      - [ ] **BLACKOUT and LAMP-off are currently the same operation**, so one
            of six keys is redundant. Fable's suggestion: make BLACKOUT a
            *latched* KILL that holds against the tracker and needs a deliberate
            release, which is also what the arbitration latch below wants.
      - [ ] **`ExecStopPost` arg drift**: `ZDECK_ARGS` can set
            `--dmx-universe`/`--dmx-url` but `ExecStopPost` parks universe 0 at
            the default URL. Same latent mismatch in `zvision.service`.
            Harmless with one universe; note it in the env file.

- [ ] **Phase 2 — needs a decision first.** Fleet-wide actions (cycle nav target
      HOME/MAN/TEMPLE, screen blackout/stealth, mark-this-spot on the breadcrumb,
      switch the driver's concept) all require an **inbound command channel the
      cockpit app does not have** — it only ever listens (GPS, threat multicast).
      That lands next to nav/GPS/messaging, so it is a separate call, not a
      natural extension of phase 1.
- [ ] **Night discipline**: the keys are backlit LCDs, and a bright RGB panel in
      the cab fights everything the rest of this project does about night vision.
      Brightness way down, dim icons, and ideally follow the same `$ZENV` lux
      auto-dim the tablets already use.

## 🟡 Waiting on the world

- [ ] **BM 2026 art/camp placements.** `location` and `location_string` are
      null on all 332 art records; everything else (artist, hometown,
      description, programme, images) is published and already in the app.
      **When BM publishes placements, the map markers, the ART card and the
      approach detection all light up with no code change.** Re-run
      `tools/prerender_art.py` then to pick up any new pieces.
- [ ] **Fire HD 10 perf profiling** — the Fire is on the bench now, so the
      whole `design/PERFORMANCE.md` section is unblocked. Top item: GPU
      layer-promotion of the rasterised map. It's a profiling campaign
      (`gfxinfo framestats`, recomposition counts, Macrobenchmark), worth
      scoping before starting.

## 🟢 Doable without Rob

- [ ] **Android track-mirror client.** The Jetson serves the track read-only at
      `http://192.168.86.235:8087/` (index + files, GET/HEAD, traversal-proof)
      and mirrors pull. The tablet-side puller isn't written. **Understand it
      as a partial copy, not a peer** — doze and process death punch holes in
      anything an Android app records or fetches on a schedule, which is
      exactly why the Jetson is authoritative.
- [ ] **Debug screen over `RollingFileLog.tail(n)`** — the method exists and is
      tested; only a screen is missing. Also surface `droppedLines`.
- [ ] **`artNearby` / `approachingArt` share a scan.** Both walk `state.pois`
      every tick. Fine at 332 pieces; worth merging if the feed grows.
- [ ] The older M6 remainder, L8-class file-shape items, and anything left in
      the sections below.

## 🔵 Needs Rob's decision, not a keyboard

- [ ] **Internet backup destination for the breadcrumb.** S3? your own box over
      rsync/scp? Deliberately not chosen — it's a complete record of where the
      vehicle has been. Credentials would go in `local.properties` beside the
      BM key.
- [ ] **Kiosk provisioning.** `docs/KIOSK.md` has the runbook; the app supports
      it and no-ops without it. Rob is sourcing cheap screens first. **One-way
      door per tablet** (removal needs another factory reset), so provision
      only once the build has settled. Not the A54 — kiosk costs the driver
      maps/phone/camera on the one screen they might need them.

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
