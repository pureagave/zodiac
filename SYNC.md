# SYNC.md

Append-only log of significant decisions, lessons, and changes for the Zodiac Control project.

Newest entries on top. Each entry: ISO date, short title, body. Don't rewrite history — if something later turns out wrong, add a new entry that supersedes it.

---

## 2026-08-12 — Beacon radio/power config, verified across a reboot (closes the B3 reboot test)

Settled and **hardware-verified** the XCover beacon's radio config, and set a
fleet-wide radio policy.

**Beacon (XCover) config — VERIFIED:** airplane mode **ON**, WiFi **ON**, Location
**ON** (high accuracy), Bluetooth **OFF**, NFC **OFF**, factory WiFi MAC
`f8:f1:e6:01:8b:6b`.

- **Why airplane mode:** the phone has no SIM. Cellular with no SIM does nothing
  but keep the modem powered, scanning for towers/PLMNs it can never register on
  — and at BRC (flooded / no coverage) it scans *harder* ("searching" drains more
  than "connected to a strong tower"). Pure waste + heat, zero function. Airplane
  mode kills cellular/BT/NFC; WiFi is re-enabled on top (Android keeps WiFi on in
  airplane mode).
- **GPS is unaffected by airplane mode** — GNSS is receive-only, and A-GPS
  wouldn't work over cellular without a SIM anyway (WiFi/Starlink can still
  assist). So no GPS penalty from airplane mode.
- **Reboot test PASSED** (acceptance for this config *and* B3): `adb reboot` →
  came back airplane ON, WiFi enabled, Location on, factory MAC held, and the
  **beacon auto-started (fresh pid) and logged `transmit socket open` +
  `targets -> 239.7.7.10, 192.168.86.255`** within the boot window, **zero phone
  interaction**. Beacon side proven on hardware; end-to-end tablet *receipt* not
  re-checked this session (the Mac can't listen — needs the Jetson listener).
- Still required: **permanent vehicle power.** Airplane mode extends the
  flat-battery margin; it does not replace power (the beacon died from a flat
  battery earlier 2026-08-12).

**Fleet radio policy — the rule:** WiFi is the only radio the fleet uses. Keep GPS
on only where there is a real GPS chip — the app's `FailoverLocationSource` uses
the device's own GPS as a backup if the beacon (NET) dies, so ON for the Samsungs,
moot for the Fires (no GNSS; they report only `location.network`). Airplane mode
only where there is a cellular modem to silence (A54, XCover). Everything else
(BT, NFC) off.

| Device | WiFi | Airplane | BT | NFC | Location |
|---|---|---|---|---|---|
| S9+ (hero) | on | — (no modem) | off | off | **on** (failover) |
| A54 (driver) | on | **on** (no SIM) | off | off | **on** (failover) |
| Fire 9th / 11th | on | — | off | — | off (no GPS) |
| XCover (beacon) | on | **on** | off | off | **on** (needs GPS) |

Applied via adb where possible (S9+ Bluetooth off; Fire 11th location off; S9+
location kept on). **adb cannot toggle NFC (Samsung blocks it) or Fire-OS
Bluetooth (`No shell command implementation`)** — those are Settings-UI only. The
A54 was on battery / unreachable at config time; its radio config is pending.

**WiFi MAC randomization audit (same session):** none of the fleet *rotates* its
MAC — two already on factory MAC (A54 `80:07:94:37:98:05`, Fire 9th
`0c:ee:99:12:11:a4`), the rest on *persistent* (stable) randomization. adb cannot
force the factory MAC — Samsung blocks `cmd wifi` (`Uid 2000 does not have access
to wifi commands`) and Fire OS silently ignores `-r`. It is a per-network
Settings toggle ("Phone MAC" / "Use device MAC"), and it does not carry to a new
network (the car WiFi will need it set there too). Plan: pin IPs via DHCP
reservation on the car router using each device's factory MAC.

---

## 2026-08-12 — 2026 placements released; the camp placer was dropping 56% of camps

BM's Innovate team published the 2026 art/camp placements (null on 2026-08-10,
live now). Pulled everything current and, in the process, the real data exposed a
latent bug that had been invisible precisely *because* placements were null.

### What the data actually was

- **Art:** 331 records, every one carrying `location.gps_latitude/longitude` +
  a top-level `location_string`. 314 placed; **17 project to a placeholder
  ~11,000 km coordinate with no clock/distance fallback** (spec/test entries +
  a few not-yet-placed pieces) and are correctly dropped by the ≤5 km
  plausibility gate. `parseArt` reads exactly those fields, so **art placement
  worked as-is**.
- **Camps:** 1190 records, all with a clock/street address.
- **GIS:** base map (streets, blocks, plazas, outlines, gate road, dmz)
  **byte-identical** to what shipped; only `cpns` (+1 node, a Deep-Playa Music
  Zone; one non-nav "Point 3" nudged ~5 m — the Man/Temple/plazas unchanged),
  `toilets` (+2), and `trash_fence` (refined polygon) changed. No georeference or
  nav-target impact. Art thumbnails: 2 withdrawn pieces removed, `index.json`
  refreshed (no new pieces).

### The bug: `campPoint` assumed a fixed field order

`campPoint(frontage, intersection)` hard-assumed `frontage` = clock and
`intersection` = street letter. But the feed **varies the order**: "G & 9:15"
(letter first) vs "4:30 & D" (clock first). Half the camps have the letter in
`frontage`, so `parseClock("G")` returned null and they were dropped. Measured:
**only 375 of 1190 camps placed; 672 (56%) silently missing.** Never caught
because the whole path had never run against real data until today — the exact
"never exercised" shape this project keeps getting bitten by.

Fixed to detect which token is the clock (parses as H:MM) vs the ring letter
(resolves in `StreetRingRadiiM`, with an `ESP`→`ESPLANADE` alias) regardless of
order — the corner is identical either way, so it only recovers drops, never
moves a camp. **Verified live on-device against the API: 1075 camps placed** (up
from 375). Tests cover both orders + the false-placement guards.

### Offline guarantee: bundled discovery seed (Rob's requirement)

`DiscoveryRepository` had **no bundled fallback** — a device that had never
reached the API and boots offline showed the full base map but a **blank
art/camp overlay**. Now there is a bundled seed
(`assets/brc/2026/discovery_seed.json`, 427 KB) served on cold start when the
disk cache is empty; the disk cache still wins when present, and a live fetch
still overwrites both. So the layering is: **baked seed = floor, cache + live
refresh = freshness.** Matches the project's own baked-asset philosophy (an APK
asset can't fail to load or arrive half-downloaded).

The seed was generated the safe way — **warm a real device on the fixed build,
pull its actual `filesDir/discovery_2026.json` via `run-as`, bundle that exact
file** — so its format is byte-for-byte what `loadCache` expects, no
reimplementation to drift. (Production emits the artifact.)

app 839→**847** (4 campPoint + 4 seed tests), beacon 88, gate green. Fleet
reflashed; cold-offline seed path verified on a wiped device.

---

## 2026-08-11 — Jetson camera-ring USB & bandwidth, measured; plan documented

Freed the Jetson's USB-C from the flash host `grr` (LAN path verified, zvision
active, WiFi down so wired Ethernet is now the sole access path — acceptable). The
question that followed — can the ring's cameras go on the USB-C, and how does
bandwidth split — is now a design doc: `design/jetson-camera-ring-usb.md`.

Measured on the live box (`lsusb -t`, `/sys/kernel/debug/usb/devices`,
`v4l2-ctl`), so this replaces guesswork:

- **Two independent host budgets.** The four Type-A ports are ONE onboard hub on
  `3610000.usb` — they **share a single 480 Mbps HS lane** (+ one 10 Gbps SS).
  Splitting cameras across those four ports adds *zero* bandwidth. The **USB-C is
  a separate controller** `3550000.usb` (dual-role, `usb2-0-role-switch`,
  currently device mode) = a **second independent 480 + SS budget**. That is the
  only way to add USB-2 bandwidth.
- **The Type-A hub is FULL:** optical (Arducam) + FTDI/DMX + PureThermal +
  **Stream Deck**. The deck took the port the old notes called "1 free," so the
  USB-C is the real expansion port now.
- **Optical = USB 2.0**, and the Arducam offers **MJPG/H264/YUYV** (onboard
  compression). **IR/thermal = 12 Mbps full-speed**, ~3 Mbps — negligible.
  Pipeline runs `--hz 8`.
- **The rule is compressed-vs-raw, not port count.** MJPEG/H.264 @ 8 Hz puts the
  whole 4-optical+IR ring at ~50 Mbps — fits one 480 lane 8× over, so bandwidth
  stops being the constraint and **physical ports + decode CPU** take over. Raw
  YUYV 720p is ~118 Mbps/cam → 4× ≈ the 480 ceiling, which is the only case that
  forces a split across controllers.
- **Recommendation:** ring behind ONE **powered** hub on the USB-C, MJPEG @ 8 Hz,
  pinned by `by-path`. The hub gets its own `3550000.usb-…` sub-chain, so it does
  **not** renumber the existing Type-A `by-path`s — sidesteps the identity-churn
  trap in the USB-identity section.
- **Gotcha:** oversubscribed isochronous UVC **fails to START** (`-ENOSPC`), it
  does not degrade — test the whole ring streaming at once.

**Scheduled 2026-08-12:** switch the USB-C to host, plug a powered hub + camera,
confirm SuperSpeed + a stable `by-path`, and check the role survives a reboot
(persist in the device tree if not). Datasheet-vs-measured is called out
explicitly in the doc — the USB-C's SuperSpeed *host* capability on this unit is
the one thing still to be confirmed on hardware.

---

## 2026-08-11 — FLEET-2: every build now says what code it is

Yesterday's lesson was that **nothing on a device could report its own build** —
`versionCode=1` / `versionName=0.1.0` were pinned identical on every build, so
install *time* was the only staleness signal and it lied whenever a clock was
wrong (the beacon came back from a flat battery set to `Asia/Dubai`). That is the
prerequisite for the fleet version monitor Rob asked for, and it is now closed.

`versionName` is now `0.1.0+<sha9>[.dirty]` — e.g. `0.1.0+8897cab95`. It is in the
APK manifest, so `adb shell dumpsys package <pkg>` reads it **without launching
the app**, and the app's existing boot log (`ZodiacApplication.kt:84`) prints it
for free. Alongside it, four structured `BuildConfig` fields so FLEET-1 can
announce `(node, role, version, sha, epoch)` without string-parsing:
`VERSION_BASE`, `GIT_SHA`, `GIT_DIRTY`, `GIT_COMMIT_EPOCH_SECONDS`.

Decisions worth keeping:

- **The sha is fixed at 9 chars** (`git rev-parse --short=9`). Git's default
  abbreviation is adaptive — a shallow CI clone and a full local clone would
  otherwise print *different* strings for the *same* commit, which a comparison
  monitor would read as a false divergence. Fixing the width makes them agree.
- **Commit epoch, not build wall-clock.** A per-build timestamp would change
  `BuildConfig` on every `assembleDebug` and make **every `testDebugUnitTest` run
  non-cacheable** — unacceptable for a project that runs the full gate constantly
  — and it adds no signal the monitor can act on beyond commit epoch + the dirty
  flag (two devices on the same commit with the same cleanliness are running
  identical code). Because identity changes only on a commit or a clean↔dirty
  transition, the build cache still works.
- **Everything fails toward untrusted.** Git absent at build time →
  `GIT_SHA=unknown`, `GIT_DIRTY=true`. The monitor must never mistake a build it
  cannot place for a current one — `BuildIdentity.known` encodes exactly that, and
  a test pins the `0.1.0+unknown.dirty` string round-trips back to `known=false`.
- **`versionCode` stays 1** so `adb install -r` never trips a downgrade across the
  fleet; the sha is the identity, not the code.

The `versionName` format is a **contract**, pinned the way ZTHREAT is: git values
computed once in the root `build.gradle.kts` (`providers.exec`) and read by both
modules via `rootProject.extra`, and a pure `core/telemetry/BuildIdentity`
render/parse type is the single definition of the string. A unit test asserts
`BuildConfig.VERSION_NAME == BuildIdentity.render(the structured fields)`, so the
Gradle side and the Kotlin side cannot silently drift. `:beacon` had `buildConfig`
turned **off** — now on, with its own test that the four fields actually
materialised (it would have gone red on the old `versionName = "0.1.0"`).

Process note: spec written first (`design/FLEET-2-build-identity-spec.md`),
implemented by a Sonnet subagent against it, then verified file-by-file against
the spec and the generated `BuildConfig`/merged manifest by hand. app 819→**839**,
beacon 82→**88**, jetson 432 untouched. Gate green, unscoped.

FLEET-1 (the S9+ hero-display monitor) is now unblocked. The Jetson still needs
its own version-report path before it can join the announcement.

---

## 2026-08-11 — The fleet all runs the same code, and the beacon became an appliance

Six devices, six different builds, none of it visible without a USB cable. All
current now — the first time that has ever been true. App **819** tests, beacon
**82**, jetson **432**. Green, pushed.

### The Lepton's 160° is the diagonal — measured, not argued

`HARDWARE.md` had settled this on physics and the tablet implemented it, but
`zvision`'s `--fov-ref` still defaulted to `h`, so any camera spec omitting
`fovref=d` computed ±80°. **The deployed rig always passed `fovref=d` explicitly,
so the vehicle never carried the error** — the exposure was latent, for the next
camera added without the flag. Default is now `d`.

Rob settled it with a cold bottle and a tape measure. At 85 cm off centre at
1.0 m — a true **40.4°** — the running rig reported **42.5°**, implying a ~61°
horizontal half-FOV against the 64° assumed. The horizontal reading predicts
**32.3°**. ±80° is ruled out by observation.

**The first protocol was wrong and that is the lesson.** "Slide it until it
disappears" measures the *detector*, not the lens: a fisheye squeezes and dims an
object toward the edge until it drops under the minimum-area threshold, so it
vanished at ~44° against a 64° frame edge. Comparing *reported vs true* angle at
**any** known position calibrates the whole scale and never needs the edge at
all. One position was enough.

DOC-5 fell out of this and turned out to be a **category error, not arithmetic**:
a circular window is clipped by the *corner* rays, so it is governed by the 80°
diagonal while bearings are governed by the 64° horizontal. Both numbers were
always right, for different jobs. `radius ≥ standoff × tan(80°)` stands.
**80° for anything circular in the optical path, 64° for anything about bearing.**

### The beacon is a power-on appliance

Once mounted, nobody can reach that phone, so having power must be enough. Three
things stood in the way and all three were real:

1. **The installed build was 9 days old and had no `BootReceiver` at all** — the
   feature we were trying to test did not exist on the device. Every "the flag
   was never written" observation was explained by that one fact.
2. **`PREF_AUTO_START` defaulted to false.** Now true. The STOP button is still a
   real stop and still survives a reboot, because `onToggle` writes the flag on
   both branches; only the never-configured state changed.
3. **Broadcast targets were resolved once**, from the DHCP lease. On the vehicle
   the phone boots faster than the router, so there is often no lease and the
   fallback silently became the limited `255.255.255.255` — which consumer APs do
   not reliably deliver, which is the entire reason the subnet fallback exists.
   Re-resolved every 5 s now. **The first resolution is deliberately not gated by
   that interval**: `elapsedRealtime()` counts from boot, so a service starting
   seconds after boot would have failed the interval test against a zero baseline
   and come up with no targets at all.

Then a fourth, found by cold-booting the real phone: **a wake lock acquired before
`start()` makes a failed start look perfectly healthy** — service alive,
notification showing, nothing on the wire, no log. Socket creation moved into the
retry loop and `start()` is wrapped and logged at ERROR.

**And the reason all of that was so hard: the beacon had two `Log.` calls in the
entire module** — on the one component that must run unattended for a week with
nobody able to reach it. It now says what it is doing, which is what let us
settle the last test in a single reboot instead of five.

### Three false conclusions, all from deaf instruments

Worth recording together, because the shape repeats:

1. **The macOS Application Firewall silently drops inbound UDP to python.** The
   Mac reported "no traffic seen" on a busy bus — multicast, subnet broadcast
   *and* unicast aimed straight at it. Listeners must run on the Jetson.
2. **Samsung delivers `BOOT_COMPLETED` about two minutes after
   `sys.boot_completed=1`.** Three separate "the beacon failed to auto-start"
   conclusions were just polling windows that ended before Android got round to
   telling the app. Allow ≥3 minutes.
3. **`/etc/default/zvision` is not shell-sourceable** (systemd `KEY=value with
   spaces`). Sourcing it yielded an empty `$ZVISION_ARGS` and ran the *fake*
   camera while printing a confident `±80°` that I nearly reported as the
   deployed config.

The rule that covers all three: **before trusting a negative result, send the
instrument a control packet and prove it can hear.** That is what finally
separated "the beacon is dead" from "my listener is deaf".

### Fleet brought current

S9+ **40** commits behind (with A2's fabricated contacts live on the hero
display), A54 **38**, Fire 11th-gen **32**, Fire 9th-gen **25**, beacon **9 days**,
Jetson **63**. All updated and verified; Jetson services restarted and re-checked
(123 threat frames in 8 s, all parsed by the new codec) with `--dmx none`
untouched, so the head was never energised. `zodiac-deck` left **inactive** on
purpose — `zdeck` now exists on the box, but deck-vs-tracker arbitration is
unresolved and that is Rob's call.

**Nothing on a device can say what build it runs.** `versionCode`/`versionName`
are pinned, so install *time* is the only signal — and it is worthless on a
device whose clock is wrong, which the beacon's was by ten hours. Stamping the
git short hash into `versionName` is the prerequisite for the fleet version
monitor Rob asked for.

### Log lines carry their UTC offset

Rob noticed the logs had no timezone. The KDoc showed it was deliberate — local
time so there is no conversion to get wrong at 3am — and that reasoning is sound
right up until a device is wrong about its own zone. The beacon came back from a
flat battery set to `Asia/Dubai` and wrote ten hours of plausible timestamps that
correlated with nothing. Lines are now `13:14:06.123-06:00`: still readable next
to a laptop clock, but comparable across the fleet, and a misconfigured device
announces itself. The offset is glued to the time with **no space**, because
`logLineSeverity()` splits on spaces and reads field 2 — the mutation that adds
the space turns 9 tests red.

---

## 2026-08-10 — Documentation audited against the code; three dangerous errors found

Full rewrite of `README.md` and `ARCHITECTURE.md` as a genuine three-part system
description, plus new `docs/{PROTOCOLS,BUILD,DEVICES,README}.md`, and a correction
pass over `CLAUDE.md`, all seven `jetson/*.md` and all four `design/*.md`. Every
claim verified against code; gates re-run green (app 817 / beacon 77 / jetson 429).

**Three errors could have cost real time or hardware:**

1. `MOVING-HEAD.md` §7 still listed `tilt_range_deg | 270` in its config table —
   the manual's figure, in the lookup table an engineer reads first, three
   sections above the measurement that refuted it and a test that pins 180.
   Exactly the failure this project's own rule was written about, living inside
   the document that tells the story.
2. `DEPLOY.md` gave `ola_set_dmx -u 0 -d 128,0,128,0,255` as the "dimmer test",
   twice. In 11-channel mode slot 5 is the **colour wheel** (255 = auto-spin) and
   the dimmer is **ch8**, so it pans a head that stays dark — the exact symptom
   MOVING-HEAD §7 Trap 1 tells you to watch for. `jetson/scripts/install-ola.sh`
   printed the same broken command; fixed here too, with the reason in a comment.
3. `MOVING-HEAD.md` §8.2 said force a motor reset with "ch9 held 250–255". That is
   the **9-channel** map; the head runs 11-channel, where ch9 is the pan/tilt
   speed axis and §8.6d measured 255 as the *slowest* setting. Following it would
   make an unresponsive head worse while looking like the fix.

**One real cross-side bug surfaced — documented, not fixed.** `HARDWARE.md`
declares `fovref=d` settled and "DONE"; the tablet carries ±64° in
`SurroundRing.COVERED_ARCS`, but `zvision`'s `--fov-ref` still defaults to `h`
(`rig.py:90`) and no example rig sets it. The runner says so itself:
`fov=160°h ... covers -80°..+80°`. **The two sides of the vehicle disagree by up
to 16° at the frame edge — about 2.8 m of miss on a person at 10 m, which is
where the tracker light gets pointed.** Recorded as DOC-1; needs a code decision,
not a doc edit.

**The `:beacon` CI gap was found during this pass** — every `android-ci.yml` step
was `:app`-scoped, so the beacon's 77 tests, ktlint, detekt, lint and assemble had
never run on a push, including the lint gate made real earlier the same day. Fixed
by unscoping all five; verified by running them as CI does and confirming the
`:beacon:` tasks execute. First green beacon-inclusive CI run: `4048250`.

