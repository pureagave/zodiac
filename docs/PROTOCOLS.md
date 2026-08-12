# Wire protocols

The three parts of Zodiac share no code. They agree through two wire formats on
the fleet bus. This is the byte-level reference for both.

> Architecture context: [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §3 (the bus)
> and §7 (the seams). The ZTHREAT contract additionally has its own
> owner-neutral note at [`../protocol/README.md`](../protocol/README.md).

---

## 1. The fleet bus

| | Group | Port | Sender | Receivers |
|---|---|---|---|---|
| Telemetry (NMEA) | `239.7.7.10` | `10110` | `:beacon` | every `:app` tablet; `zvision`'s `$ZAUD` listener |
| Threats (ZTHREAT) | `239.7.7.20` | `10120` | `zvision` | every `:app` tablet |
| Shared nav target (ZNAV) | `239.7.7.30` | `10130` | a nav-authority `:app` tablet | every `:app` tablet |

Multicast TTL is **1** — nothing leaves the vehicle's subnet. `239.0.0.0/8` is
administratively scoped.

**Every sender transmits each datagram twice**: once to the multicast group and
once to the /24 subnet-directed broadcast (falling back to `255.255.255.255` if
the local IP cannot be determined). Some access points silently drop multicast,
and a fleet that goes blind because of an AP setting is not acceptable.

**Every receiver binds the wildcard address** on the port *and* joins the group,
so it gets whichever copy arrives. The consequence is duplicate delivery — which
is why the tablet de-duplicates byte-identical `$ZSHK` lines within 200 ms.

Constants are declared in three mirrored places:

| Where | File |
|---|---|
| `:app` | `app/src/main/java/org/pureagave/zodiac/control/core/net/FleetBus.kt` |
| `:beacon` | `beacon/src/main/java/org/pureagave/zodiac/beacon/TelemetryBroadcaster.kt` |
| `zvision` | `jetson/zvision/fleet_bus.py` |

`FleetBus.TTL` is used on the app side by `NavShareSender` (`$ZNAV` is the
app's first device-to-device transmit path — every prior `:app` role was a
receiver). Receivers never set TTL; it is purely a sender concern.

---

## 2. NMEA telemetry — `:beacon` → `:app`

### 2.1 Framing

Every synthesized sentence has exactly this shape:

```
$<BODY>*<CC>\r\n
```

- `$` start delimiter
- `<BODY>` — the comma-separated sentence, starting with the 5-character sentence
  identifier
- `*` checksum delimiter
- `<CC>` — **two uppercase hex digits**, the XOR of every character of `<BODY>`
  (i.e. strictly between `$` and `*`)
- terminator `\r\n` (0x0D 0x0A)

Encoded US-ASCII on the wire. **All numeric formatting is pinned to
`Locale.US`** — on a comma-decimal device an unpinned format would emit `12,3`
and split the field, taking the whole fleet's telemetry down.

The tablet's parser (`NmeaParser`) accepts a **one or two** digit checksum, a
deliberate tolerance for third-party receivers that emit a single digit below
`0x10`. A sentence with no `*` fails. Dispatch is on the **last three
characters** of the identifier, so `$GP` / `$GN` / `$GL` / `$GA` / `$GB` talkers
all match the same handler.

### 2.2 Raw GNSS passthrough

The phone's own GNSS NMEA is forwarded **verbatim** — no filtering by talker or
type, no re-checksumming. The only modification is appending `\r\n` when the OS
hands over a message with no trailing newline.

The broadcaster also *sniffs* passing GGA sentences for fix quality and satellite
count to populate `$ZBCN`, but does not alter them.

### 2.3 Sentences the tablet consumes

| Sentence | Source | Consumed as |
|---|---|---|
| `GGA` | raw GNSS | position, fix quality, HDOP → accuracy estimate |
| `RMC` | raw GNSS | position, speed, course |
| `GPHDT` | synthesized | true heading (preferred over course) |
| `ZTLM` | synthesized | pitch, roll, speed |
| `ZAUD` `ZENV` `ZSHK` `ZBCN` `ZODO` | synthesized | the five sensor channels |

**`VTG`, `HDG` and `HDM` are deliberately rejected.** VTG carries GPS *course*,
not compass heading; Android chips emit it every epoch and the beacon forwards
raw NMEA verbatim, so treating it as heading would interleave with `$GPHDT` in a
last-writer-wins race and flip-flop a stopped vehicle's heading — and it would
keep the heading-freshness clock alive, defeating the dead-compass watchdog. RMC's
own course field is the correct course source. `HDG`/`HDM` are *magnetic*
heading; magnetic variation at Black Rock City is roughly 13° east, so applying
them as true heading would be silently wrong by that much.

`GLL`, `GSA` and `GSV` are simply not parsed.

### 2.4 Standard sentence field use

Only the fields the tablet actually reads are listed.

**`GGA`** — minimum 10 fields.

| Field | Meaning |
|---|---|
| 2, 3 | latitude `DDMM.MMMM`, hemisphere `N`/`S` |
| 4, 5 | longitude `DDDMM.MMMM`, hemisphere `E`/`W` |
| 6 | fix quality — must be `> 0` or the sentence is discarded |
| 8 | HDOP → accuracy estimate as `hdop × 5.0` metres |

**`RMC`** — minimum 9 fields.

| Field | Meaning |
|---|---|
| 2 | status — must be `A` (valid) |
| 3–6 | latitude / hemisphere / longitude / hemisphere |
| 7 | speed over ground in **knots** → × 1.852 for kph |
| 8 | course over ground, degrees true, normalised to [0, 360) |

Coordinates are `DDMM.MMMM`: degrees = integer part / 100, minutes = remainder.
Minutes outside `[0, 60)` are rejected, latitude is range-checked to ±90 and
longitude to ±180, and the hemisphere letter must be one of the four valid
characters. A negative or non-finite speed yields null rather than zero.

### 2.5 `$GPHDT` — true compass heading

```
$GPHDT,<heading>,T*CC
```

| Field | Type | Range / format |
|---|---|---|
| 1 | heading, degrees true | `%.1f`, normalised to **[0, 360)** |
| 2 | literal `T` | fixed — true, not magnetic |

Example: `$GPHDT,123.4,T*31`

The only builder that normalises: `365.0` emits `5.0`, `-90.0` emits `270.0`.

Emitted every tick (~4 Hz) **only when a heading is available** — a device with
no rotation-vector sensor emits nothing on this channel rather than a fabricated
`0.0`.

> **Caveat worth knowing.** Heading is derived from the rotation vector and
> corrected to true north with `GeomagneticField`, which needs a position. Until
> the first GPS fix arrives, `$GPHDT` carries *magnetic* heading labelled `T`.

### 2.6 `$ZTLM` — IMU tilt and speed

```
$ZTLM,<pitch>,<roll>,<speedKph>*CC
```

| Field | Type | Units | Format |
|---|---|---|---|
| 1 | pitch | degrees, −90…90 | `%.1f` |
| 2 | roll | degrees, −180…180 | `%.1f` |
| 3 | ground speed | **kph** | `%.1f` |

Example: `$ZTLM,-2.5,1.0,8.4*2A`

No clamping or normalisation is applied by the builder — the ranges come from the
sensor. Speed is the GNSS fix's speed × 3.6, and is `0.0` when there is no fix.

Emitted every tick (~4 Hz) **unconditionally**, so a device with no
rotation-vector sensor broadcasts `0.0` pitch and roll. (Unlike `$GPHDT` and
`$ZENV`, this channel has no absent-sensor suppression.)

### 2.7 `$ZAUD` — microphone level

```
$ZAUD,<rms>,<peak>,<beat>*CC
```

| Field | Type | Range | Format |
|---|---|---|---|
| 1 | rms level | 0.0–1.0 | `%.3f` |
| 2 | peak level | 0.0–1.0 | `%.3f` |
| 3 | beat flag | `0` or `1` | integer |

Examples: `$ZAUD,0.125,0.800,1*19` · silence `$ZAUD,0.000,0.000,0*16`

Emitted once per captured audio frame — 1024 samples at 16 kHz ≈ **15.6 Hz**.
Levels are normalised against 16-bit full scale and clamped to 0–1 upstream of
the builder. The beat flag compares frame energy against a decaying running
average: the first frame never beats (no baseline), and sustained loudness stops
beating as the average catches up.

**No audio is recorded or transmitted.** The PCM buffer is a reused
coroutine-local array reduced to these three scalars in place. Nothing
audio-derived is written to storage. The microphone is optional at every layer —
without the permission, or on a background/boot start, this channel is simply
silent.

Consumed by `:app` for the passenger oscilloscope, and by `zvision`'s
`audio_bus` for the tracker light's idle sound-reactive show.

### 2.8 `$ZENV` — ambient light

```
$ZENV,<lux>*CC
```

| Field | Type | Units | Format |
|---|---|---|---|
| 1 | illuminance | lux, raw sensor value | `%.1f` |

Example: `$ZENV,315.0*02`

Emitted every 8th tick (~2 s), **only when a reading exists** — a device with no
light sensor emits nothing rather than `0.0`, and the tablet shows `-- lx (no
sensor)`.

Drives the tablet's automatic screen dimming: a log-10 interpolation between
5 lux (brightness floor 0.05) and 2000 lux (1.0), applied as a **ceiling** that
the burn-in phase backlight can further reduce but never raise.

### 2.9 `$ZSHK` — shock / impact

```
$ZSHK,<peakG>*CC
```

| Field | Type | Units | Format |
|---|---|---|---|
| 1 | peak acceleration | g (1 g = 9.80665 m/s²), gravity removed | `%.2f` |

Example: `$ZSHK,2.35*3C`

**Event-driven, not periodic.** A window opens when linear acceleration magnitude
reaches 1.5 g, tracks the peak for 120 ms, then reports **the peak of the impulse
rather than its onset**. A 500 ms refractory period starts when the window
*closes*, so a single bump produces one sentence.

Because every datagram is sent twice (multicast + broadcast), the tablet
de-duplicates byte-identical `$ZSHK` lines inside a 200 ms window. Consumers
diff a monotonic `shockCount` rather than watching the value.

### 2.10 `$ZBCN` — beacon health

```
$ZBCN,<batteryPct>,<fixQuality>,<satellites>,<uptimeSec>*CC
```

| Field | Type | Meaning |
|---|---|---|
| 1 | integer | battery percentage, 0–100 (`0` if the battery intent is unavailable) |
| 2 | integer | GNSS fix quality — the GGA field-6 code; `0` = no fix |
| 3 | integer | satellite count |
| 4 | integer | service uptime in seconds |

Example: `$ZBCN,87,1,9,3600*17`

Emitted every 20th tick (~5 s); the same tick persists the lifetime odometer.
Fields 2 and 3 are sniffed from passing GGA sentences; if none has arrived, the
previous values persist rather than resetting.

The tablet's ops footer flags battery ≤ 20 % and either `fixQuality ≤ 0` or
`satellites < 5` in red.

### 2.11 `$ZODO` — odometer

```
$ZODO,<tripMeters>,<totalMeters>*CC
```

| Field | Type | Units | Format |
|---|---|---|---|
| 1 | trip distance since service start | **metres** | `%.1f` |
| 2 | lifetime distance | **metres** | `%.1f` |

Example: `$ZODO,1234.5,987654.0*1E`

Both fields are metres — the on-screen readouts divide by 1000, the sentence does
not. The lifetime total is persisted in the beacon's `SharedPreferences` and
survives restarts.

Emitted every 8th tick (~2 s).

Distance is haversine-accumulated with three fail-closed gates, all of which hold
the anchor and add zero rather than guessing:

| Gate | Rule |
|---|---|
| Accuracy | reported accuracy must be present and ≤ 20 m — applied to the **first** fix too, so a poor first fix never becomes the anchor |
| Jitter floor | steps below 5 m are ignored |
| Teleport | any step implying more than 160 kph is rejected, as are non-positive time deltas |

Recovery is automatic: once enough time has elapsed for a step to be plausible,
it is accepted again.

### 2.12 Cadence summary

One 250 ms tick loop drives everything periodic, with integer divisors instead of
per-channel timers. Tick 0 satisfies every divisor, so all periodic channels fire
on the first tick.

| Channel | Trigger | Effective rate |
|---|---|---|
| raw GNSS | OS NMEA callback | whatever the receiver emits (requested ≥ 1 Hz) |
| `$GPHDT` | every tick, if heading available | ~4 Hz |
| `$ZTLM` | every tick | ~4 Hz |
| `$ZENV` | every 8th tick, if lux available | ~0.5 Hz |
| `$ZODO` | every 8th tick | ~0.5 Hz |
| `$ZBCN` | every 20th tick | ~0.2 Hz |
| `$ZAUD` | per audio frame | ~15.6 Hz |
| `$ZSHK` | on impact | event-driven |

There is **no throttling, deduplication or change-detection** beyond these
divisors. An unchanged value is re-broadcast identically, which is what makes the
stream self-healing for a tablet that just joined the network.

---

## 3. `ZTHREAT` — `zvision` → `:app`

### 3.1 Frame format

```
ZTHREAT;<id>:<relAzDeg>:<size>:<collision>;<id>:<relAzDeg>:<size>:<collision>;...
```

One UDP datagram is exactly one frame. A bare `ZTHREAT` with no contacts is an
explicit **all-clear**, and is what the runner emits on shutdown.

Example:

```
ZTHREAT;1:-12.0:0.300:0;2:4.5:0.900:1
```

| Field | Type | Meaning |
|---|---|---|
| `id` | integer | track id. `0` is the "unidentified" id and never latches the tracker light. Ids are namespaced per camera as `camera_index × 1000 + local_id`. |
| `relAzDeg` | fixed-point, 1 decimal | bearing relative to the vehicle's nose. **Negative = left, positive = right. Full circle: ±180.** |
| `size` | fixed-point, 3 decimals | apparent size, 0 = far → 1 = near. A *sensor prominence* proxy for range, **not** a distance. |
| `collision` | `0` or `1` | constant-bearing-while-growing collision flag |

Note there is no leading `$` and no checksum — this is not NMEA. UDP already
carries a checksum, and the frame is validated structurally.

### 3.2 Limits

| Constant | Value |
|---|---|
| Header | `ZTHREAT` |
| Frame separator | `;` |
| Field separator | `:` |
| Fields per contact | 4 |
| Max absolute azimuth | 180.0 |
| Max contacts per frame | 32 |
| Azimuth decimals | 1 |
| Size decimals | 3 |

The 32-contact cap keeps a frame inside one MTU — a fragmented multicast datagram
over lossy WiFi essentially never arrives. When a frame would exceed the cap the
producer sorts by **collision first, then size descending** and truncates, so the
contacts that survive are the ones that matter.

Bearings past ±180 are garbage rather than rear contacts, and are dropped.
`size` is clamped into 0–1 by the consumer. A malformed `collision` flag never
costs the driver a contact — the body is still drawn, just not in collision red;
anything that is not exactly `"1"` is false.

### 3.3 The numeric grammar is pinned, deliberately

```
id      -?[0-9]{1,9}
number  -?[0-9]{1,9}(\.[0-9]{1,6})?
```

Both sides check these patterns explicitly rather than delegating to
`toFloatOrNull` / `float()`. **A wire format must not inherit its host language's
parser quirks.** `[0-9]` rather than `\d`, because Python's `\d` also matches
Unicode digits and Java's does not.

Framing whitespace is spelled out as exactly `" \t\n\r\v\f"` on both sides,
because Kotlin's `trim()` and Python's `strip()` disagree about which Unicode
code points count, and a wire contract cannot depend on that.

Nine integer digits keeps an id inside Int32 and keeps every value far below
32-bit float range, so no parsed number can be NaN or infinite.

### 3.4 Producer rules

- Contacts with a non-finite azimuth or size are dropped **at the producer**. A
  NaN would serialise as the literal `nan`, which both parsers reject anyway —
  but a producer must keep its own frames well-formed rather than leaning on the
  consumer's guard being there.
- Fixed-point formatting rounds **half-to-even** on both sides. The Kotlin side
  uses `BigDecimal`, which is locale-independent by construction — `String.format`
  would follow the device locale and emit `0,300`.
- The Kotlin side restores the sign of `-0.0` from the raw float bits, because
  `BigDecimal` has no negative zero and would spell it `0.0` — and a contact a
  hair to the left of the nose is exactly where that shows up.

### 3.5 The golden corpus

`ThreatProtocol.kt` and `threat_protocol.py` are **two hand-written
implementations of the same grammar, and neither is authoritative.** The shared
truth is the measured corpus at
[`protocol/threat-protocol-golden.json`](../protocol/threat-protocol-golden.json),
read by both test suites:

- `jetson/tests/test_threat_protocol_golden.py`
- `app/src/test/java/.../core/vision/ThreatProtocolGoldenTest.kt`

Both fail loudly if the corpus is missing or truncated, rather than skipping —
a corpus that failed to load would make every assertion vacuously true, which is
a failure mode this project has been bitten by before. Both CI workflows are
triggered by changes under `protocol/**`.

The corpus carries 111 parse vectors, 27 format vectors, the shared constants,
and a `known_precision_limits` section which is explicitly **not** a to-do list:
the producer holds 64-bit floats and the tablet 32-bit, so a handful of values
whose two roundings straddle a decimal boundary are spelled differently by each
side. Both parsers accept either spelling and the difference is far below one
wire digit. They are recorded so nobody "fixes" them.

**Why it exists.** Before the corpus, the two implementations had silently
drifted in ten measured ways. Three of them:

- Kotlin's float parser accepts Java *source* syntax, including hex floats — so
  `0x1p3` in an azimuth field became **a live contact bearing 8° on the driver's
  HUD**, while the Python side rejected the same frame.
- Python's `int()` accepts underscores, surrounding whitespace and unbounded
  magnitudes — a 4000-digit track id parsed fine there and was dropped on the
  tablet.
- A size of `3.5e38` is finite in 64-bit and infinite in 32-bit, so one side kept
  the contact and the other discarded it.

The Python side now rounds parsed values through 32-bit float so both agree on
the range and clamp boundaries.

**If you change the format**, re-run the differential comparison that produced the
corpus rather than hand-editing the JSON. Hand-editing lets you write down what
you believe instead of what the code does, which is the exact failure this file
exists to prevent.

### 3.6 What the consumer does with a frame

`NetworkThreatSource` publishes the parsed contacts and marks the feed alive. A
1.5 s silence watchdog clears both. `RoutedThreatSource` derives an explicit feed
state:

| State | Meaning | Shown as |
|---|---|---|
| `LIVE` | frames arriving | `n CONTACTS   CLEAR` (or an alert), solid ring rim |
| `DEMO` | no frames, synthetic source enabled | `n CONTACTS   DEMO`, dotted grey rim |
| `ABSENT` | no frames, no synthetic source | `NO VISION`, dotted red rim |

**An empty-but-live frame is a real all-clear** and must never trigger the demo
fallback. On a deployed vehicle the demo source is disabled, so a dead feed reads
`ABSENT` rather than inventing pedestrians.

---

## 4. Known cross-side discrepancy

**Field-of-view reference.** The tablet treats the forward thermal camera as
covering ±64° (`SurroundRing.COVERED_ARCS`), following the conclusion in
`jetson/HARDWARE.md` that the Lepton Ultra Wide's quoted 160° is a *diagonal*.
**As of 2026-08-10 `zvision`'s `--fov-ref` defaults to `d` too**, so both sides
compute ±64° by default and agree without anyone remembering a flag.

**Confirmed by measurement, not argument:** a cold target at a true 40.4° was
reported at 42.5° (implying a ~61° horizontal half-FOV); the horizontal reading
would have reported 32.3°. The historical note below is kept so that anyone
reading a bearing off the bus knows the edge values are unverified until it is
resolved.

---

## 5. `ZNAV` — shared nav target (`:app` ↔ `:app`)

`239.7.7.30` : `10130`. Single-language, `:app`-only — no golden corpus, no
cross-language drift risk (§3 doesn't apply). Builder + parser both live in
`core/ops/NavShareProtocol.kt`; the Lamport ordering + ownership state machine
is `core/ops/NavShareArbiter.kt`, kept separate and pure (no I/O) from the
send/receive plumbing (`data/nav/NavShareSender.kt`,
`data/nav/NavShareReceiver.kt`).

### 5.1 Operating model

The S9+ (entry display) and the A54 (HUD) are **nav-authority** tablets: only
they may set + broadcast the destination. The two Fires (and every other
tablet) **adopt** it but cannot change it locally — a passenger poking a Fire
must not be able to diverge the HUD's guidance. `CockpitUiState.navAuthority`
gates both the ViewModel's send path (`NavShareController.userSet`, the hard
gate) and the UI's affordance (`driveToBar`'s `enabled`, the address keypad).

