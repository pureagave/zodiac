# Open

What's worth doing next. Critical and High audit items are all done — see `done.md`.

> **🆕 2026-08-13 bug-hunt catalogue → [`docs/AUDIT-2026-08-13.md`](../docs/AUDIT-2026-08-13.md).**
> 8-agent Fable+Opus fan-out, ~40 findings (8 CONFIRMED P1). Not yet triaged into
> tasks below. Fix-first: the 3 LIVE-now P1s (beacon `start()`/watchdog,
> `NetworkThreatSource` rebuild). See SYNC top for the summary.
>
> Beacon area 4 (`start()`/watchdog/`$ZAUD`) fixed on `fix/audit-beacon` — see
> SYNC 2026-08-13. One item deliberately deferred, below.

> **🆕 2026-08-13 RESILIENCE audit ("does it come back by itself after a power
> event?") → [`docs/AUDIT-2026-08-13-resilience.md`](../docs/AUDIT-2026-08-13-resilience.md).**
> Live read-only sweep of Jetson + grr + code. Headline: beacon & Jetson services
> auto-start and the multicast layer self-heals, **but** two things need a human
> after every power-on today, and the Jetson vision is currently blind. Top items:
> - [x] **RES-P1-1 FIXED + HARDWARE-VALIDATED 2026-08-13 — zvision no longer comes
>       up blind and stays blind.** Diagnosed by measurement: the camera, USB bus,
>       reboot and open code are all fine (`v4l2-ctl` streamed the thermal 30/30
>       standalone; OpenCV opens the by-path fine). The real bug is a **cold-boot
>       race** — zvision opened the thermal before the USB device was
>       streaming-ready, wedged in `select() timeout`, and had **no recovery path**
>       (it stayed blind for ~2 h, emitting false all-clear). Fix: `CameraStallGuard`
>       (`normalize.py`, 5 tests) + reopen-on-stall in `capture.py`'s `UvcCamera`/
>       `ThermalCamera` (`_open`/`_reopen`), plus a bounded read timeout.
>       **Proven on hardware:** across 5 reboots the wedge reproduced once, and the
>       journal showed `select() timeout ×2 → "reopening stalled thermal camera" →
>       77 ZTHREAT/5s healthy` (~20 s to recover vs. forever). jetson 475 green;
>       deployed surgically to `/opt/zodiac` (backups `*.bak-20260813`). **Caveats:**
>       (a) this OpenCV build ignores `CAP_PROP_READ_TIMEOUT_MSEC`, so reads still
>       block ~10 s — **threaded capture is still owed** for true loop isolation once
>       the ring is multi-camera; (b) during the wedge window (and any persistently
>       dead camera) it still emits all-clear, so **RES-P2-1 (emit BLIND) still
>       stands**, now lower-urgency since the blind window is bounded to ~20 s.
> - [x] **RES-P1-2 DONE IN CODE 2026-08-13 (device verify owed) — kiosked tablets
>       now auto-relaunch the cockpit after a reboot.** `KioskController.engage()`
>       makes the app the persistent preferred **Home** activity (via a
>       normally-disabled `KioskHomeActivity` `<activity-alias>` it enables) — the
>       reliable path, since Android blocks a `BOOT_COMPLETED` receiver from
>       starting an activity in the background. `exitKiosk()` relinquishes it.
>       Device-owner-gated, so non-provisioned devices are unaffected; :app gate
>       green (compile/ktlint/detekt/lint/tests). **Owed:** `adb reboot` a
>       provisioned tablet and confirm `mCurrentFocus` returns to the cockpit
>       (docs/KIOSK.md "Verify auto-relaunch"). ⚠️ **2026-08-18: verify can ONLY be
>       done on a Samsung.** The Fires **cannot be kiosked at all** — Fire OS's
>       built-in `com.amazon.parentalcontrols` profile owner blocks device-owner and
>       can't be removed without root (proven by exhausting every method; see
>       `docs/KIOSK.md` + SYNC 2026-08-18). So the passenger Fires get no kiosk and
>       no auto-relaunch; only the S9+/A54 can be provisioned. To verify the
>       feature, kiosk the **S9+** and reboot it. This also **kills the old plan of
>       kiosking the passenger Fires** — decide: run them unlocked, or swap hardware.
> - [x] **RES-P2-1 DONE + DEPLOYED 2026-08-19 — a down camera shows as a BLIND arc,
>       not "all clear."** New low-rate `ZCOVER` channel (separate from `ZTHREAT`,
>       same bus) carries the arcs covered by *currently-delivering* cameras;
>       `SurroundRingCoverage.rimSegments` renders each rim arc COVERED/BLIND/DEMO
>       (blind = dotted red). Built via spec (`design/RES-P2-1-blind-arc-spec.md`) →
>       Fable plan → Opus implement+tests → independent validation → human
>       split-refactor (coverage moved to a sibling object, no detekt bump). Gates
>       green (app 1047 / beacon 109 / jetson 523); **deployed to `/opt/zodiac`
>       (`ab42ec5`) + verified on the wire** (`ZCOVER;-64.0:65.0` = thermal forward
>       arc, rest of ring blind). Follow-up below: wire the RGB ring so `ZCOVER`
>       carries real per-camera coverage instead of just the thermal's arc.
> - [ ] **Wire the RGB ring into `--camera` so `ZCOVER` means something.** Today
>       only the thermal is configured, so `ZCOVER` reports one forward arc and the
>       whole rest of the ring reads BLIND (correct, but uninformative). Once the
>       ring is mounted + assigned bearings (see the camera-ring USB section), each
>       camera's arc lights COVERED and a down camera flips its own wedge to blind.
> - [ ] **Fires-only cannot set a nav destination (resilience gap).** Nav-authority
>       is Samsung-only (`!Amazon`); a Fire's drive-to is a gated no-op, and it
>       can't self-promote if no Samsung is present. If both Samsungs are lost, the
>       Fires can show the map + follow a pre-set target but can't pick a new one
>       (and have no GPS). Fix: let a Fire fall back to *local* drive-to when it sees
>       no authority on the bus. Ops mitigation meanwhile: keep ≥1 Samsung powered
>       (separate fusing). See `docs/FAILURE-MODES.md`.
> - [ ] **Order the tablet mounts** — RAM X-Grip kits + fasteners per
>       `docs/MOUNTING.md` (S9+ / A54 / Fires). Vibration is the enemy; the doc has
>       the exact Amazon list + fiberglass fastening method.
> - [x] **RES-P2-2 DONE + DEPLOYED 2026-08-13 — `olad` self-heals.** Extended the
>       `olad.service.d/override.conf` in `scripts/install-ola.sh` with
>       `Restart=always` + `RestartSec=2` ([Service]) and `StartLimitIntervalSec=0`
>       ([Unit]). **Applied to the live box** (drop-in installed + `daemon-reload`;
>       effective `Restart=always`/`StartLimitIntervalSec=0` confirmed; olad
>       uninterrupted). Backup `override.conf.bak-20260813`. Latent until `--dmx ola`.
> - [x] **RES-P3-2 (broadcast half) DONE + DEPLOYED 2026-08-13.** `ThreatBroadcaster`
>       lazily re-derives the auto subnet-broadcast target until a real (non-loopback)
>       address appears, then freezes — a Jetson that starts before its DHCP lease no
>       longer stays stuck at `127.0.0.255`. Operator overrides respected verbatim. 3
>       mutation-killing tests. **Deployed to `/opt/zodiac`; zvision restarted,
>       healthy** (backup `broadcaster.py.bak-20260813`). **Still owed:** pin
>       `--iface-ip` to the `enP8p1s0` address in `/etc/default/zvision` for
>       multicast-egress NIC selection (owner config change; the flag already exists).
> - [ ] **RES-P3-1/P3-4 [CONFIG/OWNER]** — Jetson has no persistent RTC (boots at a
>       stale epoch until NTP; journal is volatile) and is on DHCP with no
>       reservation. Enable persistent journald; add a DHCP reservation.
>
> **Owner to-do after a power event *today*:** tap Zodiac on every tablet (P1-2);
> ensure beacon power (P1-3); Jetson vision needs P1-1 diagnosed. See also RES-P2-3
> (wedged-but-streaming Jetson defeats every tracker-light fail-safe — reinforces the
> hardware kill-switch P0) and RES-P2-4 (keep the beacon credential-free, now a
> provisioning rule). Full evidence + `file:line` for all P1/P2/P3 in the audit doc.