Other corrections: `minSdk` documented as 30, is **28** (and `beacon/build.gradle.kts`
carried a stale comment claiming the beacon's floor was *lower* than the app's when
29 > 28 — both fixed); "landscape-only", is `fullUser`; `HUD_FORWARD_ARC_DEG`
documented but does not exist (`PERSPECTIVE_ARC_DEG = 30f`); ZTHREAT called a
"byte-exact mirror" of a "frozen protocol" when it had drifted ten measured ways;
DEPLOY's "Lepton is 120×120" (refuted 100 lines later in the same file), "Software
is 100% ready" (aim uncalibrated, DMX arbitration unresolved) and "disabled-to-fake
by default" (`install.sh` **enables and starts** it broadcasting a phantom
collision); TRAINING's 120×120 in three load-bearing arguments; DETECTOR asking for
`--record`, which shipped; BURN_IN listing the stress ledger as not built, which
ships; PERFORMANCE written entirely in the old concept letters against a deleted
`CRTVectorScreen`; and a **fabricated console banner** in `jetson/README.md` that
could not be the output of the command printed directly above it.

**Lesson worth keeping: the fabricated banner and the stale table row are the same
defect.** Both are places where someone wrote down what they believed instead of
what the code does, and both survived because nothing ever re-derived them. The
golden corpus landed the same day is the pattern that fixes this class — measure
it, check it in, make CI read it — and it is currently the only artifact in the
repo that cannot go stale silently.

---

## 2026-08-10 — The threat protocol had drifted, and five other things fixed in parallel

Six parallel agents in git worktrees plus the ZTHREAT golden corpus, merged one
at a time with the full gate between each. App **817** tests (was 773), beacon
**77**, jetson **429** (was 423). Green, pushed.

### The headline: "byte-exact mirror" was not true, and measurement is what showed it

`CLAUDE.md` described `ThreatProtocol.kt` and `threat_protocol.py` as a byte-exact
mirror. Differential-fuzzing 90 identical frames through both found **10 real
divergences**. None was visible by reading either file; both were correct on their
own terms. The cause in every case was the same: **a wire format had inherited its
host language's numeric parser.**

- Kotlin's `toFloatOrNull` accepts Java *source* syntax — `5.0f`, `5.0d`, hex
  floats. **`0x1p3` in an azimuth field became a live contact bearing 8° on the
  driver's HUD**, while the Jetson rejected the very same frame. That is the one
  that mattered: the Kotlin comment directly above it calls this "the untrusted
  network boundary".
- Python's `int()` accepts underscores, surrounding whitespace and unbounded
  magnitudes — a **4000-digit track id** parsed happily there and was dropped on
  the tablet.
- A `size` of `3.5e38` is finite in float64 and infinite in float32, so the
  producer kept the contact and the consumer discarded it.

Both sides now check an explicit grammar (`-?[0-9]{1,9}` for ids,
`-?[0-9]{1,9}(\.[0-9]{1,6})?` for numbers — `[0-9]` and not `\d`, because
Python's `\d` also matches Unicode digits and Java's does not), and the Python
side rounds parsed values through 32-bit float so both agree on the range and
clamp boundaries. **0 divergences over 111 cases, compared at exact equality.**

Format direction converged too: half-even rounding to match the producer, and
negative zero restored from the sign bit — `BigDecimal` has no `-0.0` and spells
it `0.0`, and a contact a hair to the left of the nose is exactly where that
shows up.

### What the corpus is, and the one thing that makes it work

`protocol/threat-protocol-golden.json` — 111 parse vectors, 27 format vectors,
read by **both** suites. It was **measured, not authored**: a vector ships only
if both implementations were observed to produce it, enforced by an assert in the
generator. Hand-editing it would let someone write down what they believe instead
of what the code does, which is the exact failure it exists to prevent.

Both suites **fail loudly when the corpus is missing** rather than skipping, and
both assert a minimum vector count — otherwise a truncated corpus makes every
assertion vacuously green, which is this project's most-repeated bug (six times
now). Verified by hiding the file and watching both go red.

`jetson-ci.yml` was path-filtered to `jetson/**`, so **a corpus-only change would
have skipped the Python half entirely** — the shared artifact would have been
enforced on one side. Now triggers on `protocol/**`.

3 cases remain where float64 and float32 spell a value differently (`1.45` → `1.4`
vs `1.5`). They are recorded in the corpus as `known_precision_limits`, **not
asserted as agreement**, with a test proving both spellings still parse to the
same contact. This is inherent to a 64-bit producer and a 32-bit consumer, not a
defect, and the note is guarded so it cannot rot into a lie.

### Two halves nothing on the vehicle runs

Worth recording, because it explains the whole failure: the production path is
Python `format_frame` → Kotlin `parse`. Kotlin's `format` and Python's
`parse_frame` exist **only as mirrors and are exercised only by tests**. The two
halves that drifted are precisely the two the vehicle never runs. A cross-language
contract rots in whichever direction nobody executes.

### Merged alongside

- **`CockpitViewModel` split** into `MapCameraController` / `NavigationController`
  / `GpsController`; 721→457 lines, 24→21 functions, public API and every test
  unchanged. `TooManyFunctions` reverted 25→**22** (probed to the tightest passing
  value). "Split the file, don't bump the number" holds for a fourth time.
- **Two tests that could only agree with the code they test.**
  `PlayaMapBinaryCacheTest` wrote `_v1` while production writes `_v2`, so every
  magic/header/truncation assertion was hitting the missing-file path — the whole
  corruption half of the suite was dead. Fix pattern worth adopting: **have
  production emit the artifact, then mutate those bytes, and pair every negative
  assertion with a positive control.** `NavTargetTest` re-anchored to the bundled
  GIS and BRC city geometry instead of retyping the Temple literal. No production
  bug behind either.
- **C5 closed in BLE and USB.** `start()` was re-entrant: the old `job?.cancel()`
  dropped a working fix back to `Searching` on every redundant start *and* leaked
  the link, because cancelling a coroutine does not unblock a blocking socket read.
  `stop()` now cancels, closes the link to break the blocked read, *then* joins.
- **NET `Error` is no longer terminal.** Capped exponential re-bind (1 s→30 s)
  plus a 20 s silence-triggered socket rebuild. The silence path is the important
  one: a WiFi re-association drops IGMP membership while the socket stays valid,
  so `receive()` times out politely forever on a group the tablet has left —
  **no exception any retry-on-throw could ever have caught.**
- **Rolling log tells the truth about loss.** Rotation discards were uncounted, so
  `tail()` read as the beginning of the story rather than where it was cut — the
  loss channel that fires constantly on every healthy tablet. A failed `renameTo`
  left the writer unbounded. Counted from disk, not remembered, because files
  outlive the process.
- **`:beacon` lint gate is real** (`abortOnError = true`). Measured 0 errors / 12
  warnings before (identical under `checkAllWarnings`, so nothing was hiding), 0/4
  after. Two dead `SDK_INT >= O` guards removed from the service-start and
  notification-channel paths.

### Lessons banked

- **A "predicted" mutation was wrong twice more today**, both caught only by
  running it: a `cancel`-vs-`cancelAndJoin` test **cannot** be written in virtual
  time on a single-threaded scheduler, because any other `join` in the method
  drains the queued listener and the mutation passes; and a socket-silence check
  placed inside the `SocketTimeoutException` branch can never distinguish silence
  from socket age. Run the mutation. Every time.
- **`${PIPESTATUS[0]}` is bash; this shell is zsh** (`$pipestatus`, 1-indexed). A
  gradle run that failed reported an empty status and I compared fresh Python
  output against stale Kotlin output for one cycle. Checking the *artifact's*
  mtime is what caught it — a new variant of "printing an exit code is not gating
  on it".
- **`cd` persists between shell calls.** A `cd jetson` from four commands earlier
  made `./gradlew` vanish.
- Asserting a replacement landed is now habit and paid off three times today: it
  caught a stale `[Locale.ROOT]` KDoc reference after the formatter changed, raw
  `\x0B`/`\x0C` control bytes I had embedded in Kotlin source where escapes
  belonged, and a SYNC.md anchor I had quoted from CLAUDE.md rather than the file.

---

## 2026-08-09 — Stream Deck as a physical control surface, and what review caught

Six physical keys in the cab driving the tracker light. `jetson/zdeck/` +
`jetson/DECK.md`. Bring-up verified on the real device (Stream Deck Mini
`0fd9:0063`, fw 3.03.002, 6 keys 2x3, 80x80 BMP) — Rob drove the head with it.

**No hub needed**: the Jetson's four USB-A connectors are one onboard 4-port
hub; cameras and dongle take three, the deck took the fourth. **Do not re-cable
the cameras to make room** — a hub upstream changes every `by-path` in
`ZVISION_ARGS` and the cameras stop resolving. Being HID, the deck is found by
vid:pid and is immune to that trap.

Structure mirrors zvision: pure model, surface behind a Protocol with a fake,
hardware isolated — so CI needs neither the vendor library nor a deck. Fixture
channel numbers are **borrowed from `TrackerConfig`**, not restated, so the deck
and the tracker cannot disagree about which channel is the dimmer.

### Five bugs, none found by writing the code

A Fable design review plus one smoke test on real hardware found what an hour of
careful authoring did not:

1. **Disconnect detection was dead code.** `connected()` called the library's
   `key_count()`, which returns a class constant and does no I/O — it could
   never report a disconnect. The reconnect loop was unreachable, so a deck
   knocked off by a bump would have left the service green with dead keys. Now
   uses the transport's own `connected()`; verified True/False against the real
   device.
2. **Losing the deck left the head hot.** While the panel is gone the operator
   has no kill control, so the beam must not be left burning. Parks on
   disconnect now, not just on exit.
3. **BLACKOUT was untrustworthy.** It went through `OlaDmxSink`, which swallows
   its own send failures by design — right for a tracker frame, wrong for a
   kill. A failed blackout rendered a calm grey panel over a live beam: a lit
   head under a panel saying dark, the one lie that matters. Now goes via
   `dmxpark.park()` (retries, reports) and a failure renders **DMX FAIL** in red.
4. **`StartLimitIntervalSec=0` was missing**, so five fast crashes would have
   made systemd give up permanently — no control surface for the burn, silently.
   `zvision.service` carries that line *with a comment explaining the trap*; the
   new unit forgot its own project's lesson.
5. **A `--once` run hung for four minutes.** `close()` called `reset()` and
   `close()` in one try block, so a throwing reset skipped the close, the
   vendor's non-daemon reader thread survived, and the interpreter waited at
   exit. Found only by running it on the Jetson — the unit tests were green.

### And one the smoke test surfaced by accident

A stale checkout raised `AttributeError` inside `open()`, and the runner
swallowed it as "no deck; retrying" — forever, naming the wrong problem. A
programming error was wearing an unplugged cable's clothes. Now only a specific
`DeckNotPresent` is retried quietly; anything else is loud.

### Deliberately not built

**Arbitration.** This works today *only* because zvision runs `--dmx none`. At
8 Hz the tracker would overwrite the deck within ~125 ms and BLACKOUT would
flicker rather than kill — worse than no key, because the operator would believe
the light was off. Do not enable `--dmx ola` on both at once. Fable's proposed
`/run/zodiac/light-mode` latch is written up in `tasks/open.md`, including why
olad port merging cannot express "kill wins" (HTP takes max, so a 0 loses).

Also logged, not built: heartbeat republish, systemd watchdog, the unprompted
startup slew, and that BLACKOUT and LAMP-off are currently the same operation.

### Tests

**+57 (366 → 423), every one mutation-verified.** Two rounds: the first killed
10 of 11 mutants, the survivor being an amber colour slot sneaking into the
palette — the "no amber" test only checked *label* colours, never the wheel
values. Fixed by making it an invariant: `DeckConfig` now rejects amber slots
and auto-spin values at construction. Second round killed 7 of 8; that survivor
was the forbidden-channel guard, unreachable until a test gave it a fixture map
that actually collides.

## 2026-08-08 — DMX first light: the head works, and it's in 9-channel mode

First time the moving head was powered with the dongle attached. It ended up
working with **no code or wiring change** — but the hour it took to get there
is the useful part of this entry.

### The symptom, and why it was misleading

`ch1=128` produced nothing. Everything downstream of that command looked
perfect, and stayed looking perfect through a long diagnosis: olad holding the
frame, the `ftdidmx` plugin started clean, the port patched to universe 0, the
FTDI TX thread confirmed running by CPU sampling, zero write errors across 438
journal lines, the dongle present and continuously held since boot.

**The head had simply never completed its power-up homing sweep.** These
fixtures accept DMX and silently discard it until their motors have referenced
— no movement, no lamp, no error, display sitting normally at `A.001`. It is
indistinguishable from a severed wire. Forcing a motor reset fixed it
instantly. That belongs at the *top* of any future dead-fixture checklist,
above anything software.

### Measured: it arrived in 9-channel — now switched to 11 and re-verified

`TrackerConfig` defaults to 11-channel; the hardware disagreed, proved three
independent ways (strobe on ch5, movement on ch2, dimmer mutation on ch6 — each
impossible under the 11-channel map).

The mode selector *does* exist, it was just behind an opaque label. **The
manual documents no top-level menu at all**, only the §4.2 fine-tune submenu,
so the whole menu was read off the fixture and is now mapped in
`MOVING-HEAD.md` §4.3. The abbreviations use `nd` for "Md" (Mode) — `CHnd` is
Channel Mode, `BLnd` is the signal-loss behaviour, `SHnd` is Show Mode.

Switched to 11-channel, then **re-verified rather than trusted** — a menu
reading "11" is not evidence the fixture changed personality. Two independent
confirmations: the lamp lit on ch8 while ch6 sat at zero (ch6 had been proved
the dimmer by mutation minutes earlier), and ch3 swept ~190° while **ch1 was
held constant**, so the motion had nowhere to come from but ch3. `TrackerConfig`
is now correct as written, and `NINE_CHANNEL_OVERRIDES` stays unused.

### Measured: signal loss produced an uncommanded light show — fixed in hardware

Cutting DMX made the head reset and then run its internal auto program —
sweeping, cycling gobos and colours, full brightness — until signal returned.
On the vehicle, a Jetson reboot or a knocked XLR would turn the tracker light
into a disco while the driver's HUD still assumes it is aimed at people.

Cause found in the menu: **`BLnd` shipped set to `auto`**. Set to **`blac`**.
Chosen over `hold` because **the vehicle moves** — `hold` freezes the head at a
fixture-frame position while the car turns, so a held beam rakes across
bystanders at full brightness, uncommanded, and reads as purposeful. `blac` also
agrees with what the software already does when idle (`dimmer_idle = 0`).

**No software keepalive was written, deliberately.** The hardware setting covers
the case where the Jetson itself is what died — exactly the case a keepalive
cannot — and it avoids giving a lighting concern any power to stall the threat
broadcaster, which is why `OlaDmxSink` swallows failures in the first place.

### Fixed: `NINE_CHANNEL_OVERRIDES` was unreachable *and* half-written

Found while checking what running 9-channel would actually take. The dict was
**referenced nowhere in the repo** — no CLI flag, no consumer — so there was no
supported way to drive a 9-channel head at all. Worse, it overrode the channel
map but **not `forbidden_channels`**, leaving the guard at the 11-channel
`(10, 11)`. Dropping the fine channels shifts everything above pan down by two,
so on a 9-channel head the auto programs are ch8 and **mode-select — the motor
reset — is ch9**. The guard would have protected two channels the fixture does
not read while the reset sat unguarded. Silent, and only reachable by someone
switching modes after a factory reset.

Fix: `config_for_channel_mode(mode, **overrides)` is now the only supported way
to build the config, so the map and its guard can never be applied separately;
`forbidden_channels: (8, 9)` added; `--dmx-channels {9,11}` wired through
`app.py` (default 11). 10 tests added, **mutation-verified** — reverting the
guard to `(10, 11)` fails exactly two of them, and notably *not* the pre-existing
"never drives forbidden channels" test, which passes against the bug because it
validates the tracker against whatever the config claims. jetson 322 → 332.

### Facts worth not re-deriving

- **`A.001` with a flashing dot = valid DMX arriving.** Steady = no signal.
  Free diagnostic, no tools.
- **`/dev/ttyUSB*` missing is health, not a fault** — libftdi detaches
  `ftdi_sio` when olad claims the dongle.
- **olad reads `/etc/ola`** (`--config-dir` in `/etc/init.d/olad`).
  `/var/lib/ola/conf/` holds a full set of `ola-*.conf` files that olad **never
  reads** — precisely the trap `dmx.py`'s comment warns about, still live.
- Transmission health without a scope: the *unnamed* olad thread is the FTDI TX
  thread; ~2 jiffies per 3 s at 30 Hz. Zero means dead.
- `strace` is not installed on the Jetson. An empty `grep -c` of its output
  reads as a confident zero — check the tool ran before believing the result.

### Method

Chat lag repeatedly made single-frame tests ambiguous: a report would arrive
describing the *previous* frame, and twice led to a wrong conclusion that had
to be walked back (including an early "the reset proves DMX works" that was
actually a signal-loss reset). **Alternating a channel on a fixed cycle and
asking "what repeats?" is lag-proof where "what do you see now?" is not.** The
mode finding was closed with a mutation test — change exactly one channel,
predict the outcome, verify — rather than by accumulating agreeable evidence.

---

Rob is restarting the assistant with clean context. **app 719 / beacon 35 /
jetson 322 tests, `main` clean and green, both CI workflows passing.**

Read the `zodiac-resume-point` memory first, then this entry, then
`tasks/open.md`. Entries below this one carry the detail; this is the map.

### What shipped today, in order of consequence

1. **The 2026 map migration had silently disabled the whole nav stack.** The
   GIS renamed `type`→`source`/`kind`, `width`→`width_ft`, `Name`→`name`, so
   every street parsed `kind = null`, `PlayaCityModel` dropped them all, and
   there were no street cues and no address routes — since 2026-07-30, through
   green CI, because **the renderer only needs geometry**. `BundledGisTest` now
   measures the parse, every ring radius and `2:15 & H` against the shipped
   GeoJSON. Esplanade was also wrong by 9.5 m in *both* years' data.
2. **M10 rolling logs + an on-device log viewer.** Then the logs immediately
   found a GPS source rebinding its multicast socket on every cold launch.
3. **A passenger display** (`ui/passenger/`, `core/passenger/`) — self-running
   card carousel for the people riding the car. Now the biggest feature area.
4. **A full-burn breadcrumb recorder** on the Jetson, live and enabled at boot.
5. **Pre-rendered art**: 315 pieces baked into the APK in the phosphor
   treatment, plus approach detection so the card meets you as you drive up.
6. **Kiosk mode** (device-owner lock task) — built, documented, *not yet
   provisioned on any tablet*.
7. **minSdk 30 → 28**, now hardware-verified on the Fire HD 10 9th gen.

### Decisions that should not be re-litigated

- **A1 and A5: deliberately NOT done**, with evidence in `tasks/open.md`. A1
  assumes three routers; there are two, with intentionally opposite semantics.
  A5 trades a real consistency guarantee for an unmeasurable allocation win.
- **The passenger display must never imply authority over the driver's HUD** —
  no collision flags, no braking, no alarm red, and the driver's street/passing
  overlays are deliberately not drawn on it.
- **SOULS requires a genuinely LIVE Jetson feed.** Showing the demo crowd to
  passengers as "souls detected" would be inventing people.
- **Art images are baked in, not fetched.** RuntimeShader is API 33+ and the
  Fires are API 28; the playa has no reliable internet. Changing the look means
  re-running `tools/prerender_art.py` and shipping a new APK — that is the
  right way round.
- **No face-anonymising pass on art images.** Edge detection already renders
  photographed people as faceless wireframes; the detector's confident hits are
  mostly *sculpted* faces (Zarvan, El Diabla), and blurring those would deface
  the artwork the card exists to show. Rob reviewed and agreed.