The DRIVER HUD's heading arch (`DriverNightScreen.kt`) already reads
`CockpitUiState.activeDriveTarget` — this feature only gets the S9+'s chosen
target onto that field on every tablet; no new HUD UI was added.

### 5.2 Framing and grammar

Same framing as §2.1 (`$<BODY>*<CC>\r\n`, US-ASCII, `Locale.US`, two-hex XOR
checksum, 1–2 hex digits accepted on read). Four sentence shapes:

```
$ZNAV,<seq>,<src>,PRESET,<HOME|MAN|TEMPLE>*CC\r\n     e.g. $ZNAV,7,4A0C11,PRESET,MAN*CC
$ZNAV,<seq>,<src>,ADDR,<H:MM>,<RING>*CC\r\n           e.g. $ZNAV,8,4A0C11,ADDR,2:15,H*CC
$ZNAV,<seq>,<src>,BATH*CC\r\n
$ZNAV,<seq>,<src>,CLEAR*CC\r\n
```

| Field | Grammar | Semantics |
|---|---|---|
| `seq` | `[0-9]{1,9}` | Lamport counter, ≥ 1 on the wire; fits Int32 |
| `src` | `[A-Z0-9]{1,8}` | stable device id (last 6 of `Settings.Secure.ANDROID_ID`, uppercased); the ordering tie-break key |
| preset | `HOME\|MAN\|TEMPLE` | maps to `NavTarget` by name |
| clock | `[0-9]{1,2}:[0-9]{2}` | hours **2..10**, minutes 0..59 (mirrors `ClockEntry`'s `isCityClock`); out of range → the whole sentence rejects |
| ring | `[A-Z]{1,9}` | must be a key of `StreetRingRadiiM` (ESPLANADE, A..K) → else rejects |

Any violation — bad checksum, unknown type, wrong field count, an
out-of-range clock/ring — makes `NavShareProtocol.parse` return null. It never
throws: range checks run *before* constructing a `ClockTime`, whose own
`require` allows the wider 1..12 display range and would throw outside it.

**The payload is semantic, not resolved coordinates.** `PRESET` and `ADDR`
reconstruct through `NavigationController`'s own entry points on the
receiving side (`setNavTarget` / `driveToAddress`) — the exact same call a
local user action makes, so there is one code path and no risk of the two
sides drifting. `BATH` stays dynamic: every device resolves its own
nearest-toilet live from the shared ego (position is already fleet-wide via
the beacon), so all devices agree without shipping a coordinate. `CLEAR` has
no "no target" state to clear to — `CockpitUiState.activeDriveTarget` always
falls back to a preset, default `HOME` — so `CLEAR` adopts `PRESET,HOME`'s
effect; it stays a distinct wire type because the *intent* ("cancel") is
worth keeping legible in a capture even though the resulting state is
identical.

### 5.3 Ordering, ownership, re-broadcast

Total order on `(seq, src)`: higher `seq` wins outright; an equal `seq` is
broken by the lexicographically greater `src`. A device adopts a received
message iff its key is strictly greater than the last key it applied (locally
or by a prior adoption) — a device that has never applied anything (late
join) adopts the first valid message it hears.

The device whose last-applied key carries its own `src` is the **owner** and
the sole periodic re-broadcaster, every **3 s** — long enough to be cheap,
short enough that a HUD that reboots, cold-starts, or fails over to the
Jetson's backup AP re-syncs within seconds. Setting locally always outbids
everything seen so far and makes that device the owner; adopting a
higher-seq remote message yields ownership (`NavSharePublisher.stop()` on the
device that had it).

**Adoption never re-broadcasts** — `NavShareController.onReceived` never
calls `publish`, full stop. That, not just the arbiter refusing to *adopt* a
device's own echo, is what stops every device re-transmitting everything it
hears. Own-echo is real and expected: the receiver binds wildcard and the
sender transmits both multicast and subnet broadcast (§1's "every sender
transmits twice"), so a device hears its own transmission reflected back —
`NavShareArbiter.onReceived` ignores any message whose `src` equals its own
before doing anything else.

**No byte-window dedup needed**, unlike `$ZSHK` (§2.9's 200 ms window):
adoption keyed on `(seq, src)` is idempotent on its own. A byte-identical
re-delivery of the same message parses to an equal `NavShareMessage`, and
since `NavShareReceiver.messages` is a `StateFlow`, a value-equal re-emission
doesn't even notify the collector — the double-delivery problem dissolves
for free instead of needing its own guard.

### 5.4 Seq persistence

The Lamport counter must survive a reboot: without persistence, a rebooted
authority mints `seq = 1` on its first set, and every follower still holding
a higher seq from before the reboot rejects it forever. `CockpitPreferences`
persists the counter separately from the settings snapshot (`readNavShareSeq`
/ `setNavShareSeq`, the same shape as `readBurnInConfig` / `setBurnInConfig`)
— written on a local user-set and on adopting a higher seq, not on every
periodic re-broadcast.

**Known residual gap, documented rather than fixed:** a *fresh install*
mid-week starts its counter at 0. If that tablet sets locally before hearing
any traffic, its low seq loses to whatever the rest of the fleet already
agreed on, until it hears a higher-seq re-broadcast (within the owner's next
3 s tick) and catches up. Acceptable: it self-heals within seconds and only
affects a tablet that has never before participated.

### 5.5 Authority + reception (spec R3/R4)

Authority — automatic, not a toggle: it *is* OLED-device-ness,
`BurnInDeviceProfile.visualModulationSupported()` (`Build.MANUFACTURER !=
"Amazon"`, case-insensitive), computed once at `MainActivity` startup and
wired into `CockpitViewModel` as a constant flow. True on the two Samsungs
(S9+, A54), false on the two Fires — no per-device provisioning step, no
runtime state to drift, no hidden gesture.

Reception is universal — every tablet runs a `NavShareReceiver` regardless of
its own authority, mirroring `NetworkLocationSource`'s hardened listener
(wildcard bind + group join via the shared `openFleetNmeaSocket` seam, a held
`WifiManager.MulticastLock` under its own `"zodiac-znav"` tag, rebuild on
`rejoinSilentMs` (30 s) silence, capped-exponential backoff on bind/read
failure). Unlike GPS, silence is `$ZNAV`'s normal idle state — no owner means
no traffic — so the periodic rebuild-on-silence exists purely to survive an
AP failover mid-idle, not to detect a dead feed.