- [x] **FIXED 2026-08-22 — `FleetLinkService` foreground service.** A minimal
      FGS (`location` type, started from `MainActivity`) keeps the cockpit *process*
      at foreground priority so the NET receivers keep receiving fleet multicast
      when the Activity is backgrounded. **HARDWARE-PROVEN on the API-36 hero:**
      backgrounded (launcher foreground) with the FGS `isForeground=true`, it
      received a wired-origin `JETSON-BG` `$ZVER` — before the FGS a backgrounded
      app received nothing. Owns no receivers (they stay in `ZodiacApplication`'s
      scope); it's purely the process-priority anchor. ⚠️ Still to verify on a Fire
      (the actual beneficiary — API 28/30, not connected at fix time; older Android
      throttles less, and the fix is the standard one, so low risk). The fleet must
      be reflashed to carry it. Original finding below (kept for the evidence):
- [ ] **App multicast RX freezes when the cockpit is backgrounded (Samsung) —
      NET GPS/threats/nav/roster all pause.** MEASURED 2026-08-21 on the fleet
      `zodiac` router: with the app *behind the notification shade*, a tablet
      received **nothing** on any `239.7.7.x` group or the `/24` broadcast — despite
      the `WifiMulticastLock` being held (logcat `acquireMulticastLock
      lockTag=zodiac-zver/-threats/-znav`). The instant the app came to the
      foreground it received the beacon, the S9+, **and** a wired-origin test
      packet from the Jetson — so it is **NOT the router** (multicast forwards fine
      every direction) and not the app's socket code; it is Android/Samsung
      background-throttling the receive path. **Transmit is unaffected** (all 3
      tablets show on the Jetson's `zver_listen` while backgrounded). Impact:
      normal op is fine (cockpit foreground; the kiosked S9+/A54-as-Home stay
      foreground), but the **passenger Fires can't be kiosked** — if their app is
      backgrounded they lose GPS (they have no own GNSS) + the threat HUD until
      refocused. **Fix: run the NET receivers (`NetworkLocationSource`,
      `NetworkThreatSource`, `NavShareReceiver`, `FleetVersionReceiver`) inside a
      foreground service** (as `:beacon` already does) so reception survives
      backgrounding; that is the only thing that makes the un-kioskable Fires
      reliable. The Samsungs are covered by `FailoverLocationSource` (own GNSS) for
      *position* even backgrounded, but still lose threats/nav-share.

- [ ] **Beacon transmit socket should bind to the vehicle WiFi `Network`, not
      wildcard.** `docs/AUDIT-2026-08-13.md` area 4, P2 SUSPECTED (code fact
      confirmed, real-world silent-drop not reproduced) —
      `TelemetryBroadcaster.kt` creates a plain `MulticastSocket()` with no
      `ConnectivityManager.NetworkCallback` / `Network.bindSocket`. With any
      second route (cellular, VPN, or the planned Jetson backup-WiFi AP —
      `design/jetson-camera-ring-usb.md` territory, see
      `project_jetson_usb_expansion` memory) both the multicast group and the
      subnet broadcast could silently follow the default network away from the
      vehicle LAN while `sentences` keeps counting up. Deferred rather than
      fixed: this is the fleet's only GNSS source, a wrong bind (transient
      Network, wrong Network mid-handover) takes it fully off the wire, and CI
      cannot exercise multi-network routing at all. **Do this alongside the
      backup-AP rollout, not before** — same milestone, same on-hardware test
      pass. Validation before shipping (see the audit doc for the full
      protocol): bring up a second route next to vehicle WiFi and confirm
      frames still egress WiFi; drop/reacquire WiFi and confirm the socket
      rebinds without permanent silence; exercise the real backup-AP handover
      and confirm the fleet keeps receiving `$Z*` throughout. Interim
      mitigation (keep it, don't change it): XCover stays airplane-mode +
      WiFi-only, which is what makes the trigger unreachable today. Sibling P3
      (`BeaconNet.kt:50-56` hardcodes a `/24` subnet mask) is related but
      separate and out of scope for this item.

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
- [x] **B3 FIXED 2026-08-09.** Boot receiver for `BOOT_COMPLETED` +
      `MY_PACKAGE_REPLACED` (a sideloaded update also kills the service), gated
      on an auto-start flag `BeaconActivity.onToggle` writes on both branches —
      boot restores the operator's last expressed intent rather than starting
      unconditionally, which would make the STOP button a lie.
- [x] **B4 FIXED 2026-08-09.** `PARTIAL_WAKE_LOCK` held for the service
      lifetime, released *after* the odometer persist. Paired with a one-time
      battery-optimization exemption prompt, because in deep Doze (stationary,
      screen off, **unplugged** — the flaky-charger case) the platform ignores
      app wake locks entirely. Both halves are required. Ops cost: ~2-4 days on
      battery if vehicle power dies, versus weeks asleep — correct trade, since a
      sleeping beacon is operationally identical to a dead one and `$ZBCN`'s
      battery percentage gives hours of warning.
- [x] **B5 FIXED 2026-08-09 (commit 7c7c00b) — typed `startForeground` via the pure `safeForegroundTypes`; a START_STICKY restart is a background start by definition. ⚠️ API 34/35 branches argued from docs, not proven on hardware.**
      backgrounded gets no fixes; mic type on Android 14+ throws when denied.
- [x] **B6 FIXED 2026-08-09.** One exception killed every synthesized beacon
      channel, silently, while GNSS passthrough kept flowing and the phone still
      said "Broadcasting". Guarded via an extracted `TickLoop` (rethrows
      `CancellationException`, lets `Error` propagate to a process restart), plus
      a pure `tickHealthLine` and a second watchdog coroutine — because the loop
      must not be the only thing able to report the loop's death. Honest limit:
      a Doze stall freezes the watchdog too (that is B4), so the banner appears
      on wake. 7 tests, all mutation-verified.

### On-device verification still owed (beacon P1s)

None of these can be proven in CI; the phone is API 29 and Robolectric models the
API contract, not real OS scheduling.

- [x] **Reboot test — PASSED 2026-08-12 (beacon side).** `adb reboot` the XCover →
      it came back with **zero phone interaction** and the beacon auto-started
      (fresh pid), logging `transmit socket open` + `targets -> 239.7.7.10,
      192.168.86.255` within the boot window. Also confirmed the airplane-mode +
      WiFi radio config survives a reboot (see SYNC 2026-08-12). ⚠️ End-to-end
      *tablet receipt* of `$ZBCN` not re-checked this session (the Mac can't
      listen — needs the Jetson listener); the transmit side is proven.
- [ ] **Sideloaded update** — install a new signed APK over a running beacon,
      confirm `MY_PACKAGE_REPLACED` restarts it.
- [x] **CONFIRMED 2026-08-13 — No lock-screen credential on the beacon phone.**
      `BOOT_COMPLETED` only fires after first unlock on FBE devices; Rob confirmed
      the XCover has no PIN/credential, so the receiver fires on cold boot. **This
      is now a provisioning requirement, not just a note: keep the beacon phone
      credential-free.** Re-verified by a reboot → beacon auto-started.
- [ ] **Battery-optimization prompt** appears once and never re-nags after a
      decline.
- [ ] **Doze / charger-loss soak** — confirm the wake lock actually prevents the
      tick-loop stall B4 targets. Cannot be simulated.
- [ ] **API 30+ two-step background-location grant** — the follow-up request
      after fine location. Untestable on API 29.
- [ ] **When a replacement phone (API 34/35) exists**: verify the typed
      `startForeground` calls succeed/degrade as `safeForegroundTypes` predicts.
      **Biggest untested-on-hardware risk in this batch.**

### P2 — silently wrong data

- [x] **C1 FIXED 2026-08-09 (commit 5a20cc1) — absence is unrepresentable; the sentence is not sent until a real reading arrives. A genuine 0.0 lux / 0.0° heading IS still emitted.**
      pins the whole fleet to 5% brightness in daylight.
- [x] **C2 FIXED 2026-08-09 (commit 5298527) — `effectiveBacklight` is the single arbiter and single write site; burn-in is a ceiling that can only reduce. `autoDim` deleted.**
      burn-in mitigation is defeated whenever the beacon is live. Needs an
      arbiter — one write site.
- [x] **C3 FIXED 2026-08-09. Burn-in animation clock was an accumulating `Float`** — pixel shift
      silently froze after ~3 days (S9+) during a 14-day burn. Widening to
      `Double` would NOT have fixed it — the problem is the magnitude of the
      value, not the increment. Now derived from a Long-nanos baseline with each
      consumer taking its own modulo (`phaseFraction`), so precision no longer
      depends on uptime at all.
- [x] **C4 FIXED 2026-08-09 (commit 5a20cc1) — one `clearBeaconReadings()` used by both `stop()` and the silence watchdog so they cannot drift; `shockCount` preserved.**
      night lux holds the screen at 5% through the next day.
- [x] **C5 FIXED 2026-08-09.** ViewModel init double-started the persisted source
      and leaked a `MulticastLock` on **every fleet launch**. `start()` now
      no-ops when the listener job is live, and a `MulticastLockHandle` seam
      (acquire/release/isHeld) makes the lock testable at all — `Context` was
      always null in tests, so that path had never been exercised.
- [x] **C6 FIXED 2026-08-09.** `VTG`/`HDG`/`HDM` were parsed as compass heading
      and *a test asserted it* — the fifth test in this project found defending
      a bug. `parseHeadingDeg` now matches `HDT` only: VTG is GPS course, and
      HDG/HDM are magnetic (~13°E at BRC) with the variation field discarded.
      Chosen over parsing the variation, since that is more code to get one call
      site right for sentences the beacon does not emit today.
- [x] **C7 FIXED 2026-08-09** (both halves, by different agents).
      App side: a byte-identical `$ZSHK` within 200 ms is one impact, not two —
      the beacon sends to both multicast and subnet broadcast and the tablet
      receives both, so the count doubled on any AP forwarding multicast (bench:
      fine; playa: wrong). Beacon side: `ShockDetector` now collects for 120 ms
      and reports the **peak** rather than the first threshold crossing, which
      was systematically under-reading severity. Adds up to 120 ms latency —
      acceptable for a logged severity readout, not for an interlock.
- [x] **C8 FIXED 2026-08-09.** The odometer counted multipath teleports
      and persisted them permanently. Now gated on fix accuracy (20 m ceiling,
      applied even to the first anchor so a poor cold-start fix cannot seed the
      reference) and on implied speed against the app's own 160 kph `SetSpeed`
      ceiling — a real highway drive to the event is never rejected, a teleport
      implying thousands of km/h always is. A rejected step does not advance the
      anchor, so a bad fix cannot poison the next comparison. Fail-closed
      throughout, because `totalMeters` is unrecoverable once written.
- [x] **C9 FIXED 2026-08-09.** Discovery cache resilience
      — moved from purgeable `cacheDir` to `filesDir` (it is the only offline
      copy of the art/camp data and had no bundled fallback, unlike the map);
      atomic temp+rename write copying `PlayaMapBinaryCache`; `refresh()` merges
      **by kind** so an art-only fetch can no longer destroy cached camps; a
      plausibility gate rejects placements >5 km from the spike (swapped or
      zeroed coordinates land thousands of km out); and the path is no longer a
      logging dead zone — one line per refresh outcome, which on a Fire is the
      only way to answer "why are there no art markers".
- [x] **C10 FIXED 2026-08-09.** `mapLoadError` was dead state
      — set, declared, and rendered nowhere, so the next silent GIS schema
      change would give a blank viewport with no message, exactly as happened on
      2026-07-30. Now surfaced over the map in the concept palette (red = fault)
      with a RETRY affordance, and logged. ⚠️ **The overlay itself is
      visual-only and unverified** — this project has no Compose UI harness. The
      state transitions are tested; the rendering, and whether the RETRY chip's
      tap conflicts with the map's pan/pinch pointer loop, need a device.

### Surfaced by the P2 fixes — not yet done

- [x] **DONE 2026-08-10** — BLE and USB `LocationSource.start()` re-entry
      re-entry** — the same shape as the NET bug fixed in C5, lower priority only
      because neither is the persisted default.
- [x] DONE 2026-08-13 — deleted both. **`locationFallbackActive` and
      `commandError` were dead state** — both set by the ViewModel, both
      rendered nowhere. Exactly the C10 shape. Rob's calls were both final (no
      backup-GPS badge, no transport that can really fail), so deleted rather
      than wired up.