- **Art tags show the address only** — category, funding programme and the
  volunteer call were dropped as filler (Rob's call).

### Things measured today that would otherwise get re-derived

- **BM 2026 art API**: 332 pieces. `artist`, `hometown`, `description`,
  `program` at **100%**; `category` 98%; `images` 98%. **`location` and
  `location_string` are null on all 332** — the embargo is only about *where*,
  never *what*. The card and the map both light up automatically when BM
  publishes placements; nothing to do but wait.
- **Fire OS blocks `adb pull` of app external storage.** `ls`, `pull` and even
  `run-as` all return Permission denied on `/sdcard/Android/data`. The log *is*
  written; it just can't be read over adb. **On the Fires the on-device log
  viewer is the only way in.** (Corrected an earlier claim in this file.)
- **Track storage maths**: a 14-day burn at 1 Hz is ~66 MB raw / ~8 MB gzipped
  against 434 GB free on the Jetson. **No reason to geogate anything.**
- **Document-vs-photo detection is not viable** on luma/saturation — they
  overlap completely across the art feed. Only pure CAD (median luma 1.0,
  saturation 0.0) separates; 12 pieces were skipped on that narrow rule.
- **~46% of art thumbnails contain people** (65 of 140 sampled).

### Traps added to the list

- **A stateful call is never safe in a composable body.** `CardRotation.view()`
  mutates; calling it from composition (twice, to compute a hold flag) made the
  rotation double-step and skip cards. Only found because a screenshot was
  impossible. The ticker owns the state machine now.
- **Root-level composable siblings stack in declaration order.** The permission
  gate declared before `cockpitScreen` rendered *underneath the entire UI* and
  was invisible. Caught on device, not in review.
- **A test that repeats the implementation's magic number can only confirm it.**
  Two tests hardcoded DMX channel 5 and so agreed with a wiring bug.
- **Fire tablets: no data cable = nothing enumerates**, and ad-supported units
  serve adverts on the lockscreen — the first thing a passenger would see.

### Left deliberately unfinished

- **Android track-mirror client** — the Jetson serves the track read-only over
  HTTP (`:8087`) and mirrors pull. The tablet-side puller is not written. Note
  an Android app can only ever be a *partial* mirror: doze and process death
  punch holes, which is why the Jetson is authoritative.
- **Internet backup destination** — needs Rob to name where. Deliberately not
  chosen for him: it is a complete record of where the vehicle has been.
- **Kiosk provisioning** — `docs/KIOSK.md` is written and the app supports it.
  Rob is sourcing cheap screens first and will factory-reset then. It is a
  one-way door per tablet; provision only once the build has settled.

## 2026-08-08 — the moving-head manual is in the repo, and it found a bug

Rob's transcription lands as **`jetson/MOVING-HEAD.md`**, with a section on how
zvision actually drives the fixture.

**Reading it against our channel table found the dimmer on the wrong channel.**
`TrackerConfig.dimmer_channel` was **5**. On this head channel 5 is the
**colour wheel** (140-255 = "auto colour change, fast") and the dimmer is
**channel 8**. The tracker light would have spun colours at full speed with the
dimmer stuck at 0 — a fixture that looks dead on the bench with nothing in the
logs to explain it, on the night we finally hang it. Pan/tilt/fine on 1-4 and
the 540°/270° spans were all correct.

**Two tests had hardcoded channel 5, so they agreed with the bug.** They now
read `TrackerConfig().dimmer_channel`. That's the general lesson: an assertion
that repeats the implementation's magic number can only ever confirm it.

Two further traps are now asserted rather than assumed — **ch11 at 250-255 for
5 s is a motor reset**, and **ch10 above 59 hands the head to its internal
programs**, which fight explicit pan/tilt writes. We never write either, and a
test says so, because "we happen not to touch it" is not a guarantee.

**Set the fixture to 11-channel, not 9.** 8-bit pan over 540° is ~2.1° a step,
visibly steppy on a slow follow; the fine channels are the entire reason for
the longer personality. `NINE_CHANNEL_OVERRIDES` is there for a head stuck in
the short mode.

**What the manual does NOT settle, recorded so nobody re-derives it:** channel 9
is pan/tilt movement speed with no indication of which end is fast. We send 0.
If the head lags a walking contact on the bench, that is the first thing to
try, and it interacts with our software slew ceilings.

jetson **322** tests.

## 2026-08-08 — track mirroring: the Jetson is authoritative, everything else pulls

Rob asked to mirror the breadcrumb to a tablet or two, and to back it up when
there's internet. First half is built and verified.

**Direction was the design decision.** The Jetson pushing to tablets would
make every new mirror a config change on the one box that must not be fiddled
with mid-burn, and a wedged push could in principle disturb the recorder.
Instead it *serves* the track read-only over HTTP on the vehicle LAN and
mirrors pull. Adding a mirror is then a client-side decision, and a broken
mirror cannot touch the recording.

It also means a laptop or a phone can grab the whole burn with `curl`, today,
with nothing installed:

```
curl http://192.168.86.235:8087/                              # index + sizes
curl -O http://192.168.86.235:8087/zodiac-track-2026-08-30.csv
```

**It serves position history, so it is locked down by construction:** GET/HEAD
only, exactly one directory, and `safe_name()` *rejects* rather than sanitises
— a request that needed cleaning up was not a request for a track file. Eight
tests including six traversal attacks (encoded, doubled, nested); widening the
filename pattern fails three of them, so the guard is load-bearing. Verified on
the box: `/../../../../etc/passwd` returns **403**, real files return 200.

The index is newest-first with sizes so a mirror can stop as soon as it
recognises a file it already has, instead of walking the whole burn every poll.

Both services are `enabled` — they come back after a power cycle, which is the
only property that matters for something recording a two-week event.

**Still to build:** the Android mirror client (periodic pull into the tablet's
external files dir) and the internet backup. On the backup — the destination
must be Rob's explicit choice, not something I pick: this is a complete record
of where the vehicle has been, and uploading it anywhere is his call to make,
not a default to inherit.

jetson **320** tests.

## 2026-08-08 — a full-burn breadcrumb, and the art feed answers "what IS that?"

**Breadcrumb recorder is live on the Jetson and enabled at boot.**
`jetson/zvision/tracklog.py` + `zodiac-track.service`. It joins the same fleet
NMEA multicast group every tablet already listens to and appends one row per
fix to a daily CSV. It sends nothing — the beacon does not know it exists — so
recording can never affect navigation.

**Rob asked whether to geogate it. The numbers say no:** a full fourteen-day
burn at 1 Hz is ~66 MB raw, ~8 MB gzipped, against **434 GB free** on the
Jetson. There is no storage argument for gating or for dropping stationary
time, and every filter is a chance to lose the one stretch you wanted. Record
everything; you cannot recover what you did not write down.

**Durability drove the format**, because vehicle power comes and goes:
append-only CSV, flushed *and fsynced* per row, so a power cut costs at most
the row in flight. GPX or JSON would risk the whole file for want of a closing
tag — convert afterwards, from a file that survived. Daily rotation bounds a
corrupt tail to one day. Reopening appends rather than truncates, so a reboot
mid-burn cannot erase the morning.

Verified end to end: three fixes injected on the real bus produced three rows
with correct decimal degrees and a written header. 13 tests, jetson **312**.

**Why the Jetson is primary and a tablet can only ever be secondary:** it is
always-on, has a real filesystem, and systemd restarts it forever. An Android
app is *not* a reliable 24/7 recorder — doze, background limits and process
death will punch holes in a track, and holes are exactly what this must not
have. A second tablet recorder is still worth having for redundancy, but it
should be understood as a partial copy, not a peer.

**The BM art feed, answered by querying it rather than guessing.** 332 pieces
for 2026, and **100% of them carry `artist`, `hometown` and a real prose
`description`**. `location`/`location_string` are null on all 332 — so the
embargo is precisely and only about *where*, never *what*. We were fetching
`artist` and throwing the rest away; all three now flow through the client,
cache, model and onto the passenger ART card. Still on the table once
placements land: `images` (thumbnail URLs, 98% present — needs an image
loader) and `url`/`donation_link`.

app **698** tests.

## 2026-08-08 — a passenger display, and the BM 2026 art feed has started moving

Built the passenger display Rob asked for: six cards on a self-running
carousel — WHERE / AUDIO / SOULS / BUMP / TRIP / SUN / ART. No Dredd card (his
call). **app 694 tests**, main green, verified on the Fire HD 10.

**minSdk 30 -> 28.** The floor was a choice from when the 11th-gen Fire was the
oldest device, not a constraint: every API gate in the code is `>= S` (31) and
Lint reports no NewApi at 28. That covers the Fire HD 10 9th gen and Fire HD 8
10th gen. **Older 8" Fires (8th gen and earlier) are Fire OS 6/5 = API 25/22
and are still excluded** — check each with `getprop ro.build.version.sdk`
before assuming it's in.

**`$ZAUD` is plumbed at last**, as its own StateFlow rather than folded into
`BeaconSensors` — it arrives ~15 Hz, two orders of magnitude faster than the
other channels, and merging it would rewrite that whole value and wake every
consumer fifteen times a second. It nulls when the beacon goes quiet so the
visualiser flatlines honestly.

**Two things the ART card exposed, which is the argument for building the
thing rather than speculating about it:**

1. **BM has started publishing 2026 art.** 1526 entries fetched today into
   `discovery_2026.json`. But **names only — no coordinates**: every record
   lacks `eastM`/`northM`, so `point` is null and nothing can be placed. The
   embargo is lifting in stages. The art layer will populate itself when the
   placements land; nothing more to do but watch.
2. **My first ART card was wrong three ways** — it counted theme camps as art,
   counted entries that have no position, and called the entire city "nearby"
   (it read "1526 PIECES NEARBY"). `artNearby()` now filters on kind, on having
   a position, and on range, each with a test. The card correctly vanishes
   today rather than claiming proximity it cannot know.

**Passenger mode is a device role, not cockpit state.** detekt tripping
`CockpitViewModel`'s function limit was the nudge to notice: it never changes
while driving, no concept reads it, and it belongs to the tablet rather than
the session. It lives in a process-scoped `DisplayRoleStore` now, next to the
other device-level concerns. Hidden top-right long-press toggles it in both
directions — a rider must not be able to escape into the driver's HUD, and a
driver's tablet must not fall into passenger mode by accident.

**Design rule worth keeping:** the passenger screen must never imply authority
over the driver's display. No collision flags, no braking, no alarm red, and
the driver's street/passing overlays are deliberately not drawn on it. SOULS
additionally requires a genuinely LIVE Jetson feed — showing the demo crowd to
passengers as "souls detected" would be inventing people.

Card type scales off the panel rather than being fixed, since the fleet is a
mix of 8" and 10" Fires in either orientation, and long art titles step down
rather than truncate.

## 2026-08-08 — Fire HD 10 back on the bench, and it corrected a claim I had made

Rob plugged in the **Fire HD 10** (`KFTUWI`, API 30) and the **A54** over USB.
Both updated to the current build and verified.

**A claim in this log and the README was wrong.** M10 said the rolling log
"comes off the tablet with a plain `adb pull` — no root, no debug build". True
on the Samsungs. **False on the Fire**, which is the device most likely to need
a postmortem: Fire OS denies shell access to `/sdcard/Android/data`, so `ls`,
`adb pull` and even `run-as` all return "Permission denied". The log is being
written — logcat carries the boot line and the path — it simply cannot be read
over adb. Docs corrected.

**Which means today's on-device log viewer is the only way to read logs on the
Fire.** It was built for "no laptop next to the vehicle"; it turns out to also
be the only route on a third of the fleet. Verified on the Fire through the
corner long-press.

**The v2 cache bump earned itself.** Both devices logged `map: 2026 parsed from
GeoJSON (573 streets, 561 tagged, 12 plazas)` — *parsed*, not cached, because
the schema bump invalidated their stale v1 caches. The Fire had been offline
since before this morning's GIS fix, so it was carrying exactly the kind-less
parse the bump exists to evict. That bump was reasoning this morning; this is
the device that proves it was needed.

**M14 is settled on the device it was filed against.** The RECENTER chip fits
on the Fire at 1920×1200 with room to spare. Both concepts render correctly at
the perf floor, including today's new GPS status line.

**The performance section is now unblocked** — every item in it says "validate
on a real Fire HD 10 before landing", and the Fire is on the bench. Not started;
it's a profiling campaign (gfxinfo framestats, recomposition counts,
Macrobenchmark) rather than a keyboard task, and worth agreeing scope first.

Also confirmed incidentally: the A54 is on DRIVER and reading the Jetson live
(`vision: feed LIVE`, HUD showing `0 CONTACTS CLEAR` — and CLEAR can only
render when the feed is LIVE), and the burn-in ledger is recording on both
(`burn-in: DRIVER/ACTIVE`, `burn-in: MAP/ACTIVE`).

## 2026-08-08 — the keyboard-only backlog is empty, and three of its entries were fiction

Rob's call on the one open question: **Apache-2.0**. `LICENSE` added, plus a
`NOTICE` that carves out what it does *not* cover — the bundled BRC GIS is
Innovate-ToS data and the 2025 art layer is MIT from iBurn-Data. A blanket
license over third-party data would have been the easy wrong answer.

Everything else on the list is now done, **app 668 tests**, main green.

**Three backlog entries described code that no longer exists.** M14's chip was
already shortened to `RECENTER`. L8 wanted `CRTVectorScreen.kt` split — that
file is gone, and the two largest files left are comfortably under detekt's
thresholds. And A2's "five floating fields incl. panEastM/NorthM" had already
become six, with pan replaced by `cameraOverride` + `followMode`. Together with
the shock banner earlier today, that's **four stale entries in one day**:
treat `tasks/open.md` as a lead, not a fact.

**M6, done honestly rather than mechanically.** The colour literals it names
are not theme colours awaiting extraction — they're the RADAR concept's dim and
lit map palettes, and *the difference between those greens is the sweep
effect*. Porting them to `ConceptTheme` would have been a visual regression
dressed as cleanup. Only four literals were genuinely duplicating the palette's
hex; those now reference it, and zero pixels changed. The structural half is
`LocalCockpitTheme`, provided once at the root.

**M8** — rationale before the second decline (which latches to "don't ask
again" and takes the system dialog with it). **Caught on device, not in
review:** the gate was declared before `cockpitScreen`, and root-level siblings
stack in declaration order, so the panel rendered *underneath the entire UI*
and was invisible. A unit test would never have found that.

**L1** — `PinchSession` lifts the gesture state machine out of the Compose
modifier. All the behaviour that matters is about *when not to emit* — no pan
on the first frame of a drag, none on the first frame after dropping from a
pinch, no rotation on a fresh grip. Twelve tests, mutation-verified.

**Burn-in stress ledger** — on-time per `<concept>/<phase>` to the rolling log.
That granularity is the honest limit and it's deliberate: finer (per-widget
rectangles) would invent data the app never measures, and a fabricated
burn-risk number is worse than none.

**A1 and A5 are decided NOT to do, with reasons**, because both were "decide"
items and both premises failed on inspection:

- **A1** assumes three `Routed<T>` shapes. There are two. `RoutedThreatSource`
  isn't a router at all — a fixed two-source `combine` with a demo-fallback
  policy and no selection. And the two real routers have **deliberately
  opposite** switch semantics, documented in both files and pinned by a test.
  A generic would need a policy parameter to preserve that: more machinery than
  the duplication it removes.
- **A5**'s recomposition half is already solved by `MapUiInputs`. What's left
  is one shallow 35-reference copy per update at touch rate during a gesture —
  a *performance* claim, and this project validates those on the Fire HD 10,
  which is offline. Against that it would cost a real property: one StateFlow
  is why all three concepts always render the same world, on a display that
  carries collision alerts. If profiling ever justifies it, the move is *more*
  input slices, not three flows.

**Two of my own tests were wrong before the code was.** A "jitter" of 0.057°
against a 0.05° deadzone, and a ledger assertion that the code could know an
interval spanning a backwards clock jump had really happened. Both now pin the
real invariant. Worth noting because "the test failed, so fix the code" is the
reflex that would have broken both.