- [x] **DONE 2026-08-10 — reverted to 22** (`TooManyFunctions` had been bumped 24 → 25) for `retryMapLoad`.
      This is against the project's own rule ("split the file, don't bump the
      number" — an instinct that has caught real organisation problems three
      times). The comment in `detekt.yml` already says the VM is becoming a
      god-object and is due for a split into drive-to / map-camera / GPS
      delegates. **That split is now overdue**, and the bump should be reverted
      when it happens.
- [ ] **The discovery cache's storage-location change has no test** —
      `ZodiacApplication` wiring is not unit-testable without a Robolectric
      harness this repo does not have. Verified by review and compile only.

### Surfaced by this session's work — not yet done

- [x] **DONE 2026-08-12** — `FileLogTree` overflow is now counted. A
      `Channel(256, DROP_OLDEST)` reports success on *every* `trySend`, so its
      overflow was invisible by construction — the last silent-loss channel in
      the logging path. Replaced with a hand-rolled bounded `ArrayDeque` + a
      conflated doorbell (same drop-oldest policy, same non-blocking log call),
      and every eviction bumps `droppedBeforeWrite`. Surfaced in the log viewer
      as an "N OVERFLOW" chip beside AGED OUT/DROPPED/ROTATE FAIL, plumbed
      `FileLogTree.droppedBeforeWrite` → `ZodiacApplication.logBufferOverflow` →
      `cockpitScreen` → `logViewerPanel`. 2 tests (overflow counted + happy-path
      positive control), both mutation-proved (drop-the-increment and
      count-every-push each go red). Determinism from injecting the drain
      dispatcher and never advancing it, so the buffer is *forced* full, not
      raced. app 913→915.
- [ ] **`BleLocationSource` `Error` is still terminal** — NET got capped re-bind
      plus a silence-triggered rejoin on 2026-08-10; BLE has the same shape and
      did not.
- [x] **DONE 2026-08-10** — `:beacon` had no CI at all: every step in
      `android-ci.yml` was `:app`-scoped, so beacon's 77 unit tests, ktlint,
      detekt, lint and assemble had never run on a push, on the one module that
      must survive a reboot unattended for a week. All five gates unscoped;
      verified by running them exactly as CI does and confirming
      `:beacon:{ktlintCheck,detekt,lintDebug,testDebugUnitTest,assembleDebug}`
      actually execute.
- [ ] **The beacon's API 34/35 foreground-service branches are still argued, not
      observed** — lint cannot validate them and the only beacon device on hand is
      API 29. Needs one boot test on an API 34+ phone before Burn.
- [x] **RESOLVED 2026-08-13 — Direct-boot / `BOOT_COMPLETED` before first unlock.**
      Rob confirmed the beacon XCover has **no secure lock credential (no PIN)**, so
      `BOOT_COMPLETED` fires without an unlock — the FBE first-unlock trap doesn't
      apply. A reboot this session re-confirmed the beacon auto-starts with zero
      interaction. **Standing constraint: the beacon phone must stay
      credential-free** (a PIN reintroduces the trap and would need the receiver
      made direct-boot-aware + `PREF_AUTO_START` moved to device-protected
      storage). Documented in the beacon provisioning notes below.