Also: **L10's real failure mode is fixed even though its picker isn't.** A
failed BLE match now names the paired devices ("paired: Bose QC35, GT-02
Tracker") instead of dead-ending on "no device matched" — the difference
between a rename and an evening with no GPS. The picker UI still wants a paired
BLE GPS to build against.

## 2026-08-08 — clearing the keyboard-only backlog, and M10 immediately paying for itself

Five items, all verified on the S9+ rather than reasoned about. app **637**
tests, main green.

**The GPS source was rebinding on every cold launch — found by the new logs on
their first run.** The permission launcher fired unconditionally at startup,
and an already-granted permission comes back `true`, which is
indistinguishable from a fresh grant. So `restartLocationSource()` ran every
time: harmless on FAKE, but on **NET** it drops and rebinds the multicast
socket, on every tablet, every launch. Now only *missing* permissions are
requested, and nothing is launched when there are none.

Verified **both directions**, which is the part worth keeping: revoking
`ACCESS_FINE_LOCATION` and cold-launching still raises the system dialog and
logs a single `gps: start FAKE`; granting it then produces exactly one
`gps: stop FAKE` / `gps: start FAKE`. The restart fires when something actually
changed and never otherwise. That second half only got checked because the
first half changed a path the S9+ (already fully granted) never exercised.

**M9 — GPS failures now say *why*.** The Error state has always carried a
diagnostic and the screen never drew it; a failed source was simply silent.
`LocationSourceError` splits by **what the operator would do about it** —
PERMISSION_DENIED / ADAPTER_UNAVAILABLE / NO_DEVICE_FOUND / IO_ERROR / UNKNOWN
— across all fourteen construction sites, defaulting to UNKNOWN so an
uncategorised source says "ERROR" rather than something specific and wrong.
Category on screen (it's the actionable half and the strip has room for two
words), free-text detail to the log, once per error not once per fix. Selecting
USB with no dongle on the S9+: `? NO DEVICE` in red, and
`W/…: gps: NO_DEVICE_FOUND — No USB serial device found` in the file.

**On-device log viewer** — hidden bottom-right long-press, taking the one
corner the burn-in scaffold left free. Last 400 lines, read on IO, newest
first-visible, size and dropped-line count in the header. `adb pull` answers
"what happened two hours ago" at camp *with a laptop*; this answers it standing
next to the vehicle with neither, which is the condition the log was built for.
Severity colouring reads the priority letter out of the fixed prefix, so a
message quoting " E/" can't repaint itself.

**L2 — the no-fix wire format is now pinned.** The parser already handled it;
the tests exist so a later change can't quietly break what a receiver with no
sky actually sends (`$GPGGA,,,,,,0,,,,,,,,`, a void RMC, a truncated sentence
from a dropped byte, a claimed fix quality with no coordinates). Plus a
lat/lon round trip through ddmm.mmmm, which catches an encode/decode error that
each half alone looks consistent through. Mutation-verified both ways.

**L11 — the USB filter is vendor-wide** for the serial-bridge makers rather
than vendor+product, covering FT232H / CH343 / CP2104 / MT3329 and their
siblings, plus a USB CDC class filter for the many GNSS boards that expose a
plain CDC-ACM port under their own vendor ID. The failure being guarded against
is a dongle bought at the last minute that the app silently refuses to see.
**Not verifiable without hardware** — checked only by the resource compiling.

**Two backlog entries were stale, not broken:** the shock-alert banner has been
drawn and tested all along, and M14's "RECENTER MAP" label was already
shortened to "RECENTER" (still unconfirmed on the Fire, which is offline).

**Now genuinely blocked at a keyboard:** the whole performance section, every
item of which says "validate on a real Fire HD 10 before landing."

## 2026-08-08 — M10 shipped: the tablets can now be postmortem'd

The longest-standing operational gap is closed. Two phased commits.

**`core/log/RollingFileLog`** — pure `java.io`, JVM-testable. Three properties,
each asserted: it **never throws** (a logger that can kill the cockpit is worse
than no logger), total size is **bounded** at `maxBytes × (keep + 1)` (tablets
fill up), and rotation drops the **oldest** lines — you want the frames around
the failure you just saw, not the ones from boot three days ago. Mutation-
verified: disabling `rotate()` fails three tests.

**`data/log/FileLogTree`** on Timber. Writes drain on IO through a bounded
channel, so a log call from a render or sensor coroutine costs an offer and can
never block a frame on a slow flash write. Files live under
`getExternalFilesDir("logs")` — deliberately, so a fleet tablet's log comes off
with a plain `adb pull`, no root and no debug build, which is the whole point
when the tablet has been in the dust for two days.

**Two things the first device run taught us immediately**, which is the best
argument for the feature:
- Every line read `I/-:`. A bare `Timber.Tree` gets a **null tag** unless every
  call site says `Timber.tag(...)`; only `DebugTree` infers one. Now extends
  `DebugTree` (never calling `super.log`, so nothing reaches logcat from it).
- The map line logged a street count but not the **tagged** count — the exact
  number that would have caught this morning's schema bug at a glance. Fixed,
  and it now reads on both the cache and the parse path.

Uncaught exceptions are written **synchronously** before handing back to the
platform handler; the async drain would lose precisely the entry worth having.

Tagged the events a playa postmortem actually asks about: GPS select/start/stop,
**NET→SYSTEM failover** (deliberately invisible on screen — Rob: "we just want
things to keep working" — so the log is its only record), vision feed
LIVE/DEMO/ABSENT transitions, transport connect/disconnect, and which path
served the map.

Verified on the S9+ — and it caught a live fact in passing, that the tablet
picked up the Jetson (`vision: feed DEMO` → `feed LIVE` in 250 ms):

```
I/ZodiacApplication: boot: ... api 36, samsung SM-X810
I/RoutedThreatSource$feedState: vision: feed DEMO
I/RoutedThreatSource$feedState: vision: feed LIVE
I/RoutedLocationSource: gps: start FAKE
I/AssetsPlayaMapRepository$runLoadAttempt: map: 2026 loaded from binary cache
  (573 streets, 561 tagged, 12 plazas)
```

**One thing the log surfaced, noted not chased:** the GPS source is stopped and
restarted once during startup. That's `restartLocationSource()` on the
permission-result path doing what it says. Harmless on FAKE; worth a look
before the playa, because on **NET** it drops and rebinds the multicast socket
on every cold start.

Next for logging: a debug screen over `RollingFileLog.tail(n)` (the method is
there and tested), and surfacing `droppedLines`.

app **617** tests.

## 2026-08-08 — the 2026 map migration silently disabled the whole nav stack

**The on-device address check was the right gate to be worried about, and it
found something much bigger than a mis-placed pin.**

The 2026 Innovate GIS renamed its street properties: `type` → `source`/`kind`,
`width` → `width_ft`. `GeoJsonParser` still read the 2025 keys, so **every**
`StreetLine` parsed with `kind = null`. `PlayaCityModel` drops kind-less
streets, so the city model was **empty**: no arcs, no radials,
`cityOuterRadiusM = Double.MAX_VALUE`. That silently took out street-crossing
cues (`NAV · 3:00 ← ESPLANADE`), "what street am I on", and every address route
— since 2026-07-30, through a green CI and a "renders on the S9+" check.

**The map drew perfectly the whole time**, because the renderer only needs
geometry. Same failure signature the zvision audit kept turning up: a green
service that is lying. Geometry is not semantics; drawing is not working.

Same drift in `plazas` (`Name` → `name`), so all twelve plaza names were null
and `plazaLabelSeeds` dropped every label.

**Measured against the bundled assets, not reasoned about:**
- Every lettered ring matches `StreetRingRadiiM` within **0.7 m** — the radii
  really are stable year over year, as assumed.
- **Except the Esplanade: coded 752.0 m, measured 761.5 m.** And the *2025*
  data measures 761.1 m, so that number was never right — it just had never
  been checked against the thing it claimed to come from.
- `addressTarget(2:15, "H")` now lands **3.1 m** from the true GIS intersection
  of the H ring and the 2:15 radial. `Camp.GALACTIC_RELAY` is 2.4 m off. Both
  are far inside a street width — **the home-camp address gate is closed.**

`BundledGisTest` re-measures all of it against the shipped GeoJSON every build,
so the next rename fails a test instead of quietly disabling navigation. All
five assertions were confirmed failing before the fix; the ring-radius check
was mutation-verified with a deliberate 10 m error on H.

`PlayaMapBinaryCache` bumped to **v2** — a v1 cache on a tablet holds the
kind-less parse and would have survived the upgrade untouched.

**Verified on the S9+** with the new build: the nav line reads
`NAV · 3:00 ← ESPLANADE  OUTBOUND` (dead before this), and the keypad resolves
`2:15 & H` → `HEADING 112°`, `1.6km` — identical to the hard-coded HOME preset,
which is an independent cross-check that the typed address and the camp
constant land on the same spot.

**Lesson worth keeping:** a yearly third-party dataset has no compatibility
promise, and a parser that reads a missing key gets `null`, not an error. Any
"we migrated to the new data" claim needs a test that asserts against the new
data's *content*, not just that it loads.

app **599** tests.

## 2026-08-08 — HANDOFF: fresh context starts here

Rob is restarting the assistant with clean context. Everything needed to pick up
cold is in three places, in this order: the **`zodiac-resume-point` memory**
(READ FIRST), the top of **this file** (the *why* behind each decision), and
**`tasks/open.md`** — whose top two sections are now the live picture, split into
**blocked on Rob/hardware** and **doable without him**.

**State:** `main` clean and green. app **593** tests, beacon **35**, jetson
**299**. Both CI workflows passing. Jetson at 192.168.86.235 running the current
build; A54 has the current APK installed.

**What I did NOT get to, though I could have** (now written down rather than
carried in my head):
- **Shock-alert banner** — state is plumbed in `CockpitUiState`, nothing draws
  it. Listed twice, skipped twice, no good reason.
- **2026 map on-device address check** (our camp, 2:15 & H) — the one gate
  before trusting nav. Drivable over `adb input`; just fiddly.
- **M10 rolling file logs** — still the biggest operational gap. Without logs we
  cannot postmortem a tablet that misbehaves on the playa.
- **Long-run confirmation of the FFC fix.** `~/nightwatch/fp2.log` is
  accumulating. Pre-fix: 104 contact-frames / 10 phantom collisions per 7.7 h in
  an empty room. Post-fix at 40 min: 0 / 0 — and pre-fix had already burst twice
  by that point, so it is real positive evidence but **not yet conclusive**.
  Give it hours before making the rate claim.

**Blocked on hardware/Rob, ranked:** MotionDetector single-frame track reset
(false-negative bias, needs real bodies) → ring night legibility on the A54 →
DMX fixture + calibration → rig azimuth → pod assembly (germanium window
pending) → cross-camera dedup → grr validation → Fire HD 10 (offline) → XCover
mic grant.

**One standing warning worth repeating:** do not add tablet-side contact
coasting until the Jetson's id churn is fixed — coasting by id while ids churn
draws one person as two.

## 2026-08-08 — the overnight run found a real detector bug: FFC read as a crowd

**Measured, not reasoned about.** A passive bus listener watched the live
thermal against an empty room for **7.7 hours: 421,309 frames, 104 frames with
contacts (0.025%), 120 contacts, and 10 phantom COLLISION flags.** The arrival
pattern was the tell — **nine short bursts, 83 of 92 five-minute windows
completely silent.** A noise floor is uniform; that is a discrete sensor event.

**Cause, found by instrumenting the real board:** every few minutes the Lepton
re-baselines (flat-field correction) and **100% of pixels move by >40 counts**
with the frame median stepping **128–232**. Cadence measured at ~3 minutes
(92 s, 275 s, 458 s). The background subtractor reads a whole-frame step as
movement everywhere. The per-frame median centre already absorbed the *drift* a
correction leaves behind — nothing guarded the frame where it happens.

**Fix:** `ReBaselineGuard` in `normalize.py`. The foreground mask already tells
us — a body is a few percent of the frame even up close, a re-baseline is all of
it — so the threshold never has to be delicate. Detections are dropped across
the step and for 3 frames after while MOG2 re-absorbs. Deliberately suppression,
not correction: there is no recovering what the scene did during the step, and
inventing contacts is worse than briefly having none. Cost is a ~⅓ s blind
window every few minutes.

**Why this was worth chasing rather than filing as an acceptable rate:** one
false `! BRAKE !` every 45 minutes is exactly how a driver learns to ignore the
real one.

A mutation showed the detector could ignore the guard entirely and every test
still passed — so there is now a wiring test (recording fake `cv2`, no OpenCV
needed) asserting the guard short-circuits *before* contour extraction. That
mutation is caught now.

Post-fix measurement is running (`~/nightwatch/fp2.log`). **Not yet conclusive
— at 10 minutes it is clean, but the pre-fix run's first burst was at 15
minutes, so this needs hours before it means anything.**

jetson **296 tests**. Also removed `/opt/zodiac.pre-git-20260807`.

## 2026-08-08 (overnight) — FOV settled, alerts latched, alert path proven on hardware

**The Lepton UW's 160 deg is the DIAGONAL, not the horizontal.** FLIR never
states the axis anywhere we could find. Physics settles it: the board emits
160x120, and 160 deg across the width of a 4:3 f-theta frame demands a ~200 deg
diagonal — not a lens that exists (226 deg against the square 120x120 "usable"
area FLIR quotes). Read as the diagonal it is an ordinary fisheye with a
**64 deg horizontal half-angle**. Not cosmetic: `fov_ref` feeds
`pixel_to_bearing`, so the wrong reading **mis-aimed every edge bearing by up to
16 deg** — ~2.8 m of miss on someone 10 m away, which is where the tracker light
points. Deployed `fovref=d`; the tablet's ring carries the matching +/-64.
Both ends now report the same 232 deg unwatched. **Policy set: under genuine
uncertainty, under-claim coverage.**

**Zero false positives on real hardware.** A passive bus listener watched the
live thermal against an empty room: **13,727 frames, 0 contacts, 0 collisions**
over 15 clean minutes. That is the gain-floor fix validated in production
conditions (pre-fix: 77 spurious detections in three minutes). A clean-baseline
run is accumulating overnight in `~/nightwatch/fp.log` on the Jetson.

**The alert path is proven end to end.** Injected a synthetic rear collision on
the real bus and photographed the A54: `! CHECK REAR ! 3 CONTACTS` in red, no
`! BRAKE !`, no centre banner, the count surviving the alert, the red collision
blip astern on the ring with its spoke pointing outward, and no forward figure
for contacts outside the +/-30 deg perspective arc. Every design decision
behaved as specified against real wire data.

Also confirmed incidentally: with `zvision` stopped the tablet falls back to the
demo and **says `DEMO`** rather than `CLEAR`. Before this week that phantom
collision figure was indistinguishable from a real person.

**Alerts are now latched.** The edge box decides `collision` per frame from a
noisy size estimate at ~9 fps, so the flag chatters while the hazard is
continuous — nothing latched it, so `! BRAKE !` and the centre banner were
painted as a strobe, and a driver reads a strobe as a glitch. `AlarmLatch` is
deliberately asymmetric: **instant attack, slow release**. Raising is never
delayed; clearing waits out a hold. The bench run then exposed that the *rear*
callout was still unlatched — same signal, same failure — so both are latched
now, independently.

Clearing needs a timer rather than another frame (a passed hazard produces no
more frames), which is why this is a flow operator: `transformLatest` gives
supersede-and-reschedule for free and kept the ViewModel from growing two more
methods. That mattered — it stayed under detekt's threshold **without** raising
it, on a class already flagged as a god object.

App **593 tests**, jetson **286**, all gates green, everything pushed.

**Jetson operational notes:** reachable directly at `192.168.86.235` over
Ethernet. `/opt/zodiac` is now the single git checkout (was two untracked copies
four days stale). `pkill -f listen.py` over ssh **kills your own session** —
use `"[l]isten.py"`.

## 2026-08-07 — zvision audit: five latent failures that all showed a green service

Deep audit of `jetson/` (Fable, high effort) plus independent verification here.
**235 -> 282 tests**, nine commits. Every finding below was verified by probe or
mutation, and I re-ran the top five mutations myself rather than take them on
trust. The through-line is the same as every real failure this box has had: the
bug was in arithmetic or configuration that had no test, and the *service stayed
green while lying*.

1. **A missing `/etc/default/zvision` broadcast phantom threats.** The unit used
   `EnvironmentFile=-` (optional) and the CLI defaults to `--source fake`, so a
   lost config ran the demo detector all night — three synthetic contacts, one a
   recurring phantom collision, byte-identical on the wire to real people.
   Config is now required: a loud failed unit instead of a lie on the HUD.
   Checked the live box — the file is present with the real thermal config, so
   this was latent, not active.
2. **`install.sh` has been broken since I moved the install to a clone at
   `/opt/zodiac` this morning** — it copied the tree onto itself, `cp` refused
   "identical", and `set -e` killed the install before the unit or config were
   written. My bug, from yesterday's DEPLOY.md change. Fixed and pinned.
3. **The blind-arc report credited diagonal FOVs as widths.** `160°` diagonal on
   a 4:3 sensor is truly `±64°` horizontal, not `±80°` — verified by hand
   (`80 × 80/100`) and against the code. Three cameras 120° apart quoting 130°
   diagonal really leave three 15° blind arcs; the old code printed "blind: none
   — the ring closes". A confident all-clear aimed at exactly the person it
   would miss. The *bearings* were always right, which is what made it worse.
4. **`--check` passed configs that crash-loop or silently blind.** `az=nan`
   passes every guard (NaN compares False) and puts the literal `"nan"` on the
   wire, which the tablet drops — green service, blind arc. `farh=nan` makes
   **every** contact read size 1.0, i.e. touching the vehicle and always past
   the collision gate. `--hz inf` gives a zero period: a hot loop flooding the
   vehicle network.
5. **A lapped track-id counter reissued ids that were still alive**, splicing two
   people into one track — the collision estimator then sees a teleporting
   bearing, reads it as fast crossing, and *suppresses a real alarm*.

Also: the olad wire contract had no test at all (renaming the endpoint and both
parameters passed all 235), the DMX sink counted errors without ever logging one
(an olad that dies at 23:00 = dark light, empty journal), `UvcCamera` had no
`close()` so every RGB handle leaked, and mutating the collision rule's
"strictly closing" to `>=` passed the whole suite — that mutant flags every
stationary bystander beside a parked art car.

Cross-check worth recording: the box now reports `blind: +81°..+280°` for the
one-thermal rig, and the tablet's `SurroundRing.COVERED_ARCS` (±80°) yields the
same 200°. The two ends of the system agree about what is not being watched.

**Left undone, and it is the biggest remaining worry:** `MotionDetector` resets a
track on a single-frame dropout, so a flickering blob gets a new id and a reset
collision baseline — a systematic bias toward false *negatives* while driving.
Fixing it changes detection behaviour and needs hardware validation, so it was
deliberately not done blind.

## 2026-08-07 — DMX dongle live on the Jetson; surround DRIVER HUD shipped

**DMX.** FTDI FT232R dongle (`BG03OCDS`) plugged into the Jetson; OLA installed
and the whole path proven: `olad` opens the widget, reports **"Granularity for
FTDI thread is GOOD"** (its DMX timing will hold), and `python3 -m zvision
--source fake --dmx ola` drives pan/tilt/dimmer with no errors. Fixture not
attached yet, so nothing has actually lit — everything up to the XLR is done.

Two real bugs found doing it, both fixed in `scripts/install-ola.sh`:
- **The installer wrote plugin config to the wrong directory.** `olad` on this
  image runs `--config-dir /etc/ola`; the script hard-coded
  `/var/lib/ola/conf`, which also exists and looks plausible. It failed
  *silently* — every plugin kept its default, `ftdidmx` stayed `enabled =
  false`, and the dongle simply never appeared in `ola_dev_info`. The script
  now asks the running daemon instead of guessing.
- **olad could not open the dongle.** ftdidmx uses libftdi (raw USB), not the
  `/dev/ttyUSB0` node, so `dialout` is irrelevant — it needs a udev rule
  granting the FT232's USB node to `plugdev`. Symptom was `libusb_open()
  failed`. libftdi detaches `ftdi_sio` itself once it can open the device, so
  nothing needs blacklisting. Also disabled `stageprofi`, which grabs
  `/dev/ttyUSB0` on a timer and fights for the same hardware.

Our FT232R lands on **port 1, not port 0** — `ola_patch -d 2 -p 1 -u 0`.

**Tracker light is 180-degree, not 360.** The head is mounted to cover forward
and both sides and cannot throw behind the vehicle. Since the multi-camera
merge the wire carries full-circle bearings, and `select_best`/`_pick` took the
whole list — so a collision astern won selection, pan clamped at end of travel,
and the head parked against its limit pointing at nothing while ignoring the
person in front. The latch made it durable. `reachable()` now filters *before*
"collision wins", and out-of-reach releases the latch.

**Jetson networking:** reachable directly at `192.168.86.235` over Ethernet
(no ProxyJump needed), and at `192.168.55.1` over the USB link from `grr`.
Note `~/zodiac` on the box is a copy, not a git clone — `git pull` there fails.

**Surround DRIVER HUD shipped** (design doc `design/surround-driver-hud.md`
rev 2, four commits). Rear contacts are drawn for the first time. A rear
collision no longer fires `! BRAKE !` or the centre banner — braking puts the
vehicle further into its path — it gets `! CHECK REAR !`. Braking is
speed-gated, because people deliberately walk up to art cars and every one of
them is a constant-bearing looming track while parked. The rim is solid over
the 160 deg the thermal watches and dotted over the 200 deg it does not, red
when the feed is dead, so `CLEAR` can only appear when something is genuinely
looking. App **567 tests**, jetson **235**.

**Still not verified on hardware:** nobody has looked at the ring on the A54 at
night brightness. That is the acceptance gate the design names.

## 2026-08-07 — Thermal pod shrunk to its own tiny box; last two test-coverage items landed

**Housing.** The aluminum CCTV box is oversized for a camera that is 19.5 x
15.32 mm. Splitting the thermal into its own **Hammond 1550Z101 (50 x 45 x 24
mm, IP66/67/68, 6 mm lid, ~$15-20)** and leaving the RGB in the housing it
ships with. The apparent blocker — the D20 germanium has to sit ~1.7 mm from
the lens at 160 deg, which reads like "the front wall must be paper-thin" —
dissolves once the lens is pushed *into* the port bore instead of sitting
behind the wall: the bore wall is then beside the lens, not in front of it, and
wall thickness stops mattering. Mini-B end feeds through the gland first (7.5 x
4.5 mm clears an 8 mm bore; the USB-A end never would).

Recorded the stop-radius rule explicitly in HARDWARE.md — `r >= s * tan(theta)`,
so 80 deg half-angle needs the window within `r / 5.67`. The old "~25 mm window,
~57 deg" note predates the Ultra Wide and is 3x too forgiving; superseded.

Cost: two pods means two independent mount angles. `rig.py` already carries
per-camera `mount_az`, so it is a calibration entry, not a code change — but
both pods must bolt to the same rigid bracket or cross-camera dedup has no
stable reference.

**Tests.** Closed the last two items from the coverage audit. Callouts now go
through `AnnouncementCooldown` — the street popup and the passing-art callout
both judged "new" against only the *previous* value, so two pieces at similar
range (nearest is `max = 1`) re-announced on every flip, and a street label
flickering at a block edge re-flashed each time it returned. And the map's
pinch/rotate math is out of the `pointerInput` block into `MapGestureMath`,
where the atan2 seam is actually testable — dropping the shortest-arc
correction spins the map 358 deg from 2 deg of twist, and now three tests say
so. App at **498 tests**, all gates green.

## 2026-08-07 (later) — /dev/videoN is NOT stable across reboots; three fixes

The cold-boot test earlier looked like a pass — service active, 0 restarts. It
wasn't. **The thermal and RGB swapped device nodes across that reboot:**

| | before reboot | after |
|---|---|---|
| thermal | `/dev/video0` | **`/dev/video4`** |
| RGB | `/dev/video2` | **`/dev/video0`** |

So the service came up happily running the **RGB camera through the thermal code
path**, requesting Y16 from an RGB sensor. It didn't crash, which is worse than
crashing — it looked healthy while being wrong.

`/dev/videoN` follows USB enumeration order, which isn't deterministic. On a
five-camera ring this is genuinely dangerous: every camera carries a mount
angle, so a reshuffle silently assigns the wrong bearing to every contact and
the tracker light points at the wrong person.

**Fix: name cameras by `/dev/v4l/by-path/`,** which is tied to the *physical USB
port* — so a port keeps its identity, and therefore its mount angle, forever.
**`by-id` is not safe here:** identical cameras report identical serials (all
four Arducams will say `SN0001`). Service config updated; the reason is written
into the config file itself so nobody "tidies" it back to `/dev/video0`.

Three bugs fell out of doing it:

1. **`UvcCamera` couldn't open a camera by index at all.** OpenCV picked its
   obsensor backend and failed with "Camera index out of range" on a perfectly
   good node. `ThermalCamera` had already been forced to `CAP_V4L2`; the fix
   hadn't been carried across. (The rig *correctly* skipped the dead camera and
   kept running — that robustness earned its keep.)
2. **`parse_camera_spec` couldn't parse a by-path name.** It splits on `:`, and
   by-path names contain colons (`usb-0:2.3:1.0`) — so the *correct* way to name
   a camera was unparseable. Now consumes leading fields until one looks like
   `key=value`; keys and paths never contain `=`, so it's unambiguous. +2 tests.
3. **The recorder's lossless cutoff** (fixed earlier the same day) was the same
   class of bug — an assumption about sensor geometry that quietly changed
   behaviour rather than failing.

**Cross-camera dedup verified on real hardware:** both cameras reported a
contact at the same bearing and the merge collapsed 2 → 1. First time `rig.py`'s
fusion has run against anything but fakes.

## 2026-08-07 — both cameras on the bench; two detection bugs found and fixed by measurement

The Lepton UW and an Arducam RGB are both on the Jetson, and the service now
runs the **real thermal camera**, surviving a cold reboot with **zero restarts**
— it opened the camera first try, so the `Restart=always` net was never needed.

**RGB camera:** `Arducam / 0c45:6366` → `/dev/video2`. **MJPG up to 1920x1080@30**,
YUYV only at 640x480 and below — which is the shape that makes a four-camera
ring feasible, since the uncompressed path is capped precisely because it can't
fit. **Both cameras streamed concurrently on the same USB 2.0 hub** (thermal
`1-2.3`, RGB `1-2.1`) with no starvation. Two isn't five, but it's the first
real evidence rather than reasoning.

### Bug 1 — the naive stretch made detection impossible

Per-frame percentile normalisation reads beautifully as a single image and is
fatal downstream: a hot object raises the 99th percentile, so the mapping
darkens *everything else*, and background subtraction sees a global shift rather
than a local blob. **37.8 of 255 levels** of frame-to-frame background movement
on a static scene; one weak blob in 22 seconds of waving.

**My first fix made it 3x worse** (117 levels) by smoothing the centre as well
as the scale — wrong lesson from a right observation. The centre *must* track
per-frame, because it is cancelling real sensor drift. Measured four strategies
against live static-scene frames, where every output level is by definition
noise:

| strategy | background swing |
|---|---|
| per-frame percentile endpoints | 10.9 |
| smoothed centre **and** scale | 128.7 |
| **per-frame median centre + smoothed scale** | **6.0** ✓ |
| per-frame median + per-frame scale | 5.8 |

The last two tie on a static scene and diverge exactly where it matters — a
per-frame scale lets a hot object inflate the range, the original bug. Median
because a hand barely moves it, whereas it *defines* a percentile endpoint.
Shipped: **4.5 levels**, an 8x improvement.

### Bug 2 — a flat scene was being amplified into contacts

Three minutes unattended, nothing moving: **77 spurious contacts across 6.2% of
frames**, in bursts. Looked like FFC. Wasn't. Logging the normalisation beside
the detections showed the spread falling 58 → 40 counts with the false positives
starting exactly as it bottomed out.

The stretch had **no gain limit**, so the flatter the scene the harder it
amplified — magnifying the sensor's own noise into structure the detector
correctly reported as movement. Measured: temporal noise **37.4 counts**,
typical frame delta ~3, room contrast only **51 counts** — a contrast-to-noise
ratio of **1.4**. Three counts of noise became ~7.5 output levels.

Floor derived, not picked: to keep noise under ~2 output levels the gain must
stay below 0.67 levels/count, so spread must not fall below ~190 → **200**. Far
under the ~600 counts a person makes, so real contacts are untouched and a
featureless scene renders flat, which is the honest answer.
**Re-run: 1 spurious contact in 915 frames, down from 77 in 904.**

### Also found

* **The recorder was saving thermal as JPEG.** Its lossless cutoff was 128x128,
  which a 120x120 sensor passed — but the Lepton is 160x120, so the one format
  that most needs lossless was silently getting lossy. Fixed to a pixel-count
  threshold; the gap between thermal (19k px) and the smallest RGB mode (307k)
  is enormous, so the split is safe. Verified: 33/33 PNG, ~40 KB/frame ≈ under
  900 MB for a six-hour night.
* **DMX tracker driven by real camera contacts** — aims, then blacks out and
  holds aim when the scene empties. First time that path saw real bearings.
* **Cost measured:** 4.9% CPU for one camera at 8 Hz, so five ≈ 25% with room
  for a model. SoC 51 °C at ~22 °C ambient (a ~29 °C rise — extrapolates to
  ~69 °C at playa ambient, under throttle but against a carrier rated 0–35 °C).
* **RGB was pointed at the ceiling** the whole time, which is why it detected
  nothing while the thermal did — a neat illustration of 160° vs 87°.

**Service is on the real thermal now.** For a visual demo on the tablets, set
`ZVISION_ARGS=--source fake --hz 10` in `/etc/default/zvision` and restart.

**Still open:** the horizontal-vs-diagonal FOV measurement, detector tuning
against real bodies at real distances, and the cross-camera dedup test (needs
both cameras aimed at the same moving thing).

## 2026-08-07 — thermal camera on the bench: four assumptions wrong, detection working

The Lepton Ultra Wide + PureThermal Mini is plugged into the Jetson and driving
the full zvision pipeline. Enumerates clean as `GroupGets PureThermal
(fw:v1.3.0)` → `/dev/video0`, `uvcvideo`, no drivers. Several things we'd
recorded from datasheets turned out to be wrong.

**1. It's 160×120 (4:3), not 120×120.** Every FOV argument we'd made assumed a
square sensor — including the "160° horizontal implies an impossible 226°
diagonal" reasoning, which was built on that wrong shape. For 4:3 the two
hypotheses are: 160° horizontal → ~200° diagonal, or 160° diagonal → ~128°
horizontal. **Still open**, but now it's measurable rather than arguable.

**2. The connector is not USB-C.** HARDWARE.md said "Mini USB = compact +
standard USB-C". It takes a small USB-B-family cable — GroupGets' page says
micro-B, the board in hand took a mini-B. The **PureThermal 3** is the USB-C
board. A cable ships in the box.

**3. Native 9 fps, no MJPEG** (UYVY/Y16/GREY/RGBP/BGR3 only). Sounds bad after
all the bandwidth worry, but 160×120×2×9 ≈ **2.8 Mbps** — the thermal is
irrelevant to the USB budget. The RGB ring is the only real contention.

**4. The 8-bit output is unusable — the big one.** A real indoor frame through
the 8-bit path came back with **std 0.12 counts**: flat. The Lepton's AGC is
reachable only through vendor UVC extension units; `v4l2-ctl --list-ctrls` shows
a *read-only* brightness and nothing else. Background subtraction on that
detects nothing, ever, and presents exactly as "camera works, nothing to see".
The same scene in **Y16 raw** carries hundreds of counts of structure, and a
hand reads **+100 to +360** above background.

So `capture.ThermalCamera` now reads Y16 and does its own percentile stretch.
Two traps found in the doing:
- **Force `cv2.CAP_V4L2`.** OpenCV otherwise picks GStreamer, which silently
  ignores `CAP_PROP_CONVERT_RGB` (`unhandled property`) and returns an
  already-converted 8-bit frame — std ~86 vs ~176 for true `uint16` raw.
- **Crop telemetry rows only when present.** The 122-row Y16 mode appends 2
  Lepton telemetry rows (a permanent false-motion band along the bottom), but
  asking for 120 returns 120 — my unconditional crop ate two real image rows.

**Verified working:** live hand-tracking across the frame via baseline
subtraction, and the full `--source thermal` pipeline emitting ZTHREAT frames
(settling correctly to 0 contacts with nothing moving).

**A correction to my own reading:** an early frame showed a dark circle with
bright corners and I called it the fisheye image circle. A flat-field capture
(hand over the lens) is **uniform edge to edge** — no vignette, no circle. The
lens fills the sensor; that "circle" was scene, not optics (a 160° lens indoors
sees near warm surfaces at the periphery and a cool distant centre). Good news:
the ordinary frame-referenced FOV model applies, so `--fov-ref h|d` is the right
knob after all.

**FFC is normal** — every few minutes the shutter closes, the frame goes uniform
for an instant, spread collapses and recovers. Not a fault, and the reason the
stretch is per-frame: absolute counts drift, only relative structure is real.

**Still open:** whether the 160° is horizontal or diagonal. Settled by one
measurement — a hot object at a known 45° off-axis lands at column ~125 if
horizontal, ~136 if diagonal.

## 2026-08-06 — end-to-end VERIFIED on both tablets; failover proved itself unprompted

Jetson → bus → HUD confirmed on real hardware, plus two pieces of code that had
never been seen on a device.

**DRIVER HUD from the edge box, on both OLEDs.** A54 and S9+ both updated to the
current build and rendering the Jetson's contacts. The proof took care: zvision's
`FakeDetector` is a deliberate mirror of the tablet's own `FakeThreatSource.demo`,
so both produce three identical contacts and the picture alone proves nothing.
Reconfigured the Jetson to a 3-camera surround rig (9 contacts, rear bearings,
`2001`-style namespaced ids) — the tablet's layout changed; stopped zvision — it
reverted to the local demo. That round trip is the actual proof.
- The HUD showed **3 CONTACTS** against 9 on the bus, which is correct:
  `HUD_FORWARD_ARC_DEG` filters the six rear contacts. The forward-arc guard
  behaving exactly as intended with real surround data on the wire.
- Caught the full collision state on the A54: `! COLLISION COURSE !`, red
  striding figure with lock brackets, `! BRAKE !`, green TRACK contacts, purple
  heading marker. Semantic palette holding.

**Phase 3b ops footer verified** (S9+, RADAR). No beacon exists on this network,
so it was tested by synthesising one — GGA/HDT plus `$ZENV`/`$ZODO`/`$ZBCN`/
`$ZSHK` with real XOR checksums, multicast onto the telemetry group. Rendered:
```
16:33              RISE 05:57  SET 20:07             ▸ HOME 1.6km ▲
TRIP 12.4km  ODO 431.3km                       BATT 87%  SATS 9  UP 3h07
```
Labels dim-green chrome, TRIP/ODO/BATT purple as live values, SATS/UP blue as
link state — the palette rules exactly. With no beacon it stays **one line**, so
`beaconReadout.any` is right too.

**Beacon-staleness fix verified.** Stopped the synthetic beacon; 16 s later the
line was **gone**, not frozen at `BATT 87%`. That bug would have shipped a dead
hub's battery and uptime as current readings.

**The failover proved itself without being asked.** When the beacon went silent
the ops distance jumped `HOME 1.6km` → **`583.3km`** — because
`FailoverLocationSource` handed over to the S9+'s own GNSS, which had a real fix
at `40.519458,-112.270875` (**30 satellites, 3.8 m accuracy, indoors**). 583 km
is the true distance from there to camp (~588 km by hand). Written yesterday,
never exercised, and it did exactly the job: navigation kept working, silently,
with no badge — which is what Rob asked for.
- **This also settles the open hardware question:** the S9+ holds a strong fix,
  so the fallback is real rather than theoretical. In a *fiberglass* car it
  should be at least this good.

**Fleet housekeeping:** S9+ `adb tcpip 5555` re-armed after its battery-death
reboot (back at `192.168.86.112:5555`); both tablets on the current build; both
returned to brightness 20 and slept per OLED discipline; Jetson restored to
`--source fake --hz 10`.

## 2026-08-06 — Jetson FLASHED and broadcasting on the fleet bus

The edge box is real. Flashed from `grr` over USB-C, booted from NVMe, running
zvision as a systemd service and putting live ZTHREAT frames on the bus —
verified received by a *different* machine on the LAN, which is the end-to-end
proof that matters.

**State:** hostname `zvision` @ **192.168.86.235** (also `192.168.55.1` over the
USB gadget link), Ubuntu 24.04.4 / kernel 6.8.12-1021-tegra, **L4T R39 rev 2.0
(JetPack 7.2)**, root on `/dev/nvme0n1p1`, MAXN_SUPER, zvision enabled at boot.
Every pre-seeded thing landed: user, 5 SSH keys, NOPASSWD sudo, sshd — no
`oem-config` stall, key auth worked from the Mac first try. All **179 zvision
tests pass on the Jetson itself**. Second account `rob` added (key
`owner@CF-SV`, NOPASSWD sudo, in `video`/`dialout`/`i2c`/`gpio` for the cameras).

**Four corrections to `DEPLOY.md`, all found the hard way:**
1. **MAXN_SUPER is mode 2, not 0.** On JetPack 7.2 the table is `0=15W · 1=25W ·
   2=MAXN_SUPER`, and the board ships on 1. The doc said `-m 0` — following it
   set the box to the **slowest** mode while looking like success. Real numbers
   at mode 2: GPU max 612 → **1020 MHz**, CPU 1497 → **1728 MHz**. Also needs
   `PM_CONFIG DEFAULT=2` in `/etc/nvpmodel.conf` or it reverts every boot.
2. **`nfs-kernel-server` is mandatory.** I had recorded it optional because
   `exportfs` is `command -v`-guarded in one path; the flash tool hard-requires
   it (it serves the rootfs to the booted initrd over USB via NFS). Installed on
   grr, then **disabled** — nothing exported, won't start at boot.
3. **OpenSSH ≥ 9.8 breaks the recovery-image build.** L4T calls
   `ssh-keygen -t dsa`; grr runs OpenSSH 10.2 → `unknown key type dsa` → a bare
   `check_error` → the whole flash dies with only `command is failed`. Patched
   the line out; the initrd sshd config references only rsa/ecdsa/ed25519, so
   that key was never used. `.orig` kept beside it. **This is the Ubuntu 26.04
   incompatibility predicted for Python, showing up in OpenSSH instead —
   Python 3.14 was fine throughout.**
4. **OpenCV is not preinstalled** on JetPack 7.2 (`import cv2` fails). Needs
   `nvidia-opencv` **and** `libopencv-python` — the first installs only the C++
   libs. The apt build reports no CUDA, which is fine: the motion detector is
   CPU work and the trained model goes through TensorRT.

**Also fixed:** `systemd/zvision.service` had `StartLimitIntervalSec` under
`[Service]`, where systemd ignores it with a log warning — silently defeating
the infinite retry it existed to guarantee. Moved to `[Unit]`.

**Nice find:** the Jetson answers on **192.168.55.1 over the flash cable** (USB
gadget network) before any DHCP lease exists — the fastest way in when Ethernet
isn't ready.

## 2026-08-06 — no "on backup" badge (Rob's call); a silent beacon stops lying instead

Rob's call on the failover indicator: **no badge.** "We just want things to keep
working." Left undrawn — `locationFallbackActive` stays in UI state for whoever
wants it later, but nothing renders it. (Also: the car is **fiberglass**, not
metal, so tablet GNSS reception should be far better than assumed — fiberglass
is RF-transparent. Still needs testing where mounted.)

That question surfaced a real bug next door. `_beaconSensors` was only ever
*written* on receipt and **never cleared**, so a dead beacon left the ops footer
confidently showing `BATT 87% SATS 9 UP 3h07` indefinitely. Not silence — the
display asserting something false, and the frozen uptime is the worst of it
because a plausible number reads as live.

`NetworkLocationSource` now tracks **any-line** arrival (`beaconRxMs`, distinct
from `positionRxMs` — a beacon indoors keeps reporting battery and heading with
no GPS, and those readings are still good) and drops the bundle after
`BEACON_SILENT_MS` = 12 s, i.e. two missed `$ZBCN` beats. `shockCount` is
preserved across the clear: it's a monotonic counter the ViewModel diffs
against, so rewinding it would swallow the next real impact.

## 2026-08-06 — S9+ has its own GNSS; beacon now auto-fails-over to it

The **Tab S9+ Wi-Fi (SM-X810) carries GPS/GLONASS/BeiDou/Galileo/QZSS**, plus a
compass and ambient-light sensor. Correcting the record: "Fire tablets have no
GNSS" is a *Fire* fact that had generalised into "tablets have no GNSS".

**The beacon stays the source of truth** — every tablet agreeing on one position
beats any single tablet's accuracy, the roof antenna sees more sky, the Fire has
nothing, and position is one of seven channels. The S9+'s receiver buys a
*backup*, so a dead beacon degrades the hero display to slightly-worse
navigation rather than none.

`FailoverLocationSource` + pure `LocationFailoverPolicy`:
- **Presents as the NET source**, so registry/chip/persisted preference are
  untouched and the saved choice is never rewritten to SYSTEM.
- **Both sources run at once** — the crux, because `RoutedLocationSource.select`
  *stops* what it switches away from, so a routing-based failover could never
  see the beacon return. Also keeps the backup warm.
- **Asymmetric hysteresis:** drop 3 s past NET's own 5 s staleness; recover only
  after **10 s** clean, because a half-alive beacon would otherwise bounce the
  cockpit between two sources that disagree slightly.
- **Won't fail over to a backup with no fix**, and **won't arm without GNSS
  hardware** (`FEATURE_LOCATION_GPS`, asked of the device not assumed per model).
- Surfaced via `RoutedLocationSource`, not a 10th VM constructor param — that
  class is at its detekt limit and already flagged a god-object.

**Test lesson:** the first version drove the policy from a manual `t` variable
while advancing virtual time separately; elapsed time never accumulates and
every timing assertion passes for the wrong reason. The clock must *be*
`testScheduler.currentTime`. And `advanceUntilIdle` is unusable against an
infinite ticker — `advanceTimeBy` + `runCurrent` is the idiom.

## 2026-08-04 (night) — `--record` built, TRAINING.md written, flash pre-flight green

**`--record` (`zvision/recorder.py`) — the schedule-critical piece.**
`DETECTOR.md` had said all along that the GPU is useless without our own footage
and that recording must start before the first drive; it was the one roadmap
item with a real deadline and it didn't exist. The asymmetry is the argument:
**frames can only be captured while the rig is on the vehicle, GPU time can be
rented any evening.** Each frame writes the image plus an `index.jsonl` line
carrying the **pixel boxes** the motion detector found — weak labels, so an
annotator *corrects* (~2–5 s) instead of *draws* (~10–20 s). Defaults: 1 Hz
(10 Hz frames are near-duplicates), PNG for thermal (JPEG artefacts on
low-contrast 120×120 destroy signal), 20 GB cap (a full root filesystem takes
the box down — worse than a short dataset). **Recording must never break
detection**: every failure swallowed, counted, reported once.

**Camera format control:** `UvcCamera` gained fourcc/fps (+ `.actual()`), exposed
as `fourcc=`/`fps=` keys. FOURCC is set *before* frame size because several V4L2
drivers reset the negotiated size when the pixel format changes underneath them.

**TRAINING.md** — mostly deflationary: **RGB needs no training** (COCO already
has `person`/`bicycle`), thermal does but the **bottleneck is data, not
compute**. Records the honest reason for a model at all: **background
subtraction assumes a stationary camera**, so today's detector degrades badly
once the vehicle moves. Flags that **TensorRT engines are not portable** — an
engine built on the H100 will not load on the Orin; export ONNX on the server,
compile on target. And that the multi-GPU box is best spent **auto-labelling**,
not the 20-minute fine-tune.

**`scripts/preflight-flash.sh`** — read-only pre-flash check; ran 17 ok / 0 fail
against grr. Fails in a warm room instead of after the jumper dance.

## 2026-08-04 — `grr` boot-hardened for playa (no monitor); validation still pending

`grr` is now load-bearing for **two** things on playa — the camp audio station
*and* being the only x86 host that can re-flash the Jetson — while having no
keyboard, mouse or monitor out there. Confirmed it has **no serial console**:
the 32 `/dev/ttyS*` are 8250 driver stubs, no real UART, no serial connector in
DMI. So a console means HDMI + USB keyboard, full stop.

Rather than just carry a screen, reduced the *need* for one:
- **`fsck.repair=yes`** added to the GRUB cmdline (verified in all 3 boot
  entries). `/`, `/boot`, `/boot/efi` are fstab `pass=1` with `fsck.repair`
  unset, so an unclean shutdown — i.e. vehicle power — could stop boot at an
  **interactive repair prompt waiting for a keypress at a console that won't
  exist**. That was the single most likely "grr won't boot" scenario.
- **Rescue link:** static `192.168.99.1/24` on the spare NIC `enp2s0`, so a
  laptop at `192.168.99.2/24` plugs straight in and gets SSH with router, WiFi
  and Tailscale all dead. Gotcha found: **netplan on this release does not
  translate `optional: true` into `RequiredForOnline=no`** (it emitted no
  `[Link]` section at all), so an unplugged rescue port would have *blocked
  boot* — the opposite of the goal. Forced via a systemd-networkd drop-in.

**Deliberately NOT changed: `systemd-networkd-wait-online`.** Intended to
disable it, then found **`docker.service` depends on `network-online.target`**
and docker runs the audio station — weakening it risks Icecast/Liquidsoap
starting before the network. Worst case as-is is a bounded ~120 s delay, not a
hang. Left alone; noted for the station manager that netplan generates *two*
sequential `ExecStart` lines, the second stricter (`--dns -o routable`).

Backups, full rationale and rollback commands live on the box at
`/root/zodiac-hardening-backup-20260804-221708/CHANGES.md`, since a different
Claude instance manages that station.

**Unverified, and that matters:** `fsck.repair` only applies on next boot, and
the rescue port reads `no-carrier` until a cable is in. Rob bought a monitor,
an HDMI capture dongle and a USB-C→Ethernet adapter; he'll run the three
validation tests ~2026-08-06. The proof to look for is
**`cat /proc/cmdline` containing `fsck.repair=yes`** — everything else is
inference. Best time to break boot config is at home with a screen attached,
not on playa.

## 2026-08-04 — zvision field-tunable without a code edit + `--check`; grr travels

Prompted by the right question from Rob: *no keyboard/mouse/monitor on playa,
just a MacBook — is expecting the shipped build to be final naive?* Yes, but the
useful split is **by layer**: the OS/JetPack image is effectively fixed (needs an
x86 host + recovery jumper), while zvision is fully mutable over SSH. **And `grr`
travels as the camp's audio player**, so even the OS layer is recoverable in the
field — re-flashing on playa is possible. Keep `~/jetson/Linux_for_Tegra` on it.

**The gap this exposed:** the parameters most likely to need on-site tuning were
*hardcoded* — `min_area_frac`/`match_dist` as `MotionDetector` ctor defaults that
`build_camera` never passed, `far_h`/`near_h` taken from `bbox_height_to_size`'s
defaults at the call site, and a bare `CollisionEstimator()` freezing the
collision thresholds. Changing detection sensitivity meant editing Python on the
box, by headlamp, in dust.

Now: a `DetectorTuning` dataclass carried on `CameraMount`, surfaced as
rig-wide flags (`--min-area --match-dist --far-h --near-h --collision-az-rate
--collision-min-size`) **and** per-camera spec keys (`minarea match farh nearh
azrate minsize`), where a spec only states what makes *that* camera different.
Global flags now also seed the optics defaults, so `--hfov`/`--lens`/`--fov-ref`
apply to `--camera` specs instead of being ignored on that path.

**`--check`** validates the config, prints the resolved rig + blind arcs, and
exits **without opening a camera or touching the network**. This exists because
the unit is `Restart=always`: an unvalidated typo in `/etc/default/zvision` is a
crash loop, which is the worst possible thing to debug from a laptop in the
dust. It catches unknown keys, non-numeric values, and an inverted
`nearh <= farh` (which would silently make every contact read as maximum range).

Also recorded: **the tablets are the monitor.** Change a value, restart, watch
contacts on the DRIVER HUD — a better loop than a screen on the Jetson, since
you see what the driver sees. +15 tests → **167 jetson green**.

## 2026-08-04 — Jetson flash host stood up on `grr`; SDK Manager rejected; camera ring sized

Hardware/bring-up session. No app code changed; `jetson/DEPLOY.md` §1 rewritten
from the ground up because most of what it said was wrong.

**Flash host = `grr`** (the camp's Galactic Relay NUC, x86 Ubuntu 26.04, on the
`burbot-haddock` tailnet as `100.82.29.101`). This closes the long-open "what
will you flash the NVMe from?" question — the Mac can't, because Tegra recovery
is a **USB protocol spoken by the boot ROM**: at recovery time the board has no
OS, no bootloader and no network stack, so USB is the only way in. A dedicated
`zodiac` user + NOPASSWD sudo + a dedicated SSH key. The audio station was left
untouched (never joined the `docker` group).

**SDK Manager is the wrong tool and we're not using it.** Two independent
blockers: it's a GUI app (useless over SSH), and its supported hosts are Ubuntu
20.04/22.04 while `grr` is 26.04. Container workarounds are reported flaky
*specifically for NVMe*. Instead we use the BSP's own **`l4t_initrd_flash.sh`** —
which is what SDK Manager wraps anyway: no GUI, no NVIDIA login, headless, and
the documented path for external NVMe.

**JetPack 7.2 / L4T 39.2, not 6.x.** r39.2 added Orin Nano Dev Kit support, and
its newer toolchain is *more* tolerant of a new host — the inverted risk that
made a 26.04 host viable. **Verified end to end with no board attached:**
`apply_binaries.sh` completed `Success!` with zero errors (that's the arm64 qemu
chroot, the step most likely to break), and `l4t_initrd_flash.sh` runs on Python
3.14. The feared 26.04/3.14 incompatibility did not materialise.
- **26.04 gotcha:** `qemu-user-static` no longer exists — it's `qemu-user`
  (still static-pie). L4T hard-codes the `-static` name → symlink required. And
  apt aborts the *entire* transaction on one bad package name, so the first
  install silently did nothing.

**J14 pinout settled from the authoritative source** (carrier spec SP-11324-001
Table 3-4, extracted from the PDF) — pins 9/10 = GND/FORCE_RECOVERY, 7/8 =
GND/RESET, 3/4 = 3.3 V debug UART, 11/12 = GND/power. **No pin carries a supply
rail**, so a screwdriver slip is at worst a reset or a power-button press, not
damage. Kit ships no jumpers; ATX front-panel momentary switches fit and are
better than jumper caps (hold-and-release, 27" lead, and recovery becomes
repeatable without touching the power cable).

**Two things that will bite later, recorded now:**
- **The dev kit is rated 0–35 °C ambient.** Playa daytime is 38–40 °C — we are
  deploying *above* the rated range. Shade/case/venting are load-bearing.
- **The USB camera-ring constraint is isochronous *reservation*, not throughput.**
  Cameras over-declare `dwMaxPayloadTransferSize` (one measured case: 195 Mbps
  requested for a stream needing 46), and USB 2.0 allows only 80% of each
  microframe for isoc → `-ENOSPC` long before the bus is full. Consequences:
  **a powered hub adds no bandwidth** (everything behind it shares one upstream
  link) — only a *separate controller* does; `uvcvideo quirks=128` only helps
  *uncompressed* formats, so MJPEG and that quirk are partly at odds; the dev
  kit's 4×Type-A + USB-C-host = 5 ports for 5 cameras, so **direct-connect, no
  hub**, and put the thermal on the C port for a separate domain. Untestable
  until the cameras arrive — bench it before sealing anything.

**Camera ring sized: 4 RGB total (3 more ordered) + the UW thermal.** Ran the
candidate layouts through the new `coverage_gaps()`: 3 RGB closes the ring only
with a **2.5° seam** (or **0.0°** if the UW's 160° turns out to be diagonal),
while 4 gives 9–15° in both cases. Bought the **IP65-housed IMX662** variant for
$10 more, which also solves the un-costed per-camera enclosure problem; the
95° *diagonal* lens works out to **87° horizontal**, near-identical to the 85°
the merge math assumed, so no re-plan. Mixing the in-hand IMX462 with IMX662s is
fine — the rig fuses *bearings*, never pixels, so there's no cross-camera
registration to break.

**Still open:** the Lepton UW's 160° — horizontal or diagonal? Unresolved, and
it changes edge bearings by tens of degrees (`--fov-ref`).

## 2026-08-03 — zvision UW fisheye + multi-camera 360° merge (THE next task — done)

Built the approved next task in `jetson/zvision`, device-independent and green
(app 410 / beacon 35 / **jetson 152**). Four commits, each runnable.

**1. Real lens models (`geometry.py`, commit `ccda40a`).** `pixel_to_bearing`
unprojects (cx, cy) → (az, el) through a selectable projection —
`rectilinear` / `equidistant` (f-theta) / `equisolid`, plus `linear` to keep the
old map — normalised by frame aspect, using `atan2` so a >180° lens' edge
bearings stay correctly signed instead of folding back toward zero.
- **Correction to the premise:** the old flat `(cx-0.5)*hfov` was described as
  wrong at the edges. For an *equidistant* fisheye it's actually exact **along
  the horizontal centreline** — that's why it survived on the narrow Lepton. The
  real errors are (a) **`--hfov 57` on a 160° lens**, by far the biggest, (b)
  off-centreline contacts, where vertical off-axis angle feeds into azimuth, and
  (c) **rectilinear lenses** (the RGB modules), which are never linear in x.
- **New decision — `FOV_HORIZONTAL` vs `FOV_DIAGONAL` (`--fov-ref`).** Datasheets
  don't say which dimension a quoted FOV spans and on a wide lens it's decisive:
  **160° horizontal across a square sensor implies a ~226° diagonal (physically
  impossible)**, while 160° diagonal on that sensor is only ~113° horizontal. We
  need to determine which the Lepton UW's 160° actually is before trusting edge
  bearings — the code supports both, the hardware answer is still open.

**2. Wire arc ±90 → ±180 (commit `a8dedb3`, both sides).** The ZTHREAT contract
capped bearings at ±90 ("not in front of the vehicle") — that filter would have
silently deleted **every** rear contact before it reached a tablet. Widened in
`threat_protocol.py` **and** Kotlin `ThreatProtocol.kt` together (byte-exact
mirror; the round-trip tests guard the tablet's half). *User chose this over
keeping the wire narrow.* `DriverNightScreen` now filters to the forward ±90
explicitly, because it places contacts at `az/THERMAL_HALF_FOV_DEG` — an
unfiltered rear contact would draw off-canvas and, worse, **a collision astern
would fire "! BRAKE !" at the driver**. Display is unchanged; surround HUD is
the follow-up.

**3. Multi-camera rig (`rig.py`, commit `8b199fd`).** N cameras → one full-circle
list: `camera-local az --(+ mount_az)--> global az --(overlap dedup)--> merged`.
- `CameraMount` (where it looks / how wide / what lens) + `arc()` +
  **`coverage_gaps()`** — verbose start-up prints each arc *and the blind
  sectors*, so a ring that doesn't close is found at boot, not by wondering why
  someone behind the car never appeared.
- `--camera thermal:/dev/video0:az=0:fov=160:lens=fisheye`, repeatable
  (*user chose repeatable CLI flags over a JSON rig file*). Unknown keys are a
  hard error — a typo'd mount angle aims a real spotlight at the wrong person.
- `to_global` namespaces track ids per camera (`ID_STRIDE`), else the tracker
  light latches onto "id 1" and gets a different person each frame as cameras
  take turns. Ad-hoc id 0 stays 0 everywhere.
- `merge_contacts` collapses **cross-camera** duplicates within `--merge-deg`
  (8° default) but never merges two contacts a single camera deliberately
  resolved; ties break collision-then-nearest.
- `--hfov` default **57 → 160**; `build_detector` → `rig.build_camera`.

**4. A camera that won't open must not sink the rig (commit `0158716`).** Found
by smoking the CLI: eager `build_rig` died with a raw traceback on one missing
`/dev/videoN`. On a vehicle where five USB cameras enumerate in whatever order
they like, that trades one blind arc for a blind night. Now skipped-and-reported
at startup (mirroring the existing per-frame guard); if *nothing* opens the
runner **exits 3** rather than settling into a confident stream of empty
"all clear" frames while completely blind.

**Follow-ups this leaves:**
- **Surround DRIVER HUD** (on-device, tablet side) — rear contacts are on the
  bus now and nothing draws them.
- **Is the UW's 160° horizontal or diagonal?** Decides `--fov-ref`; check the
  FLIR datasheet or measure it on the bench.
- **Calibrate each mount's `az` physically** against the vehicle nose — an error
  there rotates that camera's whole contact set and swings the light.
- **RGB count + lens FOV still TBD** — that choice is what closes the ring
  (the UW already covers the forward 160°, so the RGB ring mainly owns sides/rear).

## 2026-08-03 — Session handoff (context restart to a newer Claude Code)

Fresh-instance orientation lives in the memory **`project_zodiac_resume_point.md`** (comprehensive) — read it + the entries below. State: **whole fleet on the latest build**, **main clean/green** (app 409 / beacon 35 / jetson 83 tests). This long session shipped: the 5 beacon sensor channels (producer+consumer) + tablet auto-dim, the beacon on-device full readout, the DMX tracker + sound-reactive light, the 2026 map migration, a docs sync + test-coverage expansion, and the thermal-camera switch to the Lepton Ultra Wide.

**⭐ THE NEXT TASK (approved by the user, deliberately NOT started — do it fresh):** in `jetson/zvision`, (1) replace the linear `bbox_to_rel_az` with a **fisheye→azimuth** map + default `--hfov 160` for the UW lens, and (2) add **multi-camera merge** — N `detect()` streams, each tagged with a mount-angle+FOV, fused into one full-circle threat list (rig = 1 forward thermal + several RGB for 360°). Device-independent + unit-testable; the surround DRIVER HUD is the on-device follow-up.

## 2026-08-03 — Thermal camera → FLIR Lepton **Ultra Wide** (160°)

Switched the thermal sensor from the planned Lepton 3.5 (57°, radiometric) to the **Lepton Ultra Wide: 160° FOV, 120×120, non-radiometric**, on a **PureThermal Mini USB** carrier (compact + standard USB-C, plug-and-play `/dev/videoN`). **One thermal camera** (1 board + 1 UW). **360° surround will come from cheaper RGB (IMX462) cameras, not more thermals** — the one thermal is the forward / total-dark sensor where it earns its cost. Rationale: the mission is people **all around** a slow art car, so wide surround coverage beats forward range; non-radiometric is fine because zvision detects warm blobs by **contrast/motion, not absolute °C** (and the daytime 120 °F ground already rules out temp-thresholding). Germanium window upsized **D15→D20** and must mount **~2 mm from the lens** (at 160°, required window radius ≈ standoff × tan80° ≈ ×5.7). Nothing bought until now — the earlier "3.5 + PT3 ordered" note was never actually placed.

**Open follow-ups this creates:**
- **zvision code:** replace the linear `bbox_to_rel_az` with a **fisheye→azimuth** map and change `--hfov` default 57→**160**, else edge bearings (and the DMX light aim) are wrong on the wide lens.
- **Multi-cam 360° surround** (the plan): **1 forward thermal (UW) + several RGB (IMX462)** around the vehicle (RGB is cheap + a good low-light STARVIS sensor; the lit-up art car gives it enough light). zvision runs N `detect()` streams, each tagged with its mounting angle, merged into a full-circle threat picture + a surround DRIVER HUD (today's HUD is single forward camera).

## 2026-08-01 — Docs sync + test-coverage expansion (two sub-agents) + fleet on latest

Fleet-wide catch-up while device-managing:
- **Fleet updated to the latest build.** S9+ (SM-X810) + A54 (SM-A546V) got the cockpit build over USB/wireless and a sticky `adb tcpip 5555` link (survives sleep, resets on reboot — reconnect via the rotating wireless-debug port otherwise). XCover (SM-G715U beacon) got the latest **beacon** build (all 5 sensor channels); its mic/`$ZAUD` needs a one-time STOP→START to pick up the RECORD_AUDIO grant (no rush — nothing consumes `$ZAUD` until the Jetson light runs). Fire HD 10 still pending. OLED tablets are slept (backgrounded app → no keep-screen-on lock → backlight off), woken only for screencaps.
- **Docs audit + fix (sub-agent, commit `1ef88b8`):** README/CLAUDE/ARCHITECTURE/jetson-README brought current — three concepts (RADAR/MAP/DRIVER), all 5 beacon channels, DMX tracker + idle `$ZAUD` show, 2026 map, auto-dim, device roster w/ model numbers, dual-CI. Every claim verified against code. SYNC untouched (append-only). **Flagged:** `HANDOFF.md` is a "read-me-first/current-state" doc but timestamped 2026-07-06 + still says two concepts — left as a dated snapshot pending a call on whether it's living or historical.
- **Test-coverage audit + build (two sub-agents, commits `db6c44c`/`3ab701a`/`d7aa2c0`): +31 tests → App 409, Beacon 35, Jetson 83, all green.** Closed the zero-coverage gaps: VM shock-alert timing + beacon-sensor fold, `GoldenSpike.ACTIVE==Y2026` pin, `PlayaMapRepository` art-optional fallback, NavTarget/Camp 2026 coords, beacon GGA/subnet (extracted `BeaconNet` internal seam), `OlaDmxSink` error-swallow. Two behavior-preserving hardening fixes surfaced: `deg_to_dmx16` non-finite guard (park sentinel is NaN, `round(nan)` raised) + the beacon net seam.

## 2026-07-30 — 2026 base-map migration DONE in code + Jetson bring-up prep + sound→DMX

Big autonomous push (user away, "do all the things you can"):

- **2026 map migration (commit `ca74867`, all gates green).** Flipped the cockpit from the 2025 city to 2026 via a single active-year source of truth: `GoldenSpike.ACTIVE = Y2026` / `ACTIVE_YEAR = 2026`. All ~11 `Y2025` refs now key off `ACTIVE` (incl. the fake-GPS center so it circles the 2026 city). `NavTarget.MAN`→ACTIVE, `TEMPLE`→the 2026 CPN, `Camp` HOME re-projected on the 2026 grid. Map + discovery year → 2026 (art stays hidden until BM releases 2026). `PlayaMapRepository` art layer made **optional** (2026 GIS has no `art.geojson` → empty, not a load failure). **Ring radii verified unchanged** vs 2026 street data (B=979 m, G=1470 m exact). Tests re-based onto ACTIVE. **Only gate left: on-device — type our camp (2:15 & H) and confirm it lands right.**
- **Jetson bring-up prep (pre-hardware).** `DEPLOY.md` is now a full runbook: added a day-one **bring-up checklist**, a **DMX tracker §7**, and `scripts/install-ola.sh` (installs OLA, enables only the ftdidmx plugin, CPU-pins olad so the ML load can't jitter DMX, on-boot) — the DMX side had zero bring-up before. Software is 100% ready; the box just needs to boot (NVMe 512 GB en route).
- **Phase 4 sound→DMX (commit `2d02297`).** `audio_bus.py` (`parse_zaud` + `ZaudListener` background UDP thread on the telemetry group) + the tracker's idle sound show (rms→dimmer, beat→flash; a live target always overrides). `--dmx` starts it, `--dmx-no-sound` opts out. +13 tests → 80 jetson green.

## 2026-07-30 — 2026 GIS data is live; city moved ~583 m (base-map migration staged)

BM published the **2026 Innovate GIS** (`burningmantech/innovate-GIS-data` → `2026/GeoJSON`). Pulled all 9 layers into `assets/brc/2026/` (city_blocks, cpns, dmz *(new)*, gate_road *(new)*, plazas, street_lines, street_outlines, toilets, trash_fence — **no art**; art/camps are the BM-API side, 2026-embargoed). Derived the georeference from the data:
- **The Man moved ~583 m SW.** 2026 "The Man" CPN = `lon -119.20788409599999, lat 40.783247448000054` (2025 was `-119.20300709606865, 40.78696344894566`). Captured as `GoldenSpike.Y2026`. **Running 2025 coords at a 2026 event = every ego/nav ~583 m off** — this is why the update matters.
- **City did NOT rotate:** every 2026 radial confirms the 12:00 axis is still **45.0°** (Temple 44.9°, 4:30 portal exactly 180.0°, 3:00 → 135.1°, 9:00 → 315.1°). So `BRC_AXIS_BEARING_DEG` is unchanged.

Staged only (build green, nothing wired yet — `PlayaMapRepository` still defaults to "2025"). Full flip is an event-critical task in `tasks/open.md` with the two real decisions flagged: (1) a single active-year indirection vs flipping ~8 `Y2025` refs; (2) **art/camps** — 2025 locations projected about `Y2025` would sit ~583 m off a 2026 base map, so either hide art until BM releases 2026 or keep it on a 2025 origin (mixed). Wants on-device verification (a known address must land right) before trusting it for nav.

## 2026-07-30 — Phase 3a: tablet consumes beacon sensors + ambient-light auto-dim

Tablet side of the sensor channels (commit `20f7f8a`) — plumbing + the auto-dim,
green. `NetworkLocationSource` parses `$ZENV/$ZBCN/$ZODO/$ZSHK` into one bundled
`BeaconSensors` flow (audio `$ZAUD` deliberately NOT ingested on the tablet — it's
a Jetson/DMX concern, 15 Hz). Shock rides a monotonic `shockCount` so equal-g
bumps still register. `CockpitViewModel` takes the bundle as **one** flow (VM kept
to a single new dep; detekt `constructorThreshold 9→10`) → folds lux/health/
odometer into `CockpitUiState` + flashes a transient `shockAlertG` per new shock.
**Auto-dim (product decision: brightness only, NOT concept-switch):** pure
`luxToBrightness()` log curve (night floor 0.05 → day ceiling), applied in
`MainActivity.autoDim` to `window.screenBrightness`; null lux → system brightness.
DI: `NetworkLocationSource` hoisted so its flow reaches the VM. Tests: the
brightness curve + ZENV/ZBCN/ZODO/ZSHK loopback ingest.
- **Phase 3b (remaining, on-device):** render odometer + beacon-health in the ops
  footer and the `shockAlertG` banner (UiState fields are plumbed, not yet drawn) —
  wants visual sign-off per concept. **Plus the whole live on-device verification
  of Phases 2a/2b/3 once the fleet's back on a stable WiFi.**

## 2026-07-30 — Beacon Phase 2b: mic → `$ZAUD` (producer side complete)

Landed the microphone channel (commit `44785de`), so the beacon now broadcasts
**all five** new channels + the original GPS/heading/tilt. `AudioLevels` (pure,
tested): PCM block → normalized rms/peak + energy-vs-running-average beat flag;
`AudioRecord` loop (16 kHz mono, ~15 Hz) emits `$ZAUD` per frame — only a
level/beat number leaves the phone, no audio stored/transmitted. `RECORD_AUDIO`
optional (denied → audio skipped, rest keeps broadcasting); manifest gains the
mic permission + `foregroundServiceType=location|microphone`. **Producer side is
now feature-complete; remaining = Phase 3 tablet consumers/UI (product call:
lux → auto-dim brightness only, NOT concept-switch) + Phase 4 Jetson `$ZAUD`→DMX;
all pending on-device verification once the network settles.**

## 2026-07-30 — Beacon sensor-channel expansion (sound/light/shock/health/odometer) — Phases 1 + 2a

Extending the Sensor Hub (XCover beacon) from GPS+heading+tilt into a broader
fleet sensor: five new proprietary sentences, same `$ZTLM` pattern (read sensor →
format → multicast bus → consumer). User picked all five: **sound, light, shock,
beacon-health, track/mileage.** Building in green phased commits, unit-tested
without the devices (on-device verify deferred — network in flux during a move).

- **Phase 1 (commit `d23050a`) — the wire contract, both sides, zero runtime change.**
  `$ZAUD,rms,peak,beat` (sound) · `$ZENV,lux` (light) · `$ZSHK,peakG` (shock,
  event-driven) · `$ZBCN,batt,fixQ,sats,uptimeS` (health) · `$ZODO,tripM,totalM`
  (odometer). Beacon `Nmea.kt` builders (Locale.US + shared checksum); app
  `NmeaParser` parsers with isFinite/range guards + a shared `fieldsForType()` /
  `finiteAt()` helper (refactored `parseVehicleTelemetry` onto it); five
  `core/telemetry` models. Tests both sides. detekt `thresholdInObjects: 18` for
  the growing central parser (flagged: split the proprietary Z-sentences if it
  grows further).
- **Phase 2a (commit `c88ccef`) — beacon reads the four no-permission sensors.**
  `TYPE_LIGHT`→ZENV; `TYPE_LINEAR_ACCELERATION`→`ShockDetector` (threshold +
  refractory, pure/tested)→event ZSHK; battery (sticky `ACTION_BATTERY_CHANGED`)
  + fix-quality/sats (from the GGA passthrough) + uptime→ZBCN; `TripOdometer`
  (haversine + 5 m jitter floor, pure/tested; total persisted in SharedPreferences)
  →ZODO. Slow channels ride the 250 ms loop on a tick divisor; `onSensorChanged`
  split per sensor. Full beacon gate green.
- **Still to do:** **Phase 2b** — mic/`$ZAUD` (needs `RECORD_AUDIO` + manifest
  `foregroundServiceType` + runtime request + `AudioRecord` RMS/beat, isolated
  because it touches permissions). **Phase 3** — tablet consumers + UI (ingest the
  5 sentences into `NetworkLocationSource` StateFlows → VM → UI: **auto day/night
  from lux [product decision: auto-switch vs suggest], beacon-health + odometer in
  the ops footer, shock alert**; mind the VM `constructorThreshold: 9` — bundle the
  new sources). **Phase 4** — Jetson `$ZAUD`→DMX sound-reactive tracker (Python).
  **On-device verification of all of it is pending a stable network.**

## 2026-07-29 — DMX tracker light software (`zvision` drives a moving head)

Built the edge-box's second output: a moving-head "tracker" light that physically
points at a detected contact, driven **in-process** from the *same* per-frame
`DriverThreat` list the HUD broadcaster already gets (the Jetson has computed each
contact's bearing, so aiming a light is just az→pan). Same design ethos as the
detector: a pure, stdlib-only, unit-tested core + a lazy hardware transport behind
a Protocol, so the whole pipeline is provable before the dongle/fixture arrive.

- **`zvision/tracker.py`** (pure math): `select_best` (collision-priority, else
  nearest by size) + a stateful `Tracker` adding **hysteresis** (`switch_margin`
  stops ping-ponging between two similar-range contacts; collisions bypass it) +
  **slew limiting** (`_approach`, deg/s ceilings so the head *follows* smoothly,
  never jerks) + az→pan (`center + az*gain`, calibratable) and a size→tilt
  range-proxy lerp. 16-bit pan/tilt (`deg_to_dmx16`), optional fine/dimmer
  channels. On no-target it **holds aim + blacks out** (an idle sweep would read
  as a false detection); `park()` recenters + blackout for a clean shutdown.
- **`zvision/dmx.py`** (transport): `DmxSink` Protocol; `FakeDmxSink` (in-memory
  512-byte frame, stdlib); `OlaDmxSink` posts the universe to a local **`olad`**
  over its **HTTP API** via stdlib `urllib` — deliberately *no* `ola` pip dep and
  no inverting our loop into OLA's event loop. A send failure (olad down/dongle
  unplugged) is counted + swallowed so a lighting glitch can't take down the
  threat broadcaster the HUD depends on. Channels persist frame-to-frame (merge,
  not overwrite).
- **Runner wiring:** `--dmx {none,fake,ola}` (default none), `--dmx-universe`,
  `--dmx-url`, `--dmx-pan-center`, `--dmx-pan-gain`. Loop taps `detector.detect()`,
  feeds the tracker, sends the sink; `--once` snaps (large dt); exit parks+blacks.
- **Proven with `--dmx fake`** (no hardware): picks the nearest of the three fake
  contacts (id2 @ −22° → `pan=248 = 270−22`, `tilt=146.2 = lerp(135,160,0.45)`),
  follows it, parks clean. **+23 tests** (`test_tracker.py`, `test_dmx.py`) →
  **67 total green**; `jetson-ci` unchanged (still `unittest discover` + `--once`).
- **Still hardware-gated:** `OlaDmxSink`→real `olad`→FTDI dongle→fixture is
  untested (same status the camera was in before it landed). On-vehicle: pin `olad`
  to a dedicated Orin core (`taskset`) so the ML workload can't jitter DMX timing;
  calibrate pan-center/gain against the mounted head. `size→tilt` is a coarse
  range→elevation proxy until the detector emits real vertical.

## 2026-07-19 — Safe test-coverage expansion round (+ a latent BM-API bug surfaced)

Continued adding pure-logic regression tests (no production behaviour changes except widening two BmApiClient parsers to `internal` for testability):
- **zvision:** broadcaster per-target error isolation (the headline claim, via a wrapped socket), success count, `extra_targets`; `app --once` end-to-end over loopback (frame + exit all-clear); `CollisionEstimator` min-size boundary + track independence.
- **routeTo:** the straight-line guard branches (dest at the Man, beyond the outer road, un-modelled city, in-city with no radials) + `nextWaypoint` edges (empty→null, single, zero-length segment).
- **`campPoint`:** hour-only frontage, `0:00`→12, out-of-range/negative clock, whitespace/case, custom axis rotation.
- **`ClockEntry`/`RadarContact`:** non-digit + over-long keypad entries; `max=0`, at-range inclusivity, `contactPulse(fadeSpan=0)`→floor-not-NaN.
- **`DiscoveryRepository`** (was zero-coverage) and **`CockpitUiState.activeDriveTarget`/BATH** (landed earlier this round).
- **`BmApiClient`** parsers via direct JSON fixtures (no HttpServer — `com.sun.net.httpserver` isn't on the Android unit-test classpath; made `parseArt`/`parseCamp` internal).

**LATENT BUG surfaced by the BmApiClient test (needs real-API verification, NOT fixed):** `parseCamp` reads `location_string` from the **top level** of the record, but `parseArt` reads it **nested under `location`**. If the real BM API nests it for camps too (as it does for art), **camp subtitles are empty in production**. Pinned current (top-level) behaviour in `camp_with_a_clock_address_is_placed` with a flag comment so any change is deliberate. Verify against a real `/api/camp` response and align the two parsers.

## 2026-07-19 — GPS staleness watchdog (dead feed stops guiding off a frozen fix)

The #2 review finding (both arch + net agents, HIGH): `NetworkLocationSource` never demoted `Active` — a dead beacon left a frozen position guiding forever, made worse because the beacon keeps sending HDT/ZTLM even with GPS lost, so `ingest()` re-asserted `Active` on every heading/telemetry line over a stale position. **Fix:** track `positionRxMs` separately (only GGA/RMC refresh it); `Active` is emitted only while the position is fresh (`nowMs()-positionRxMs <= staleMs`); a watchdog demotes `Active → Searching` after `staleMs` of position silence (default 5 s, ctor-injectable). So a live compass alone can no longer read as a healthy GPS — the nav goes honestly to "NO FIX" instead of confidently wrong. Tests: `active_fix_goes_stale_when_position_stops_arriving`, `compass_only_traffic_does_not_keep_a_dead_gps_alive` (real loopback UDP, short stale window). Gate green.
- **Still open (needs product/UI call):** a `LocationSourceState.Stale(lastFix, ageMs)` variant to grey the ego marker rather than drop it, + a fix-age readout in the ops footer. Demote-to-Searching is the safe minimal fix using existing UI handling.

## 2026-07-19 — Hardening pass from the review backlog (input validation, locale, cancellation)

Knocked out the cheap/clear-win items the Fable review surfaced, both languages, all gated green:
- **ThreatProtocol input validation (Kotlin `ThreatProtocol.kt` + Python `threat_protocol.py`):** reject NaN/Infinity az/size (they parse as floats but poison HUD Canvas math), drop contacts outside the ±90° forward arc, clamp size to 0..1, cap at 32 contacts (sender caps too, collisions+nearest first, so a crowded scene can't build an IP-fragmenting frame). This is the untrusted network boundary.
- **`NmeaParser`:** `parseVehicleTelemetry` + GGA HDOP now have the `isFinite()` guard the other parsers already had; GGA rejects negative fix-quality (`> 0`, was `!= 0`).
- **`beacon/Nmea.kt`:** `String.format(Locale.US, …)` for HDT/ZTLM (a comma-decimal fleet phone would have emitted `$GPHDT,12,3,T` and split the field, corrupting heading — checksum still valid, so it'd fail silently). `Locale.ROOT` on the hex checksum.
- **`DiscoveryRepository.refresh`:** re-throws `CancellationException` instead of swallowing it in the generic catch.
- **zvision `CollisionEstimator`:** out-of-order/duplicate frame no longer poisons the baseline before the `dt<=0` guard; az delta wraps at ±180; `MotionDetector` prunes vanished tracks (was unbounded over an all-night run).
- **Tests:** +8 Python (38 total), + new Kotlin cases (ThreatProtocol validation, NmeaParser HDG/HDM/VTG + non-finite/negative rejects), and **the beacon module's first unit test** (`beacon/src/test/.../NmeaTest.kt` — locale regression). Full gate green (ktlint/detekt/lint/test/assemble).

**Still open from the review (bigger, need design/product calls):** GPS staleness watchdog + `GpsFix.receivedAtMs` + UI age indicator; `demoEnabled=false` production wiring + DEMO watermark; source-id/seq + sender latch for multi-producer; ops-SSID/HMAC security; `CockpitViewModel` delegate split; DataStore/discovery IO off the Main scope; `DiscoveryRepository`/`parseHeadingDeg`-adjacent test coverage. Agents resumable (see prior entry).

## 2026-07-19 — Fable architecture review + P0 safety fix (demo never overrides a live all-clear)

Spawned three parallel Fable (high-reasoning) sub-agents to review the whole system read-only: (1) architecture/design, (2) networking resilience + security, (3) test-coverage gaps. All three **independently** converged on the same #1 issue, so it's real:

**P0 (FIXED): the demo threat feed painted fabricated collision alarms over a real "all clear."** `RoutedThreatSource` did `combine(network.threats, fake.threats){ net, demo -> net.ifEmpty { demo } }` — but an empty list means BOTH "no feed" and "the edge box genuinely sees nothing." A bare `ZTHREAT` frame (the legitimate all-clear the Jetson sends every quiet moment, incl. on exit) parses to empty → the HUD swapped to `FakeThreatSource.demo()`, whose approacher loops into `collision=true` ~45% of the time. Net: a night driver would see phantom red "! BRAKE !" locks whenever the road was actually clear — the safety display lying, cry-wolf. **Fix:** added `feedAlive: StateFlow<Boolean>` to `ThreatSource`; `NetworkThreatSource` sets it true on any frame (incl. empty all-clear), false when the watchdog goes stale (also fixed the publish/timestamp write-order race). `RoutedThreatSource` now falls back to demo only when `!feedAlive`, gated by a new `demoEnabled` flag (default true = bench demo preserved; set false for a deployed vehicle → absent feed reads as all-clear, never fabricated). New pinning test `live_all_clear_does_not_resurrect_the_demo` + `production_mode_shows_all_clear_when_the_feed_dies`. Full gate green.
- **TODO (product decision):** wire `demoEnabled=false` for the production/vehicle build, and add a DEMO watermark on `DriverNightScreen` when contacts are synthetic.

**Backlog surfaced by the review (prioritized, not yet done):**
- **GPS staleness (HIGH):** `NetworkLocationSource` never demotes `Active` — a dead beacon leaves a frozen fix guiding forever, worse because HDT/ZTLM keep the display "alive" on a stale position. Add a watchdog + `receivedAtMs` on `GpsFix` + UI age indicator.
- **Locale bug (HIGH, cheap):** `beacon/Nmea.kt` uses default-locale `String.format` — a comma-decimal fleet phone emits `$GPHDT,12,3,T` (checksum still valid) → tablet misparses. Use `Locale.US`/`ROOT`.
- **Input validation (MED-HIGH):** `ThreatProtocol.parse` (both Kotlin + Python) accepts `NaN`/`Infinity` → NaN into HUD Canvas; no az/size clamp, no contact cap, no checksum. `NmeaParser.parseVehicleTelemetry` + GGA HDOP miss the `isFinite()` guard the other parsers have. GGA accepts negative fix-quality.
- **zvision estimator (MED, before real camera):** `CollisionEstimator` is flicker-prone (per-frame, strict-increase), `forget()` never called (unbounded `_tracks` on the all-night box), overwrites baseline before the `dt<=0` guard, no az wrap. Needs EMA + M-of-N latch + track coasting + pruning.
- **Security (proportionate):** anyone on the WiFi can inject spoofed threats/telemetry. Right answer for an art car = router hygiene (ops SSID w/ non-shared PSK + guest isolation) + a vehicle-id/seq field on frames + optional truncated HMAC on ZTHREAT only. NOT DTLS/certs.
- **Multi-producer (MED):** no source-id/seq — two beacons or two zvisions interleave/flip; MTU truncation silently drops contacts above ~70; add a sender-side top-N cap.
- **Structure:** `CockpitViewModel` god-object (split into MapCamera/Navigation/DriveTarget delegates before more features land); `DiscoveryRepository` swallows `CancellationException`; DataStore + discovery cache IO runs on the `Main.immediate` app scope; no `CoroutineExceptionHandler` on the app scope.
- **Test gaps:** `DiscoveryRepository` (zero tests), `NmeaParser.parseHeadingDeg` (HDG/HDM/VTG untested), `CockpitViewModel` drive-to/route pipeline, `activeDriveTarget`/BATH, several `routeTo` guard branches, zvision estimator/broadcaster edges.
- Full agent reports captured this session; agents resumable (arch a3474ebad1de3d325, net a719047f2272730ce, tests a04223912c9b7c703).

Also: confirmed the phone Beacon's `TelemetryService` runs (foreground). My Mac couldn't sniff its broadcast (unicast ping to the phone works; broadcast frames don't arrive — AP filters the phone's broadcast and/or macOS inbound filtering), but Mac→tablet broadcast is proven on this AP, so the real consumer path is fine. Phone location services are on (mode 3).

## 2026-07-19 — `zvision` → real S9+ HUD, proven end-to-end (+ `--bind-ip`)

Closed the loop on real hardware: ran the actual `zvision` broadcaster on the Mac emitting a **distinctive** 2-contact frame (far-left drifter + a collision on the *right*, not center) and the S9+ DRIVER HUD **overrode the fake demo with exactly that pattern** — left green figure, empty center, red collision-locked figure bracketed on the right. That's `zvision` code → subnet broadcast → tablet `NetworkThreatSource` → `RoutedThreatSource` override → `DriverNightScreen`, on the real dashboard, camera being the only missing hop.

- **Added `--bind-ip` / `ThreatBroadcaster(bind_ip=…)`** (+ `SO_REUSEADDR`): binds the sender's source address so broadcast egresses the intended NIC on a multi-homed host. Needed here because tailscale hijacks the `192.168.0.0/24` route on the Mac (sends went out the VPN → `EHOSTUNREACH`); binding to the WiFi IP `192.168.0.145` fixes it. The Jetson will want the same (wired + debug link). Test `test_bind_ip_pins_the_source_address` (loopback source-addr assertion). 30 tests green.
- **Confirmed the multicast-vs-broadcast split in practice:** the multicast leg still fails from the Mac (tailscale) → `send()` reports "1 target"; the subnet-broadcast leg carries it. This is exactly why the belt-and-suspenders dual send exists. On the vehicle net (no VPN) both legs will fire.
- Tablet was PIN-locked/dozing at start — `svc power stayon true` + `KEYCODE_WAKEUP` + `wm dismiss-keyguard` to hold it awake for the test; concept was on MAP, cycled the top-right pill to DRIVER; slept it after (`stayon false` + `KEYCODE_SLEEP`).

## 2026-07-19 — Jetson edge-box software (`jetson/zvision`) — the ZTHREAT *producer*

Ordered a Jetson Orin Nano Super (from Arrow at $249 MSRP — Amazon was scalping it at $435). Ahead of the hardware landing, built the full edge-box software so it's plug-and-play on arrival: a new **Python module `jetson/zvision`** that is the producer side of the threat channel the tablets already consume. Turns camera frames → vehicle-relative `DriverThreat`s → **ZTHREAT** frames on the fleet bus (`239.7.7.20:10120` + subnet-broadcast fallback, TTL 1). The wire format (`zvision/threat_protocol.py`) is a **byte-exact mirror** of the Kotlin `core/vision/ThreatProtocol.kt` — two sides share no code, only the frozen protocol; the Python round-trip tests also guard the tablet's contract.

- **Two detector paths, by design.** `FakeDetector` is **standard-library only** (no cv2/numpy) — runs on a bare Jetson or my Mac and lets you prove the whole network→HUD path *before any camera exists*. `MotionDetector` (cv2 background-subtraction blobs + nearest-centroid tracker + constant-bearing collision rule) runs on a real UVC camera **with no trained model** for bring-up — plug in the Lepton (via PureThermal) or a USB webcam and moving bodies show up on the HUD. The trained thermal/RGB model drops in behind the same `detect()` signature later.
- **Dual-camera from day one.** Runner takes `--source thermal|rgb` and an optional second device — because at midday the playa ground hits 120 °F+ and *warm bodies lose thermal contrast*; RGB is the daytime primary, thermal the night primary. Capture is trivial (UVC/V4L2); fusion is later work.
- **Deployment:** `systemd/zvision.service` (config via `/etc/default/zvision`, auto-restart) + `scripts/install.sh` (provision to `/opt/zodiac/jetson`, enable) + full **`jetson/DEPLOY.md`** (flash JetPack → MAXN-Super power mode → wired/M.2-WiFi/dongle network → prove-with-fake → attach camera → make permanent, with a troubleshooting table). Each stage verifiable alone: `--source fake` proves the bus/HUD so any later break is isolated to the camera/model.
- **Verified locally:** `python3 -m zvision --once -v` emits `ZTHREAT;1:0.0:0.250:0;...`; a 5 Hz loopback run received the frames and the shutdown **all-clear** `ZTHREAT` frame. 29 stdlib `unittest` tests (protocol bytes/round-trip, geometry + collision rule, fake detector determinism, loopback send). New **`jetson-ci.yml`** GitHub Actions job runs them (Gradle CI untouched — `jetson/` is not a Gradle module).
- **Jetson facts (for the record):** GbE onboard, **no onboard WiFi** — add an Intel AX210 M.2 E-key card (~$20) or a USB dongle, or (recommended for a fixed roof install) hardwire Ethernet to the travel router. If wired-Jetson↔wireless-tablets, the router may drop multicast at the bridge (IGMP snooping) — the subnet-broadcast leg covers it.

## 2026-07-18 — Sensor Hub v2: broadcast IMU tilt + speed telemetry (ZTLM)

The Beacon now broadcasts more than position + heading: a proprietary **`$ZTLM,pitch,roll,speedKph*cs`** sentence carrying the IMU **tilt** (pitch/roll from the rotation vector) + **ground speed**, sent alongside HDT at 4 Hz on the telemetry channel. Tablet side: `core/telemetry/VehicleTelemetry` model + `NmeaParser.parseVehicleTelemetry` (reuses the checksum validation), and `NetworkLocationSource` now exposes a `telemetry: StateFlow<VehicleTelemetry?>` (parses ZTLM in the same ingest loop, separately from the GPS fix). So the full sensor-hub telemetry is on the bus and consumable.

- **Tests:** `NmeaParserTest` (parse ZTLM; reject non-TLM / bad checksum; HDT heading) + `NetworkLocationSourceTest` (ZTLM over real loopback UDP → the telemetry flow). Gate green.
- **Remaining (C):** wire `telemetry` into `CockpitUiState` + a readout — deferred since nothing renders tilt/speed yet (the beacon broadcasts it and the tablet parses it; the last hop to a UI is a small follow-up when there's a place to show it).
## 2026-07-18 — Fleet bus → fixed multicast group (broadcast-independent discovery)

Migrated the one-to-many streams from UDP broadcast to a **fixed multicast group** so there are no hardcoded IPs and it survives dynamic DHCP (the group address is baked in). New `core/net/FleetBus` constants: telemetry `239.7.7.10:10110`, threats `239.7.7.20:10120`, TTL 1 (link-local). `NetworkLocationSource` + `NetworkThreatSource` now use a `MulticastSocket` that `joinGroup`s (still bound to the port, so they also receive unicast/broadcast — the loopback unit tests keep passing). The Beacon sends to the multicast **group + a /24 subnet-directed broadcast fallback** (belt-and-suspenders — the fleet gets telemetry whether or not the AP forwards multicast); each target sent independently so one failing can't block the other.

- **Verified live on the S9+:** Beacon → tablet still delivers end-to-end after the migration — tablet showed HOME 429.3 km (the phone's SF position) + HDG 12° (compass), NET selected. Also confirmed the migrated `MulticastSocket` still receives a subnet broadcast (from a laptop) — reception not broken.
- **Gotchas found + fixed:** (1) Beacon `send()` had an ordering bug where a failing first target aborted the rest → per-target `runCatching`. (2) `WifiManager.connectionInfo.ipAddress` returns 0 on Android 10 → compute the /24 broadcast from `dhcpInfo.ipAddress` instead. (3) laptop multicast sends are blocked by tailscale hijacking the `192.168.0.0/24` route (a Mac-side test-harness issue), so isolated multicast-only send from the laptop couldn't be tested — the phone (no tailscale) is the real multicast sender.
- **Tests:** `FleetBusTest` (groups are valid, distinct multicast addresses). Existing `NetworkLocationSourceTest` / `NetworkThreatSourceTest` still green (unicast-to-port loopback).
- **Still open (B):** mDNS/`NsdManager` service announcement was deferred (multicast groups already give discovery-free stream delivery; mDNS is for health/enumeration).

## 2026-07-18 — DRIVER HUD Phase 2: live threat sources (fake moving + network)

The DRIVER night HUD is now driven by live thermal contacts instead of static placeholders. New `ThreatSource` abstraction (mirrors `LocationSource`): **`FakeThreatSource`** (three moving demo contacts — a crosser, an approacher that ramps into the red-lock collision on a loop, a far drifter; per-tick geometry is a pure `demo(tick)` fn), **`NetworkThreatSource`** (UDP consumer on port 10120, MulticastLock, parses `ThreatProtocol` frames, watchdog clears to all-clear when the feed goes stale), and **`RoutedThreatSource`** (prefers the network feed, falls back to the fake demo when silent — so the HUD is always alive and upgrades to real detections automatically). Wired through `CockpitUiState.threats` → ViewModel (`threatsFlow`, same pattern as `poisFlow`) → `DriverNightScreen` (renders `state.threats`). `ThreatProtocol` = compact `ZTHREAT;id:az:size:col;...` wire format (the Jetson emits, tablets parse).

- **Verified live on the S9+:** DRIVER HUD shows moving contacts (22k px change between frames), collision escalates dynamically. Then broadcasting synthetic `ZTHREAT` frames from a laptop (stand-in Jetson) → the HUD switched to those exact contacts, proving the network-override / "edge box → display" path on real hardware.
- **Tests:** `ThreatProtocolTest` (round-trip, garbage→null, empty frame→all-clear, skips malformed), `FakeThreatSourceTest` (3 contacts, motion, collision cycle, size ramp), `NetworkThreatSourceTest` (real loopback UDP → threats; stale→cleared), `RoutedThreatSourceTest` (network-preferred / fake-fallback). detekt constructorThreshold 8→9 for `threatsFlow`.
- Test tool: `/tmp/threat_send.py` broadcasts `ZTHREAT` frames. Threat port = **10120** (NMEA stays 10110).

## 2026-07-18 — Package rename: `ai.openclaw.*` → `org.pureagave.zodiac.*`

Scrubbed the legacy `ai.openclaw` namespace (a leftover from the original Feb-2026 scaffold — the project isn't "openclaw") and moved to the owner's domain. App: `ai.openclaw.zodiaccontrol` → **`org.pureagave.zodiac.control`**; beacon: `ai.openclaw.zodiacbeacon` → **`org.pureagave.zodiac.beacon`** (shared `org.pureagave.zodiac` root). Mechanical: 142 files rewritten (package decls, imports, `namespace`/`applicationId`, manifests, docs incl. this log), source trees moved `.../ai/openclaw/...` → `.../org/pureagave/zodiac/...`, `ktlintFormat` re-sorted imports for the new prefix. Gate green. **applicationId changed → the app installs as a new package** (old `ai.openclaw.*` installs should be uninstalled from the fleet). Two non-app "openclaw" strings intentionally left: `.gitignore`'s `.openclaw/` (agent working-dir ignore) and an avatar path in `IDENTITY.md` — tooling scaffolding, not the project.

---

## 2026-07-18 — Zodiac Beacon (new `:beacon` module) — telemetry hub, verified end-to-end

Built the **Vehicle Sensor Hub** as a standalone app: a new Gradle module `:beacon` (app "Zodiac Beacon", `org.pureagave.zodiac.beacon`, plain Views — no Compose, same detekt/ktlint gates). It reads the phone's GNSS (raw NMEA via `LocationManager.addNmeaListener`) + **magnetometer** (`TYPE_ROTATION_VECTOR` → azimuth, corrected to true north via `GeomagneticField` declination) and **broadcasts** them over UDP to the subnet broadcast (computed from DHCP; falls back to `255.255.255.255`) on port 10110, from a **foreground service** so it keeps running screen-off. GNSS forwarded verbatim; a true-heading **`HDT`** synthesized from the compass at 4 Hz (so heading is live even stopped).

- **minSdk 29** (the XCover Pro is Android 10 — the app's minSdk 30 blocks install; the beacon runs on the *phone*).
- **Tablet side:** `NmeaParser.parseHeadingDeg` (HDT/HDG/HDM/VTG) + `NetworkLocationSource` now does a **stateful merge** — holds last position (GGA/RMC) + last compass heading (HDT), emits a fix preferring the compass over GPS course (compass is valid when stopped; course is noise). Unit test drives real loopback UDP: GGA + HDT → fix carries the HDT heading.
- **Verified live on the S9+:** Zodiac Beacon on the XCover broadcast its real GNSS + compass; the tablet showed position (SF, HOME 429.4 km) and **BEARING 012° = the compass heading while SPEED 000** — proving it's the magnetometer (HDT), not GPS course. First real telemetry-hub proof.
- Replaces the bring-up GPSd Forwarder with our own controllable app. Next: broadcast IMU + richer telemetry; migrate broadcast → multicast group.

---

## 2026-07-18 — ARCHITECTURE.md + fleet-bus / sensor-hub / Jetson decisions

Wrote [`ARCHITECTURE.md`](ARCHITECTURE.md) — the living system-design reference (hardware devices & roles, the fleet network bus, sensors/detection, night-vision design, playa constraints, BOM, roadmap, decisions log). Baked in the decisions from today's design pass:

- **Fleet bus = fixed multicast groups + mDNS.** Single subnet, dynamic DHCP → no hardcoded IPs. One-to-many streams (telemetry, threats, destination) ride fixed multicast groups (address baked in, DHCP-proof — a source announces itself by transmitting); mDNS/`NsdManager` for service discovery + health. All link-local → immune to Starlink outages (only the BM API cache / weather use the WAN, offline-first). `NetworkLocationSource` currently uses UDP broadcast + MulticastLock; migrating to multicast groups is the next step.
- **GPS device → "Vehicle Sensor Hub": broadcast everything.** Not just position — GPS speed/course, **magnetometer compass heading** (distinct from GPS course, which is noise when stopped/crawling — matters for a slow art car), IMU, altitude, fix quality, time. Bring-up = GPSd Forwarder (NMEA only, proven today); production = custom broadcaster or the Pi + IMU.
- **FLIR compute = Jetson Orin Nano Super** (~$249, 67 TOPS; scarce right now). Compute isn't the bottleneck — thermal *resolution* is. **ML plan given no playa data + an H100:** v1 classical CV (zero training data — blob/track/velocity/collision); v2 fine-tune a pretrained YOLO on *public* thermal datasets (Teledyne FLIR Free ADAS, LLVIP, KAIST) — no playa collection, night playa is an easier domain. Realistic: person detect ~20–30 m, count *separated* people, bike-vs-walker mostly from velocity. FLIR lives on the edge box (front), broadcasts *detections* (metadata) not raw video.
- **DMX gimbal tracker light** (user owns the moving-head): slave it to detections — Jetson bearing→pan/tilt→USB-DMX (OpenDMX ~$25 / Enttec ~$140, OLA). Steerable "we-see-you" spotlight = safety + art.

See [[project_night_driver_and_sensors]] for the running roadmap memory.

---

## 2026-07-18 — NetworkLocationSource (NET): shared-WiFi GPS over UDP — verified end-to-end

Built the production fleet GPS path: `data/sensor/NetworkLocationSource` listens for NMEA datagrams on UDP **10110**, splits each into lines, feeds the existing `NmeaParser`, and emits `LocationSourceState` like every other source. Added `LocationSourceType.NET` (the GPS chips auto-populate from `entries`, so a **NET** chip appeared with no UI change), registered it in `ZodiacApplication`, and wired the manifest. Prefs already deserialize the enum by name with a fallback, so NET persists safely.

- **Verified live on the S9+** with the XCover on the same WiFi: broadcast synthetic NMEA (a track heading north) from the Mac → the tablet's NO FIX cleared, the drive-to distance/BRC clock came alive, and the HEADING needle swung to north (matching the RMC course). This is the first real proof of the shared-WiFi GPS architecture.
- **Two hard-won networking lessons (both now baked into the code):**
  1. **Android silently filters broadcast/multicast UDP** from apps to save power — the app must hold a `WifiManager.MulticastLock` or it receives nothing. Added it (acquire on start / release on stop; needs `CHANGE_WIFI_MULTICAST_STATE`). This was the whole reason "NO FIX" persisted at first. `NetworkLocationSource` now takes an optional `Context` for the lock (null in unit tests).
  2. **Deliver via subnet broadcast (`x.x.x.255`), not unicast.** From the Mac, unicast to the tablet failed with `EHOSTUNREACH` (stale ARP / tailscale grabbing the `192.168.0.0/24` route); broadcast needs no ARP and is the right fleet design anyway (one GPS → all tablets). Client-isolation is OFF on this AP (ping worked), so broadcast reaches every tablet.
- **`also{}` not `apply{}` gotcha:** inside `apply` the receiver's `DatagramSocket.port` (−1 when unconnected) shadowed the constructor `port`, so it tried to bind :−1. Unit test caught it.
- **Tests:** `NetworkLocationSourceTest` drives real loopback UDP — a valid GGA → `Active` fix (lat/lon asserted), junk → stays `Searching`. Re-sends across a window to beat the listener's bind race.
- **Test tooling:** `/tmp/nmea_send.py` broadcasts checksummed GGA+RMC walking north from the Golden Spike — reuse it to exercise NET. Next: point the XCover's forwarder app (Android-GPSd-Forwarder or Share GPS) at `x.x.x.255:10110` to replace the Mac with the real phone GPS. See [[project_night_driver_and_sensors]].

---

## 2026-07-17 — Fix: route "bird" chord across the city when navigating back in

Reported: the blue route line back to the city drew "like we are a bird" — a straight diagonal across the built blocks instead of following streets. Root cause in `PlayaRoute.cityWaypoints`: when the ego was **outside the Esplanade** it did `waypoints += corner` — a straight jump to the destination's entrance corner, which for a far-side ego chords clear across town. (The earlier real-streets fix only covered ego-at-the-Man; the "navigate back from the open playa" case was untested.)

- **Fix:** when the ego is already out in the grid / beyond it, come onto the destination **ring at OUR OWN bearing** (a radial move inward), then follow the ring around to the address — never a chord across the built city. Inside-the-Esplanade case unchanged (cross the open centre to the destination radial, out to the ring). Generalised the arc leg to run from the entry bearing (ours when outside, the destination radial's when inside) to the address.
- **Test:** `PlayaRouterTest` — widened the synthetic city's arcs to the full 2:00→10:00 span (105°–345°) so a far-side ego can be exercised, and added `from_outside_the_city_route_comes_in_on_our_bearing_not_a_chord_across_town` (ego at 8:00 beyond the rings, dest H&2:30 on the far side): asserts the first waypoint is on the ring at ~our bearing (not the far entrance), it still reaches the dest, and no waypoint dives past the Man. Fails on the old code, passes on the fix.
- **Verified live on the S9+** (before/after, identical setup — HOME target, ego nudged ~2 km SW): before = straight diagonal chord across the blocks; after = blue line curves along the ring.

---

## 2026-07-16 — DRIVER HUD: contact figure shape decided (distance LOD)

Resolved the open figure-shape question (user picked from a 10-shape menu): the contact figure is now **distance level-of-detail** — distant/small contacts draw a compact **head+shoulders "bust"** (stays legible at a few pixels), and once close they switch to a full striding **"walking" figure** that reads unmistakably as a person. Threshold `NEAR_SHAPE_THRESHOLD = 0.5` on the contact's size. `figureBust` / `figureWalking` replace the old head+trapezoid `figure`. Verified live on the S9+.

---

## 2026-07-16 — DRIVER night HUD, Phase 1 (Star Wars '83 vector display)

New third cockpit concept **`DRIVER`** (`CockpitConcept.DRIVER`, cycles RADAR→MAP→DRIVER) — a dim, hollow-vector night display for the person driving in the dark, styled after the 1983 Atari *Star Wars* vector cabinet. Intended eventual home is the small OLED phone (A54), but built as a selectable concept so it runs/tests on the S9 now. See [[project_night_driver_and_sensors]].

- **Design (iterated with the user over mockups):** pure-black field; **hollow wireframe** thermal-contact figures (filled silhouettes emit too much light for night vision); palette restricted to **dim green + red + purple, no white/no yellow**; a perspective ground grid for the depth cue (nearer contact = lower + bigger). Heading **arch** across the top (the '83 "shield bar") with a purple marker that rotates to the drive-to bearing; **destination NAME at top-centre** (HOME/MAN/TEMPLE), **entrance clock/address boxed on the arch** (on-playa) — split apart per user feedback. Collision escalation: figure bright red, **four lock brackets in deep red** (`0xFF9E1224`, lower luminance than the alarm red), "! COLLISION COURSE !" / "! BRAKE !". On-playa vs in-city context from `entranceRadial`.
- **Files:** `ui/concepts/DriverNightScreen.kt` (Compose `Canvas` + `nativeCanvas` text in Orbitron; nav data live from `CockpitUiState` — heading/speed/`activeDriveTarget`/`entranceRadial`; threats are **placeholder** `DriverThreat`s until the FLIR feed exists), `ui/concepts/DriverThreat.kt`, dispatch in `CockpitScreen`, enum in `CockpitConcept`.
- **detekt:** `TooManyFunctions.thresholdInFiles` 12→18 (the HUD is a stack of small `DrawScope` helpers). Updated `CockpitConceptTest` + `CockpitViewModelTest` for the 3-concept cycle.
- **Verified live on the S9+.** Testing gotcha logged: after a prior `KEYCODE_SLEEP` the display was OFF (`PROCESS_STATE_TOP_SLEEPING`), so every `screencap` was pure black and injected `input tap`s don't wake a sleeping screen — must `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` (+ `svc power stayon true`) first.
- **Next:** Phase 2 = `DriverThreat` data model + a fake *moving* threat source (mirrors `FakeLocationSource`); Phase 3 = wire real on-playa/in-city context + entrance selection; later = real FLIR feed + network broadcast. Figure *shape* is still open (user reviewing alternatives to the current head+trapezoid).

---

## 2026-07-11 — City routing follows real street polylines (not idealised polar)

Feedback: an entered address (e.g. 5:45 & C) "isn't on a road when it hits the city — routes across camp sites." Root cause in `core/navigation/PlayaRoute.routeTo`: it built the in-city legs from **idealised polar geometry** — `polarPoint(esplanadeR, entBearing)`, `polarPoint(ringR, entBearing)`, and a coarsely-sampled arc chord — on *perfect circles* at mean radii. That sits near but not on the real GIS streets, so the drawn line drifted across blocks. Compounded by two radius sources (address uses `StreetRingRadiiM`, route used `arcRadiiM`) and off-radial addresses (5:45 has no radial → enters at 5:30/6:00, then a chord).

- **Fix:** every in-city corner is now snapped to the **nearest vertex of the actual `city.streetsM` polylines**. Pick the ring arc (nearest `arcRadiiM` by radius) and the entrance radial (nearest by bearing), gather all their GIS segments, then walk: radial's real vertices from the Esplanade out to the ring corner → the ring arc's real vertices from the corner along to the vertex nearest the address. No `polarPoint` generation left; the route lies on the drawn streets by construction.
- **Free-drive unchanged:** open playa (inside Esplanade, 10–2 mouth, past the outer road) still returns a single straight leg. Verified TEMPLE → straight.
- **Known limit:** the ego-already-out-in-the-grid case is still approximate (jumps to the ring corner, then arcs) — fine for driving *out along* a route (ego→corner runs along the radial you're already on), but a cross-town trip from a deep-city ego can still cut across. Left as-is; the reported case (drive-to-address from the Man) is handled.
- **Test:** `PlayaRouterTest` rewritten with *dense* synthetic radials/arcs (the old inner/outer-pair streets can't exercise vertex-snapping). New `in_city_route_stays_on_the_radial_then_the_ring` asserts every waypoint is on the entrance radial (±1°) or on the ring (±20 m) — the rigorous proof of "on streets."
- **Detekt:** `routeTo` tipped over CyclomaticComplexity 15 → extracted the polyline walk into `cityWaypoints(...)`.
- **On-device (S9+):** verified 5:45 & C, HOME (2:15), TEMPLE render sensibly. Visual caveat: with the fake ego parked at the Man, a radial route and a straight line nearly coincide (ego at centre), and adb can't pinch-zoom on the non-rooted device (`sendevent` = permission denied, `input` has no pinch) — so the pixel-on-street proof is the unit test, not the screenshot.

---

## 2026-07-11 — Address heading flash: stop the big degrees wrapping

`ui/ops/AddressEntryPanel` flash showed `HDG 345°` at 104 sp; wide digit values overran the 620 dp panel and wrapped to two lines (narrow ones like `112°` fit, so it was intermittent). Now the big line is just the number (≤4 glyphs) with a small "HEADING" label above, plus `maxLines=1`/`softWrap=false`. Verified on the S9+ with 10:00 (345°) — single line.

---

## 2026-07-11 — Portrait mode (responsive layout for small tablets)

The app was landscape-locked; now it reflows for portrait so it can run on small portrait-mounted screens (fleet has mixed orientations — S9 landscape, small tablets portrait).

- **Orientation.** Manifest `screenOrientation` `landscape` → **`fullUser`** (each device shows its *locked* mount orientation without flipping mid-drive), plus `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"` so rotating reflows instantly instead of recreating the Activity.
- **Responsive branch.** Each concept detects `portrait = LocalConfiguration.screenHeightDp > screenWidthDp` and branches the middle content (landscape layouts unchanged):
  - **RADAR** — landscape is `left stats | centre scope | right stats+controls`; portrait stacks **scope (square, full width) → BEARING/SPEED + RANGE/ZOOM row → scrollable controls**. Extracted `radarScope(...)` so both branches share it.
  - **MAP** — landscape is `left gauges | centre map | right throttle/cells/controls`; portrait stacks **map tile → HEADING/SPEED/CELLS row → controls** (drops the decorative throttle trace). Extracted `bayMapTile/headingTile/speedTile/cellsTile` helpers.
- **Control strip** is `verticalScroll`-able (added in the tilt cleanup) so it fits either shape.
- **Verified on the S9+ forced to portrait** (via a temporary manifest `portrait` for the shot — `fullUser` + adb `user_rotation` didn't take, likely DeX/freeform ignoring it): both concepts render cleanly stacked — RADAR's big circular scope up top with the route line, MAP's ground-track up top. Reverted the manifest to `fullUser`, restored the device to landscape.
- First pass — functional on the S9; polish the spacing/sizing on the actual small tablets when they're in hand.

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

**Deploy bring-up.** Got the debug build running on the physical tablet (Amazon Fire HD 10, codename "tungsten" / model `KFTUWI`, Android 11 / Fire OS 8.0, **API 30**, arm64-v8a — the real perf-target hardware). `./gradlew installDebug` + `adb shell am start -W -n org.pureagave.zodiac.control/.MainActivity`; screencap to verify. Toolchain isn't on PATH (JDK 17 at the Homebrew Cellar path, SDK at `/usr/local/share/android-commandlinetools`), and there's no `local.properties` — set `JAVA_HOME`/`ANDROID_HOME` inline per command.

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
- `wrapHeading(deg)` would have pushed `CRTVectorScreen.kt` to 12 functions (over the 11-per-file ceiling). Lifted to a new tiny file `app/src/main/java/org/pureagave/zodiac/control/ui/Heading.kt`.

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
alias zodiac-install='./gradlew :app:installDebug && adb shell am start -n org.pureagave.zodiac.control/.MainActivity'
```

Verified end-to-end: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, 4/4 unit tests pass; APK installs, `MainActivity` launches and renders the CRT cockpit on the AVD.

Open code-review observations from the install pass (not blockers, log for later):
- Repo root has identity/bootstrap files (`BOOTSTRAP.md`, `SOUL.md`, `IDENTITY.md`, `HEARTBEAT.md`, `TOOLS.md`, `USER.md`, `AGENTS.md`) that look unrelated to the Android project — consider gitignore or move out.
- `ExampleInstrumentedTest.kt` exists but no instrumented-test job in CI — run it on a managed device or delete it.
- Dependency versions are inlined in `app/build.gradle.kts`. `gradle/` exists but has no `libs.versions.toml` yet — worth adopting before the module count grows.
- `FakeVehicleGateway.kt` + `VehicleGateway.kt` may be dead code post-transport-abstraction refactor; verify before next cleanup.