- [ ] **A `MapLoadController` is the next VM delegate if map loading grows** —
      `retryMapLoad` + the `playaMapRepository` collector are the one cohesive
      group still in `CockpitViewModel` after the 2026-08-10 split.

- [ ] **FLEET-1 (P1) — a fleet version monitor on the S9+ hero display.** Rob asked
      for this 2026-08-11: something that tracks whether every device is running
      the latest code, shown in one of the spare cards. On that day six devices
      were 40 / 38 / 32 / 25 commits and 9 days behind, and every one needed a USB
      cable to discover. **Blocked on FLEET-2.** Design: each node announces
      `(node, role, version, sha, build epoch)` on the existing fleet bus; "latest"
      = the newest build seen among peers, so it works offline with no server; the
      S9+ collects and flags anything older. Include the Jetson and the beacon, not
      just tablets. Silence must read as *unknown*, never as healthy. If it becomes
      a cross-language wire format, give it a golden corpus like ZTHREAT.
      **UNBLOCKED 2026-08-11** — FLEET-2 landed. Read the node's own identity from
      `BuildConfig.{VERSION_BASE,GIT_SHA,GIT_DIRTY,GIT_COMMIT_EPOCH_SECONDS}`
      (`GIT_COMMIT_EPOCH_SECONDS` is the "newest-wins" comparator; an `unknown`/
      dirty build must render as unknown, never current — `BuildIdentity.known`
      already encodes that). The Jetson still needs its own version-report path.
      **IN PROGRESS 2026-08-12** — spec at `design/FLEET-1-version-monitor-spec.md`;
      wire is `$ZVER` on **239.7.7.40:10140**. Pure, tested core done + pushed:
      `core/telemetry/FleetVersion*` (protocol, `1c74cc5`), `FleetRoster`
      (aggregator, `def9eb6`), `FleetPeerTable` (receive-side fold, `517da46`).
      **3b DONE 2026-08-20** — `data/fleet/FleetVersionReceiver` (folds `$ZVER` into
      a `StateFlow<Map<node, FleetObservation>>` via `FleetPeerTable`) +
      `FleetVersionSender` (re-broadcasts one fixed sentence every 10 s, no
      authority gate), both mirroring `NavShare*`; `FleetBus.VERSION_GROUP/PORT`
      added; 12 loopback tests; gate green (app 1059).
      **4 DONE 2026-08-20** — `data/fleet/FleetVersionMonitor` (peers + self →
      `StateFlow<List<FleetRosterEntry>>` via `FleetRoster.compute`, off
      `CockpitUiState`; 5 s recompute tick ages silent peers OFFLINE; filters the
      device's own multicast echo) wired in `ZodiacApplication` (self identity from
      FLEET-2 `BuildConfig`, emit starts at boot, one log line per roster change).
      `tools/zver_listen.py` sniffer added. 4 tests; gate green (app 1063).
      **6a DONE 2026-08-20** — `$ZVER` golden corpus
      (`protocol/version-protocol-golden.json`, 6 format + 22 parse vectors, gen by
      `protocol/gen-version-golden.py`) + Python codec `version_protocol.py`; both
      `FleetVersionProtocolGoldenTest.kt` and `test_version_protocol_golden.py`
      validate byte-for-byte (app 1067, jetson 534).
      **6b DONE 2026-08-20** — the Jetson emits `$ZVER` every 10 s: `version_report.py`
      (`self_version()` from git+machine-id+hostname, fail-toward-unknown;
      `VersionScheduler`) + `fleet_bus` VERSION constants + a 2nd `ThreatBroadcaster`
      on the version group in `app.py`. Proven via `--once -v`; jetson 542.
      **6c DONE 2026-08-20 (Rob greenlit)** — the beacon emits `$ZVER` via a
      separate `VersionBroadcaster` (own socket/coroutine; `TelemetryBroadcaster`
      GNSS path byte-untouched; `TelemetryService` starts it in its own
      `runCatching`). `Nmea.zver` is the 3rd builder, pinned by
      `BeaconVersionGoldenTest`. 13 tests; `org.json` added to `:beacon` test deps.
      beacon 122. **Every node now emits; all three implementations agree on one
      28-vector corpus.**
      **⚠️ Owed on hardware (needs fleet up):** `zver_listen.py` on the Jetson
      should now show edge box + tablets + beacon; confirm a reflash flips a device
      CURRENT within ~10 s. Beacon needs a reflash to carry 6c.
      **5 DONE 2026-08-21/22** — a **FLEET tab in the log viewer** (bottom-right
      long-press; Rob's placement call): LOG/FLEET toggle, worst-status-first rows,
      CURRENT=blue / BEHIND·OFFLINE·UNKNOWN=red, `▸`+bold = this device, build
      string per row, `N nodes · M stale` header. Wired `FleetVersionMonitor.roster`
      → `MainActivity` → `cockpitScreen` → panel (collected only while open).
      **Friendly role names** via `FleetRoleName` (HERO/DRIVER/BEACON/PASSENGER
      9·11/JETSON, display-only — wire unchanged). Verified on hardware.
      **✅ FLEET-1 COMPLETE — all 6 phases shipped.** Follow-ups only: reflash the
      fleet to carry the emit + card everywhere (beacon/Jetson/Fires still on
      `ca41bbf`); optional `zver_listen.py` friendly-name mapping.
- [x] **FLEET-2 DONE 2026-08-11 — builds are now self-identifying.** Spec at
      `design/FLEET-2-build-identity-spec.md`. Git values computed **once** in the
      root `build.gradle.kts` (`providers.exec`, failing toward unknown/dirty) and
      read by both modules via `rootProject.extra`. `versionName` is now
      `0.1.0+<sha9>[.dirty]` (visible over `adb shell dumpsys package` and in the
      app's boot log for free), plus four `BuildConfig` fields — `VERSION_BASE`,
      `GIT_SHA` (fixed 9-char so a shallow CI clone and a full local clone agree),
      `GIT_DIRTY`, `GIT_COMMIT_EPOCH_SECONDS` — so FLEET-1 announces without
      string-parsing. **Deliberately no per-build wall-clock** (would make every
      `assembleDebug`/`testDebugUnitTest` non-cacheable and adds no signal beyond
      commit epoch + dirty); `versionCode` stays 1 so `adb install -r` never trips
      a downgrade. `:beacon` had `buildConfig` off — now on. The `versionName`
      format is a contract pinned by a `core/telemetry/BuildIdentity` render/parse
      type and a test that asserts `BuildConfig.VERSION_NAME == render(the fields)`
      — the Gradle↔Kotlin seam fails red if it drifts. app 819→839, beacon 82→88.
- [ ] **The beacon still has almost no logging outside the paths touched
      2026-08-11.** It had two `Log.` calls in the whole module. Consider whether
      it needs the app's `RollingFileLog` treatment — it is the component that must
      survive a week unattended, and logcat does not persist across a reboot.

### Surfaced by the 2026-08-10 documentation audit
- [ ] **Wire the beacon phone to permanent vehicle power** — it is the fleet's
      only GNSS, so its charge state is a single point of failure for every
      tablet's position and nav. The ~2-4 day battery figure is margin for a
      failed charger, not an operating mode. (It went flat on the bench
      2026-08-11 running unplugged — documented behaviour, not a defect.)
      Confirm the vehicle supply is on a circuit that does not die with the
      ignition, since the beacon is most useful when the car is parked and
      people are milling around it.

- [ ] **`CameraMount` has no tilt/pitch term** (`mount_az_deg` only), so the bearing
      math assumes a level optical axis. Guidance added to `HARDWARE.md`: mount
      level, ≤2°. Only needed if a pod genuinely cannot be mounted level — then add
      `mount_el_deg` and rotate the bearing vector before trusting the output
      (~1 hour with tests). A 20° tilt costs ~1.6° of azimuth at a 30° bearing,
      which is the same failure class as the `fov_ref` bug and feeds the same light.


- [x] **DOC-1 — DONE 2026-08-10, and measured.** `zvision`'s `--fov-ref` defaulted
      to `h` (±80°) while the tablet carried ±64° in `SurroundRing.COVERED_ARCS`.
      The **deployed** rig already passed `fovref=d` explicitly, so the vehicle
      never carried the error — the exposure was latent, for the next camera added
      without the flag. Default flipped to `d` in `rig.CameraMount` and the CLI;
      `FovReferenceDefaultTest` pins it and both mutations were run (reverting
      either default goes red). **Confirmed physically:** a cold target at 85 cm
      off centre at 1.0 m (true 40.4°) was reported at 42.5°, implying a ~61°
      horizontal half-FOV vs the 64° assumed; the horizontal reading predicts
      32.3°, so ±80° is ruled out by observation. Rob declined further refinement
      — 61 vs 64 is inside the measurement's own slop.
- [x] **DOC-2 — DONE 2026-08-10.** `jetson/scripts/install-ola.sh` printed
      `ola_set_dmx -u 0 -d 128,0,128,0,255`, writing ch5=255 (colour auto-spin)
      and never touching the ch8 dimmer, so the head moved in the dark. Now
      `-d 128,0,128,0,0,0,0,255`, with the reason in a comment.
- [ ] **DOC-3 (P3) — Delete the stray `jetson/jetson/systemd/zodiac-track.service`.**
      A tracked duplicate of `jetson/systemd/zodiac-track.service` differing only
      in comments — and its comment falsely claims `install.sh` creates
      `/var/lib/zodiac/track`, which it does not. Nothing references it.
- [ ] **DOC-4 (P3) — `install.sh` should copy `zdeck/`, or DECK.md's step must stay.**
      `zodiac-deck.service` runs `-m zdeck` from `WorkingDirectory=/opt/zodiac/jetson`,
      but `install.sh` copies only `zvision` and `pyproject.toml` packages only
      `zvision`. Works on a git-clone-to-`/opt/zodiac` box, fails with
      `ModuleNotFoundError: zdeck` otherwise. DECK.md §2 now carries an explicit
      `cp -r jetson/zdeck /opt/zodiac/jetson/` as the interim fix.
- [ ] **DOC-5 (P3) — Reconcile the germanium-window standoff arithmetic.**
      `HARDWARE.md` sizes the D20 window with `radius ≈ standoff × tan(80°)`
      (~2 mm standoff) while the same file's fov-ref section concludes the true
      half-angle is 64°. The 2 mm figure is the conservative one, so building to
      it is safe, but the 5.7× rule should not be quoted until one half-angle is
      chosen. **RESOLVED 2026-08-10 — and it was a category error, not an
      arithmetic one.** There is no single half-angle to pick: the window is a
      *circular* aperture, so the rays that govern it are the **corner** rays at
      the diagonal half-angle of **80°**, while **64°** is the *horizontal* half
      used for bearings and coverage arcs. Both numbers are correct for their own
      job. `radius ≥ standoff × tan(80°) ≈ 5.7 × standoff` therefore stands as
      originally written, and the ~2 mm standoff for D20 is right. Sizing the
      window with tan 64° would allow ~4.9 mm and quietly vignette the image
      corners — where the widest-bearing contacts are. Remaining work is only to
      reconcile the stray "~1.7 mm" figures to the 2 mm one and to state, at each
      use, which half-angle applies and why.

### P3 — hygiene, and the structural items

- [x] **DONE 2026-08-10** — rotation discards counted from disk (`discardedLines`),
      `rotationFailures` surfaced, a write that cannot be bounded is refused.
- [x] **DONE 2026-08-10** — `ThreatProtocol` drift was enforced socially — two hand-mirrored
      implementations, zero shared artifacts. A checked-in golden corpus read by
      both suites is an afternoon and removes the category.
- [x] **NET DONE 2026-08-10** — capped re-bind + silence-triggered rejoin.
      **BLE remains terminal** and is still open.
- [ ] Beacon network targets computed once; socket never bound to the WiFi
      `Network`, so traffic can leave the wrong interface silently.
- [ ] Kiosk: provisioning with a debug APK is a signature trap and the documented
      escape hatch likely does not work; `ota_disable_automatic_update` is not an
      allowed key on API 34.
- [x] **DONE 2026-08-10** — beacon lint gate is real (`abortOnError = true`) — on the
      one module where manifest/permission mistakes are fleet-fatal.
- [ ] **Seams worth extracting**: `SystemLocationSource` clock,
      `NetworkLocationSource.ingest()`, a BLE/USB `SppHandle`, and continuing the
      `BeaconNet` extraction out of the 408-line `TelemetryBroadcaster` object.

### Tests that agree with the code they test

- [x] **`Nmea.checksum` validated against itself** — `xor` → `or` passed all 35
      beacon tests. FIXED 2026-08-09 with published NMEA 0183 vectors.
- [x] **DONE** — `NmeaParserTest`'s VTG/HDG assertion replaced with
      `rejects_vtg_and_magnetic_hdg_hdm_as_compass_heading` (commit in the C6 merge).
- [x] **DONE 2026-08-10** — it wrote `_v1` while production writes `_v2`, so the
      whole corruption half of the suite hit the missing-file path. Tests now have
      production write the cache and mutate those bytes. No production bug.
- [x] **DONE 2026-08-10** — re-anchored to the bundled 2026 GIS CPN and BRC city
      geometry (2500 ft up the 12:00 axis); MAN and HOME given the same treatment.
- [x] **DONE** — the handle seam now speaks `GpsFix`, and
      `a_delivered_fix_makes_the_source_active` is the first test in that suite
      that ever delivered one (A3 fix).

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

> **Camera-ring USB + bandwidth plan:** [`design/jetson-camera-ring-usb.md`](../design/jetson-camera-ring-usb.md)
> — measured topology, the compressed-vs-raw bandwidth rule, and the recommendation
> to hang the ring off a **powered hub on the Type-C (host mode)**, pinned by
> `by-path`. **USB-C host-mode test DONE 2026-08-13** — role flips to host, ring
> streams, and **`role=host` survives a reboot** (Type-C CC detection, no
> persistence config needed). ⚠️ **Corrected a wrong premise:** the USB-C in host
> mode is served by the **same** controller (`3610000.usb`) and **same 480 Mbps HS
> bus** as the Type-A ports — **NOT** a second independent budget. It still avoids
> renumbering Type-A `by-path`s (different root-port branch `…:1.x` vs `…:2.x`).
> SuperSpeed-through-Type-C-host remains unproven. See the design doc's correction
> box.
>
> **⚠️ NEW 2026-08-13 (P1? — under investigation by the resilience audit): zvision
> came back from a reboot BLIND.** Its only camera is the thermal
> (`--camera thermal:…2.3…`), and post-reboot the service is `active` but logs
> nothing except a repeating `select() timeout` on that device (`cap_v4l.cpp:1119`)
> — no frames, **no ZTHREAT egress observed**. The thermal offers
> `Y16`/`UYVY`/`GREY`; this is the classic PureThermal+OpenCV format-negotiation
> stall. Determine whether the reboot caused it or it was already broken, then pin
> the capture format/resolution explicitly instead of trusting OpenCV's default. A
> service that comes back "active but blind" with no self-report is exactly the
> auto-recovery failure class this fleet can't afford.

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

- [x] **DONE 2026-08-17 — thermal is now serial-pinned to a stable
      `/dev/zodiac-thermal`.** udev rule `jetson/systemd/70-zodiac-thermal.rules`
      (keyed on the PureThermal's unique serial `000b0026-…`, `:capture:` node
      only), `ZVISION_ARGS` points at `/dev/zodiac-thermal`, and `install.sh`
      deploys the rule on reflash. Works on ANY USB port — hardware-verified on the
      box (process holds `/dev/video5` via the symlink, ZTHREAT flowing). **DMX is
      already identity-bound** (olad patches FTDI `BG03OCDS` to universe 0
      regardless of port) and the **Stream Deck** is already HID vid:pid + its own
      udev rule — so both were already port-independent. The ring stays on ports.
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

- [x] **BM 2026 art/camp placements — RELEASED and pulled in 2026-08-12.** 331
      art (314 placed; 17 are placeholder/test coords, correctly gated at 5 km),
      1190 camps (1075 placed). GIS re-pulled (only cpns/toilets/trash_fence
      changed; base map identical). **Exposed a latent bug:** `campPoint` assumed
      `frontage`=clock but the feed varies the order — only 375/1190 camps placed
      until fixed to be order-robust (now 1075, verified live). And added a
      **bundled discovery seed** (`assets/brc/2026/discovery_seed.json`) so a
      never-fetched offline device isn't blank. Fleet reflashed. See SYNC
      2026-08-12.
      - [ ] *(follow-up, low)* 17 art carry a placeholder GPS with no
            clock/distance fallback, so they can't be placed. If BM later fills
            their real coords a re-pull picks them up; nothing to do until then.
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
