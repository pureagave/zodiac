# Zodiac — System Architecture

The engineering map of all three codebases: how each is layered, how data flows
through it, which types matter, and where the seams between the parts are.

> [`README.md`](README.md) is the public snapshot; [`SYNC.md`](SYNC.md) is the
> dated decision log; [`docs/PROTOCOLS.md`](docs/PROTOCOLS.md) is the byte-level
> wire reference; [`docs/BUILD.md`](docs/BUILD.md) is build/test/deploy.
> This file is the thing you read to find your way around the code.

**Status legend:** ✅ built and exercised · 🚧 built, not yet proven on the
vehicle · 📋 planned, no code.

---

## 1. Design commitments

Two principles explain most of the structure.

**Offline-first, LAN-local.** Every realtime, safety-relevant path runs on the
vehicle's own WiFi with no dependence on the internet. Starlink is aboard but
treated as unreliable. Only non-safety extras — the Burning Man API discovery
cache — touch the WAN, and those are cached offline-first with a stale cache
strictly preferred over an empty one.

**One-to-many by default.** Sensors are shared: *one* GPS, *one* camera ring →
*every* screen. Sources publish on fixed multicast groups; any number of
subscribers listen. Nothing unicasts, nothing handshakes, nothing has a
hardcoded IP.

A third rule governs how the code is written rather than how it is arranged:
**measure, don't guess.** A fixture manual said the moving head's tilt range was
270°; it is 180°, and believing the manual scaled every tilt command by 1.5×.
Where a number in this repository came from a datasheet rather than a
measurement, the code says so.

---

## 2. Hardware and roles

Full rationale in [`docs/DEVICES.md`](docs/DEVICES.md).

| Role | Device | Software | Status |
|---|---|---|---|
| Hero dashboard | Galaxy Tab S9+ (`SM-X810`), 12.4" OLED | `:app` | ✅ |
| Driver night display | Galaxy A54 (`SM-A546V`), OLED | `:app` (DRIVER) | 🚧 night legibility unverified |
| Passenger / crew displays | Fire HD 10 (`KFTUWI`) + others, LCD | `:app` | ✅ |
| Sensor hub | XCover Pro (`SM-G715U`), headless | `:beacon` | ✅ |
| Vision edge box | Jetson Orin Nano Super | `zvision` | 🚧 no camera attached yet |
| Light control surface | Stream Deck Mini (`0fd9:0063`) | `zdeck` | ✅ |
| Tracker light | DMX moving head + FTDI USB-DMX + `olad` | `zvision` / `zdeck` | 🚧 aim uncalibrated |
| Network | travel router, single /24, DHCP | — | ✅ |
| Proximity alarm | ESP32 + 24 GHz radar or LiDAR | — | 📋 |

The **driver display is OLED** because a mostly-black UI at low brightness
preserves dark adaptation; an LCD backlight floods light through "black" pixels
and wrecks night vision. The **camera lives on a dedicated box at the front** —
not on the GPS phone (wrong physical location) and not on a tablet (that couples
the sensor to one display). The **Fire HD 10 is the performance floor** but does
not certify visual parity, because its GPU takes a different precision path for
the CRT halos than the Samsungs do.

---

## 3. The fleet bus

Single L2 subnet, dynamic DHCP, **no hardcoded IPs anywhere.** Discovery is the
transport itself: a source announces by transmitting on a fixed multicast group
that is compiled into every part.

Constants live in exactly two mirrored places —
`app/.../core/net/FleetBus.kt` and `jetson/zvision/fleet_bus.py` — with
`:beacon` restating the telemetry pair in `TelemetryBroadcaster`:

```
TELEMETRY_GROUP = 239.7.7.10   TELEMETRY_PORT = 10110    TTL = 1
THREAT_GROUP    = 239.7.7.20   THREAT_PORT    = 10120
```

`239.0.0.0/8` is administratively scoped, so nothing leaves the vehicle.

**Every sender transmits twice** — once to the group, once to the /24
subnet-directed broadcast — because some access points silently drop multicast.
**Every receiver binds the wildcard address** on the port and *additionally*
joins the group, so both copies arrive. The cost of this is duplicate delivery,
which is why the tablet de-duplicates byte-identical `$ZSHK` lines inside a
200 ms window.

**Android multicast gotcha, handled:** the WiFi driver filters broadcast and
multicast frames from apps to save power unless the app holds a
`WifiManager.MulticastLock` (with `CHANGE_WIFI_MULTICAST_STATE`). Both
`NetworkLocationSource` and `NetworkThreatSource` hold one.

### Channels

| Channel | Source | Consumers | Payload | Status |
|---|---|---|---|---|
| Vehicle telemetry | `:beacon` | all tablets | raw GNSS NMEA verbatim + `$GPHDT` + `$ZTLM` | ✅ |
| Sensor channels | `:beacon` | all tablets | `$ZAUD` `$ZENV` `$ZSHK` `$ZBCN` `$ZODO` | ✅ |
| Thermal threats | `zvision` | all tablets, DMX light | `ZTHREAT` frames | 🚧 both ends built, no camera yet |
| Destination / nav | hero dashboard | all tablets | active drive-to target | 📋 |
| Vehicle commands | — | vehicle | heading / speed | 📋 (transports are fake) |
| Raw thermal video | `zvision` | opt-in viewers | MJPEG/RTSP | 📋 |

mDNS / DNS-SD (`NsdManager`) for richer service discovery and health remains 📋.

---

## 4. `:app` — the Android cockpit

Package `org.pureagave.zodiac.control`. Kotlin + Compose. Reactive core:
delegates fold repository and source flows into **one**
`StateFlow<CockpitUiState>` that the whole UI reads.

### 4.1 Dependency injection

**Manual, in `ZodiacApplication.kt`** — no Hilt or Dagger. Everything is `by
lazy` in a process-lifetime `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`,
so construction order is first-access order. `onCreate` does the only ordered
work: plant the debug `Timber` tree, plant the file log tree, install the crash
logger, then log a boot line naming the device, API level, active BRC year and
log path.

Two objects are deliberately hoisted out of their registries so a second
consumer can reach the *same instance*: `fakeLocationSource` (the debug nudge
chips drive it) and `networkLocationSource` (the ViewModel reads its
`beaconSensors` and `audioLevel` flows).

### 4.2 Layer map

```
MainActivity ─ immersive chrome, permission gate, kiosk engage
     │
CockpitScreen ─ burnInScaffold → theme → dispatch on CockpitConcept
     │            ├── RADAR   MotionTrackerScreen
     │            ├── MAP     InstrumentBayScreen
     │            ├── DRIVER  DriverNightScreen
     │            └── passengerScreen (when this device holds the passenger role)
     │
CockpitViewModel ─ one StateFlow<CockpitUiState>
     │   ├── MapCameraController    pan / zoom / rotate / tilt / recenter
     │   ├── NavigationController   drive-to targets, routing, street + art callouts
     │   └── GpsController          source selection, fake-GPS nudges
     │
data/ ─ VehicleConnectionGateway · TelemetryRepository · LocationSource
        ThreatSource · PlayaMapRepository · DiscoveryRepository · CockpitPreferences
     │
core/ ─ pure logic, no Android: geo · navigation · ops · vision · telemetry
        sensor · model · connection · log · net · passenger · permission
```

`core/` is where every decision that can be tested without a device lives. The
rule the codebase follows consistently: **no new logic in a composable** — draw
code turns decisions into pixels, and the decisions live in `core/` with tests.
`SurroundRing` / `SurroundRingCanvas` is the clearest example of the split.

### 4.3 The ViewModel and its delegates

`ui/viewmodel/` is four files. `CockpitViewModel` (457 lines, 20 public
functions) owns the single `MutableStateFlow<CockpitUiState>`, the flow
subscriptions, and the vehicle-command actions; it constructs and delegates to
three controllers that share that state handle:

| File | Owns |
|---|---|
| `CockpitViewModel.kt` | state ownership, `init` flow wiring, heading/speed/transport/concept actions, map-load retry |
| `MapCameraController.kt` | `setMapMode`, `setTiltDeg`, `setPixelsPerMeter`, `panBy`, `nudgeViewRotation`, `recenterPan`, auto-recenter scheduling |
| `NavigationController.kt` | `setNavTarget`, `driveToNearestToilet`, address entry, route recomputation, street-crossing and passing callouts |
| `GpsController.kt` | `selectLocationSource`, `restartLocationSource`, fake-GPS nudge/reset |

The public API of `CockpitViewModel` is unchanged by the split — the delegates
are internal collaborators, not a new surface.

**Input validation lives in the ViewModel layer, not the UI**, so every entry
point — chip, gesture, synthetic GPS, persisted preference — is bounded by the
same rules: heading 0–359, speed 0–160 kph, tilt 0–80°, zoom 0.05–5.0 px/m,
camera pan ±5000 m per axis.

`CockpitUiState` is one immutable data class of 37 fields updated with `.copy()`.
That is a known cost — a per-frame field change copies the whole state — tracked
as item A5 in `tasks/open.md`.

### 4.4 Vehicle command path — entirely simulated

| Type | Implementations |
|---|---|
| `TransportAdapter` | **`FakeTransportAdapter` only.** Three instances registered, one per `TransportType` (BLE / USB / WIFI). |
| `TelemetryRepository` | **`FakeTelemetryRepository` only** in production. |
| `VehicleCommand` | `SetHeading(Int)`, `SetSpeed(Int)` — the entire vocabulary. |

`RoutedVehicleGateway` forwards commands to the selected adapter.
`FakeTransportAdapter.connect()` transitions CONNECTING → (120 ms) → CONNECTED;
`send()` while disconnected sets an ERROR phase and drops the command.
`FakeTelemetryRepository` emits a deterministic 500 ms tick of
heading/speed/thermal/mode that nothing on a real vehicle produces.

**Real vehicle data does not travel this path at all.** It arrives through
`NetworkLocationSource` and `NetworkThreatSource`. The gateway is a boundary
kept warm for a vehicle bus that does not exist yet.

> **Note the two routers have deliberately opposite lifecycle policies.**
> `RoutedLocationSource.select()` stops the old source before starting the new
> one. `RoutedVehicleGateway.selectTransport()` leaves the old adapter connected.
> Both are documented in place and pinned by tests. `FailoverLocationSource`
> exists precisely because the location policy makes failover-on-top-of-routing
> impossible any other way.

### 4.5 GPS sourcing

`LocationSource` is `type` + `StateFlow<LocationSourceState>` + `start()` /
`stop()`. `LocationSourceState` is `Disconnected` / `Searching` / `Active(GpsFix)`
/ `Error(detail, kind)`.

| Type | Implementation | Notes |
|---|---|---|
| `FAKE` | `FakeLocationSource` | integrating dead-reckoner around the Golden Spike, driven by the debug chips; 500 ms tick |
| `SYSTEM` | `SystemLocationSource` | Android `LocationManager` GPS_PROVIDER behind an injectable handle; 1 s / 1 m |
| `BLE` | `BleLocationSource` | Bluetooth **Classic SPP** despite the name; matches paired device names against a GPS-receiver regex |
| `USB` | `UsbLocationSource` | `usb-serial-for-android`, 9600 8N1, lines reassembled by `NmeaLineAssembler` |
| `NET` | `NetworkLocationSource` | the fleet path — see below |

`RoutedLocationSource` + `LocationSourceRegistry` do the runtime selection.
`FixFreshness` (5 s) demotes a stale `Active` back to `Searching` — and only
`Active`, never an `Error`.

**Errors name their own fix.** `LocationSourceError` categorises every failure by
what the operator would do about it, rendered under the GPS chips as
`⊘ PERMISSION` / `⊘ ADAPTER OFF` / `? NO DEVICE` / `✕ I/O`, with the free-text
detail going to the rolling log.

**`NetworkLocationSource`** binds the wildcard address on 10110, sets
`reuseAddress`, joins `239.7.7.10`, and holds a `MulticastLock` behind an
injectable handle (so unit tests get a no-op). It tracks three independent
liveness clocks — position (GGA/RMC), heading (HDT), and beacon (`$Z*`) — so a
dead compass demotes heading to GPS course after 8 s without also making the
position look stale, and 12 s of beacon silence clears the sensor readings.
`start()` is idempotent, `stop()` **joins** the listener before clearing state so
an in-flight read cannot repopulate it. A socket failure is not terminal: the
source rebuilds the socket on exponential backoff (1 s → 30 s cap), and a socket
that has heard nothing for 20 s is rebuilt too, because rebuilding is the only
way to re-join a group after a router reboot.

**Failover.** `FailoverLocationSource` wraps NET with SYSTEM, presents itself as
`NET` (so the chip and the persisted preference stay honest), and keeps **both**
running so the fallback is warm. `LocationFailoverPolicy` drops to the fallback
after 3 s of an unhealthy primary — on top of the 5 s staleness window, so ~8 s
total blind — and recovers only after 10 s of health, deliberately asymmetric so
a half-alive beacon cannot flap the source. It is armed only on devices that
report `FEATURE_LOCATION_GPS`, asked of the hardware rather than assumed by model.

### 4.6 NMEA parsing

`data/sensor/nmea/NmeaParser` is talker-agnostic (it dispatches on the last
three characters, so `$GP` / `$GN` / `$GL` / `$GA` / `$GB` all match) and
validates the XOR checksum on every entry point, accepting one or two hex digits.

Handled: **GGA** (position, fix quality, HDOP→accuracy), **RMC** (position, speed
in knots→kph, course), **HDT** (true heading), and the five proprietary channels
plus `ZTLM`.

**Deliberately not handled: VTG, HDG, HDM.** VTG is GPS *course*, not compass —
Android GNSS chips emit it every epoch and the beacon forwards raw NMEA verbatim,
so treating VTG as heading would interleave with the synthesized `$GPHDT` and
flip-flop a stopped vehicle's heading, *and* would keep the heading clock fresh,
defeating the dead-compass watchdog. HDG/HDM are *magnetic* heading — roughly 13°
east of true at Black Rock City — so applying them as true heading would be
silently wrong by that much.

### 4.7 Vision and threats

| Type | Role |
|---|---|
| `ThreatSource` | `threats: StateFlow<List<DriverThreat>>` + `feedAlive: StateFlow<Boolean>` |
| `NetworkThreatSource` | listens on `239.7.7.20:10120`, 1.5 s staleness watchdog |
| `FakeThreatSource` | three synthetic contacts including a recurring collision |
| `RoutedThreatSource` | picks live or demo, derives `VisionFeed` = `LIVE` / `DEMO` / `ABSENT` |

`demoEnabled` has **no default value, deliberately.** It once defaulted to `true`
and production never overrode it, which put three fabricated contacts — one
flagged as a collision course — on the driver's night HUD.
`ZodiacApplication` passes `false`.

An empty-but-live feed is a real all-clear and must never trigger the demo
fallback. `VisionFeed` is surfaced, not hidden: the HUD status line reads
`DEMO` explicitly, the surround ring's rim goes dotted, and the passenger
"souls detected" card is dropped entirely unless the feed is `LIVE`.

`core/vision/SurroundRing` holds every decision for the DRIVER HUD — bearing
wrap, sector and band classification, angular clustering with hysteresis, the
brake and check-rear rules, and the five-state status precedence.
`DriverAlerts` latches both alerts through `AlarmLatch` (instant attack, 1.5 s
release) so a one-frame contact cannot produce a one-frame warning.

`brakeAdvised` is gated on speed ≥ 5 kph, reading `effectiveSpeedKph`
(`gpsSpeedKph ?: speedKph`) — the fix for the audit's A1 finding, where the gate
read only the debug chips' speed and so could never fire on a real drive.

### 4.8 Playa map and navigation

**Data pipeline.** GeoJSON assets → `GeoJsonParser` → typed `PlayaMap` →
`PlayaMapBinaryCache`. The cache is a hand-rolled big-endian format
(magic `0x504C4159` = `PLAY`, schema version 2, year in the header) written to a
`.tmp` and renamed for atomicity; a magic, version or year mismatch is a silent
miss that falls through to re-parsing the JSON. `PlayaMapRepository` publishes
the map *first* and writes the cache fire-and-forget, so serialisation never
delays time-to-first-map. A load failure surfaces as `MapLoadResult.Failed` and
is rendered with a RETRY control.

The parser reads both schemas: 2025 used `type` and a string `width`; 2026 uses
`source` and an integer `width_ft`. That dual read is why the cache schema
version is 2.

**Geometry.** `PlayaProjection` is equirectangular anchored on the Golden Spike
(`R = 6 371 000 m`), with an allocation-free inline variant for the renderer's
polyline walk. `PlayaViewport` is track-up with a configurable anchor, so TOP
mode centres the ego and TILT mode anchors it to the lower third.

**Active year is one source of truth.** `GoldenSpike.ACTIVE = Y2026`. The 2026
city translated ~583 m SW from 2025 and did **not** rotate — every 2026 radial
still puts the 12:00 axis at 45.0° true, verified against the shipped GIS (the
4:30 portal lands at exactly 180.0°).

**Navigation.**
- `ClockBearing` converts between BRC clock positions and true bearings about
  that 45° axis.
- `PlayaCityModel` derives the ring radii and street polylines from the loaded
  map rather than an idealised circle.
- `PlayaNavigator` snaps the ego to the nearest street within 15 m and produces a
  `NavigationCue` — on a radial inbound/outbound, on a named arc at a clock
  position, or heading toward/away from a clock position across open playa.
- `PlayaRoute` decides in-city vs open-playa (clock 2–10, outside Esplanade,
  inside the outer ring) and, when in-city, routes out to the nearest entrance
  radial, in to Esplanade, along the ring, to the address — **snapping every
  corner to a real GIS street vertex**, which is what keeps the drawn line on the
  drawn streets. `nextWaypoint` is stateless, so it survives any recomposition.
- `StreetRingRadiiM` holds the mean centreline radius of every ring, measured from
  the shipped GIS (Esplanade 761.5 m through K 1753 m) and re-measured by
  `BundledGisTest` against the assets — which is how a nine-metre error in the
  old Esplanade value was found.

**Drive-to targets.** `NavTarget` has exactly three entries — HOME (camp
`GALACTIC_RELAY`, "Heiau & 2:15"), MAN (the active Golden Spike), TEMPLE (the 2026
GIS CPN). BATH is resolved dynamically to the nearest toilet, and ADDR is built
from a typed clock + ring letter. The camp coordinate is a **geometric estimate**
on the 2026 grid, not a geocode — the real one comes from the BM API the Sunday
before gates.

### 4.9 Rendering

`PlayaMapPanel` is the shared map host used by both RADAR and MAP. The pipeline
caches aggressively because the Fire HD 10 is the floor:

```
PlayaMap → ProjectedMap (paths + point batches, cached per camera change)
         → drawProjectedMap (per frame)
         → drawProjectedLabels (zoom-gated, same pass)
```

Polylines are held as flat `DoubleArray`s and walked with inline projection
straight into a `Path` cursor, so the inner loop allocates nothing. Labels are
measured colour-agnostically so a palette change never invalidates the layout
cache. RADAR draws the map twice a frame — a dim base plus a lit re-blit clipped
to the rotating sweep wedge — which is why the cached path exists at all.

`ConceptTheme` is a strict semantic palette, set 2026-07-04 and shared by all
three concepts:

| Colour | Hex | Means |
|---|---|---|
| Phosphor green | `#00FF66` | all chrome — controls, borders, labels |
| Status blue | `#00BFFF` | status only — link/GPS state, "this is selected" |
| Data purple | `#C77DFF` | live data values — headings, speeds, clock, needles, ego |
| Dim green | `#2C8A4A` | de-emphasised secondary text |
| Alert red | `#FF5555` | faults and extreme warnings **only** |

Amber is banned. The DRIVER concept overrides these with its own dimmer night
set (`#009E4A` / `#00421E` / `#B874E0` / `#FF4848`) for dark adaptation.

### 4.10 Burn-in mitigation

`burnin/` protects the OLED dashboard over multi-day deployments where the
cockpit runs for days with mostly static chrome.

`BurnInMitigationManager` is a pure phase machine on an injectable clock
(`SystemClock.elapsedRealtime` — excludes deep-sleep drift and never jumps with
the wall clock), depending only on the location and connection flows plus a
narrow `BurnInConfigStore` persistence port. The ViewModel is untouched.

| Phase | Enters at | Backlight | Drawn |
|---|---|---|---|
| `ACTIVE` | any activity | system | dashboard, pixel-shift + brightness breathe |
| `DIM` | 5 min idle | 0.40 | dashboard at content alpha 0.30, breathing frozen |
| `DEEP_IDLE` | 30 min idle | 0.15 | `standbyScreen` — phosphor STANDBY + drifting scan line |
| `SLEEP` | 60 min idle | 0.01 | app-drawn pure black, Activity still foreground |

Activity is touch, **real** GPS movement (≥ 1 kph or > 3 m from the reference
fix — a parked car re-emitting identical fixes does not count), or a vehicle-link
phase change. `FLAG_KEEP_SCREEN_ON` is held in every phase; "sleep" is app-drawn
black rather than a real display-off, so wake is instant and there is no
lockscreen to fight.

`burnInScaffold` wraps the entire cockpit from one node and is the **single
writer** of `window.attributes.screenBrightness`. Ambient-light auto-dim
(`ScreenBrightness.luxToBrightness`, a log-10 interpolation between 5 lux and
2000 lux, floor 0.05) is folded in there as a *ceiling that can only reduce*:
a dark room's floor still beats DIM's larger backlight, and SLEEP's 0.01 can
never be raised by a lux tick. `MainActivity` used to also apply auto-dim and
the two writers fought.

Pixel-shift is universal. The brightness breathe / dim `graphicsLayer` is
OLED-gated by `BurnInDeviceProfile`, whose entire detection is a case-insensitive
`Build.MANUFACTURER != "Amazon"` check — there is no panel-type query.

`BurnInLedger` accumulates on-time per `<concept>/<phase>` zone and reports it to
the rolling log. Finer granularity was refused deliberately: a per-widget burn
risk number would be invented data, and an invented risk number is worse than
none.

Every parameter is in `BurnInConfig`, which coerces itself (timeouts forced
strictly increasing, alphas clamped) on both read and write, and is adjustable
live from the hidden tuning panel.

### 4.11 Hidden gestures

Four corner long-presses, none discoverable by design:

| Corner | Action | Defined in |
|---|---|---|
| Top-left | park — jump straight to STANDBY | `BurnInScaffold` |
| Bottom-left | burn-in tuning panel | `BurnInScaffold` |
| Bottom-right | on-device log viewer | `CockpitScreen` |
| Top-right | toggle this device's passenger role | `CockpitScreen` |

The passenger toggle is hidden in **both** directions: a rider must not be able
to leave passenger mode, and a driver's tablet must not fall into it.

### 4.12 Logging

`RollingFileLog` writes `zodiac.log` plus four rotated segments under
`getExternalFilesDir("logs")` — external app-specific storage, so it comes off a
Samsung with a plain `adb pull`, no root and no debug build. Default bound is
512 KiB × 5 ≈ 2.5 MB.

**It never throws, and it counts everything it loses.** Write failures, rotation
failures that could not honour the size cap, and lines aged out of the ring are
all counted separately and surfaced in the log viewer's header. Silent loss
would make the log a liar about its own completeness.

`FileLogTree` is the Timber tree. Writes go through a 256-line channel that drops
oldest on overflow and drains on IO, so a log call from a render or sensor
coroutine costs a `trySend` and cannot stall a frame. The uncaught-exception
handler writes synchronously before the process dies.

Tagged lifecycle events are the ones that actually get asked about: GPS source
selection, NET→SYSTEM failover, vision feed transitions, transport connect, which
path served the map, discovery refresh outcomes.

### 4.13 Preferences

`DataStoreCockpitPreferences` persists 21 keys in one Preferences DataStore —
GPS source, map mode, tilt, zoom, concept, passenger role, and every burn-in
parameter. Enums are stored **by name**, so a renamed constant falls back to the
default rather than mis-mapping. Every numeric read is clamped to the same range
the UI enforces, so a tampered or stale file cannot strand the UI. A
`ReplaceFileCorruptionHandler` is installed because a torn file would otherwise
be a crash loop on a kiosked tablet with no Settings access.

The default persisted GPS source is **`NET`, not `FAKE`** — a corrupt-prefs
recovery on a deployed tablet should land on real GPS.

---

## 5. `:beacon` — the sensor hub

Package `org.pureagave.zodiac.beacon`. minSdk 29, targetSdk 35. One foreground
service, one debug activity, no resources — the UI is built in code.

```
TelemetryService (foreground, START_STICKY, partial wake lock)
      │
TelemetryBroadcaster (object; SensorEventListener)
      ├── LocationManager NMEA listener ──▶ forwarded VERBATIM
      ├── LocationManager fix updates   ──▶ TripOdometer, speed for $ZTLM
      ├── TYPE_ROTATION_VECTOR          ──▶ $GPHDT, $ZTLM pitch/roll
      ├── TYPE_LINEAR_ACCELERATION      ──▶ ShockDetector ──▶ $ZSHK
      ├── TYPE_LIGHT                    ──▶ $ZENV
      ├── AudioRecord 16 kHz            ──▶ AudioLevels   ──▶ $ZAUD
      └── ACTION_BATTERY_CHANGED        ──▶ $ZBCN
      │
   TickLoop (250 ms) ──▶ MulticastSocket ──▶ 239.7.7.10:10110 + subnet broadcast
```

**Cadence.** One 250 ms tick loop drives everything periodic, with integer
divisors so nothing needs its own timer: `$GPHDT` and `$ZTLM` every tick
(~4 Hz), `$ZENV` and `$ZODO` every 8 ticks (~2 s), `$ZBCN` and the odometer
persist every 20 ticks (~5 s), the on-screen status every 4 ticks. `$ZAUD` is
driven by audio frames instead (1024 samples at 16 kHz ≈ 15.6 Hz) and `$ZSHK` is
event-driven. There is no rate limiting or change-detection beyond that — a
value that has not changed is re-broadcast identically, which is what makes the
stream self-healing for a tablet that just joined.

**Watchdog.** A second coroutine checks the tick clock every 2.5 s and, if the
loop has been dead for more than 5 s, prepends a banner to the status readout
naming exactly which channels stopped. The tick loop itself catches per-tick
exceptions and continues, rethrowing only cancellation.

**Sensor detail worth knowing.**

- **Heading** comes from `TYPE_ROTATION_VECTOR`, corrected to true north with
  `GeomagneticField` — but only once a location fix exists. Before the first fix,
  `$GPHDT` carries *magnetic* heading labelled `T`.
- **`AudioLevels`** computes rms and peak normalised to 0..1 and a beat flag from
  an energy comparison against a decaying running average. The first frame never
  beats (no baseline) and sustained loudness stops beating as the average catches
  up.
- **`ShockDetector`** opens a window when acceleration crosses 1.5 g, tracks the
  peak for 120 ms, and reports the **peak of the impulse rather than its onset**.
  The 500 ms refractory period starts when the window *closes*.
- **`TripOdometer`** accumulates haversine distance with three fail-closed gates:
  accuracy must be ≤ 20 m (applied to the *first* fix too, so a poor first fix
  never becomes the anchor), steps below a 5 m jitter floor are ignored, and any
  step implying more than 160 kph is rejected as GPS multipath. A rejected step
  holds the anchor, so recovery is automatic.
- **Absent sensors emit nothing.** `hdtSentenceOrNull` / `zenvSentenceOrNull`
  return null rather than a fabricated `0.0`, and the status readout shows `--`.

**Privacy.** The only audio API used is `AudioRecord`. The PCM buffer is a reused
coroutine-local array reduced to three scalars in place; nothing audio-derived is
written to storage or sent. `RECORD_AUDIO` is optional at every layer, and a
background or boot-started service passes `micEnabled = false` and never requests
the microphone foreground-service type.

**Foreground-service types** are computed by `ForegroundTypes`, a file with zero
Android imports (it mirrors the framework constants as literals) so the whole
matrix is unit-testable: location from API 34 gated on permission, microphone
only with the permission *and* not from a background start, floored to
`specialUse` so the result is never zero.

---

## 6. `jetson/` — the edge box

Python 3.8+. The `zvision` core has **zero declared dependencies** — standard
library only — with `numpy` and `opencv-python` as an optional `camera` extra
imported lazily. That is deliberate: the whole test suite, and the `--source
fake` bus proof, run anywhere.

### 6.1 `zvision` modules

| Module | Role |
|---|---|
| `app.py` | CLI runner; the broadcast + tracker loop |
| `rig.py` | camera mounts, `--camera` spec parsing, full-circle merge and overlap dedup, coverage-gap reporting |
| `capture.py` | `UvcCamera` and `ThermalCamera` (cv2) |
| `detector.py` | `Detector` protocol, `FakeDetector`, `MotionDetector` |
| `geometry.py` | pixel → bearing through a lens model, bbox → size, constant-bearing collision rule |
| `normalize.py` | the array-free arithmetic — contrast stretch window, track-id assignment, re-baseline guard |
| `threat.py` / `threat_protocol.py` | the `DriverThreat` record and the ZTHREAT wire format |
| `broadcaster.py` | UDP sender — multicast **and** subnet broadcast |
| `fleet_bus.py` | group/port constants, mirrored from Kotlin |
| `tracker.py` | DMX target selection, pan/tilt mapping, slew, idle sound show |
| `dmx.py` | DMX transport — `FakeDmxSink` and `OlaDmxSink` |
| `dmxpark.py` | standalone universe-zeroing fail-safe, own entry point |
| `audio_bus.py` | `$ZAUD` listener for the idle sound-reactive show |
| `recorder.py` | frame + weak-label dump for model training |
| `tracklog.py` | NMEA breadcrumb CSV recorder, own service |
| `trackserve.py` | read-only HTTP server for those CSVs |

### 6.2 The pipeline

```
_mounts_from_args ─▶ build_rig ─▶ MultiDetector
                                      │  per camera: detect() → to_global()
                                      ▼
                                 merge_contacts   (cross-camera overlap dedup)
                                      │
                   ┌──────────────────┴──────────────────┐
                   ▼                                     ▼
            format_frame ─▶ ThreatBroadcaster      Tracker.update ─▶ DmxSink
            (ZTHREAT, 10 Hz default)               (pan/tilt/dimmer)
```

**Camera declaration.** There is no camera config file. Cameras are declared
entirely by `--camera source[:device][:key=value...]` and, on the box, live in
`ZVISION_ARGS` in `/etc/default/zvision`. **As shipped by `install.sh` there are
zero real cameras** — the default written is `--source fake --hz 10`. The
service deliberately fails to start if that file is missing rather than
broadcasting synthetic contacts unattended.

**Fusion.** `to_global` rotates each camera's local bearing by its mount azimuth
and namespaces track ids as `camera_index * 1000 + local_id` (id 0 never
latches). `merge_contacts` ranks by collision-then-size and drops any contact
within the dedup angle of an already-kept contact **from a different camera** —
never within one camera, which would erase two people standing together.

**Lens models** matter because bearings drive both the HUD and where a real
spotlight points: `rectilinear` (r = tan θ), `equidistant` (r = θ, the fisheye
default), `equisolid`, and a legacy `linear`.

**Failure behaviour.** A camera that will not open, or starts throwing mid-run,
costs its arc — not the run. If *nothing* opens the runner exits with code 3
rather than broadcasting a confident all-clear while blind. On shutdown it always
emits one final empty frame (an explicit all-clear), parks the light, and zeroes
the universe.

### 6.3 The detector, honestly

`MotionDetector` is OpenCV MOG2 background subtraction: threshold, morphological
open, external contours, area filter, centroid → bearing, bbox height → size,
nearest-centroid track ids. Collision is a constant-bearing-while-growing rule —
azimuth rate below a threshold while size is above a floor and increasing.

A `ReBaselineGuard` drops the whole frame when a thermal flat-field correction
makes the subtractor call everything foreground, and suppresses three frames
after. Reporting nothing is the right failure; reporting a scene full of people
is not.

**There is no trained model, no weights file, and no inference runtime in this
repository.** `FakeDetector` — three deterministic synthetic contacts — is the
default source.

### 6.4 The DMX tracker light

Transport is **not** Art-Net and not direct serial: `OlaDmxSink` HTTP-POSTs a
512-channel frame to a local `olad`, which drives an FTDI FT232 USB-DMX dongle
through the `ftdidmx` plugin. `olad` is pinned to a dedicated Orin core with a
systemd `CPUAffinity` drop-in so vision work cannot jitter DMX timing.

Both sinks hold a persistent 512-slot frame and merge each send into it.
`OlaDmxSink` swallows its own send failures by design — a lighting concern must
never be able to stall the threat broadcaster — but counts them and reports once
per outage. That is exactly why `zdeck`'s BLACKOUT bypasses it.

**Fixture facts, measured on the bench, not from the manual:**

| | Value | Provenance |
|---|---|---|
| Channel mode | 11-channel | measured three independent ways after the fixture arrived in 9-channel |
| Pan range | 540° | manual, confirmed |
| **Tilt range** | **180°** | **measured — the manual says 270° and is wrong** |
| Pan channels | 1 (coarse), 2 (fine) | measured |
| Tilt channels | 3 (coarse), 4 (fine) | measured |
| Dimmer | channel 8 | measured — **not** channel 5, which is the colour wheel |
| Never written | channels 10, 11 | auto programs and mode-select/motor-reset |

The tilt figure is pinned by a test that fails if anyone restores the manual's
270. Aiming maps bearing → pan about a configurable centre, and size (as a range
proxy) → tilt; both `tilt_far_deg` and `tilt_near_deg` are marked in the source
as **uncalibrated placeholders** to be set on the mount before enabling real DMX
output.

Two operational lessons are baked in rather than documented: the fixture silently
discards DMX until its power-up homing sweep completes (indistinguishable from a
severed cable — force a motor reset before diagnosing anything in software), and
its signal-loss behaviour was set in hardware to blackout, because `hold` freezes
a beam that then rakes across bystanders as the vehicle turns.

### 6.5 `zdeck`

A six-key Stream Deck Mini surface, structured like `zvision`: a pure model, the
surface behind a protocol with a fake, and the vendor library isolated in one
file — so CI needs neither the library nor a deck.

Guards that exist because they were needed: `DeckConfig` rejects amber colour
slots and auto-spin wheel values *at construction*; `DeckModel.channels()`
refuses to emit a frame touching a forbidden channel; only a specific
`DeckNotPresent` is retried quietly, so a programming error cannot wear an
unplugged cable's clothes forever.

The runner parks the head on **every** exit path *and* when the deck itself
disappears — while the panel is gone the operator has no kill control, so the
beam must not be left burning.

### 6.6 Services

| Unit | Runs | Notes |
|---|---|---|
| `zvision.service` | `python3 -m zvision $ZVISION_ARGS` | `EnvironmentFile` **without** `-`, so a missing config fails the unit; `StartLimitIntervalSec=0` so repeated crashes never permanently disable it |
| `zodiac-deck.service` | `python -m zdeck` from a separate venv | `After=olad`, deliberately not `After=zvision` |
| `zodiac-track.service` | `python3 -m zvision.tracklog` | NMEA breadcrumb CSV, fsynced per row |
| `zodiac-track-serve.service` | `python3 -m zvision.trackserve` | read-only HTTP on :8087, hardened |

All the DMX-touching units carry `ExecStopPost=-… zvision.dmxpark`.

---

## 7. The seams — wire protocols

Byte-level detail in [`docs/PROTOCOLS.md`](docs/PROTOCOLS.md).

### 7.1 NMEA (`:beacon` → `:app`)

Standard NMEA 0183 framing: `$` + body + `*` + two uppercase hex XOR-checksum
digits + CRLF. Raw GNSS is forwarded verbatim; seven sentence types are
synthesized. All numeric formatting is pinned to `Locale.US`, because a
comma-decimal locale would emit `12,3` and split the field.

### 7.2 `ZTHREAT` (`zvision` → `:app`)

```
ZTHREAT;<id>:<relAzDeg>:<size>:<collision>;...
```

One UDP datagram is one frame; a bare `ZTHREAT` is an explicit all-clear.
Bearings are **full-circle** (±180), because the rig fuses cameras all the way
around the vehicle.

This is a **cross-language contract between two hand-written implementations**,
and neither is authoritative. The shared truth is the measured golden corpus at
[`protocol/threat-protocol-golden.json`](protocol/), which **both** test suites
read and which both fail loudly on rather than skipping if it is missing.

Before that corpus existed the two sides had silently drifted in ten measured
ways — including `0x1p3` in an azimuth field becoming a live contact bearing 8°
on the driver's HUD while the Python side rejected the same frame. Both sides now
check explicit numeric grammars rather than delegating to the host language's
parser, and the Python side rounds parsed values through 32-bit float so both
agree on the clamp boundaries. See [`protocol/README.md`](protocol/README.md).

---

## 8. What is deliberately fake, stubbed, or absent

Documenting this is the point of the section — it is the thing docs usually get
wrong.

| Thing | Reality |
|---|---|
| Vehicle transports (BLE / USB / WiFi) | **Fake.** One `FakeTransportAdapter` implementation, three registered instances. |
| `TelemetryRepository` | **Fake.** Deterministic synthetic thermal/mode/link tick. |
| Vehicle command vocabulary | `SetHeading` / `SetSpeed` only, delivered to a fake. |
| MAP cell gauges and throttle trace | Hard-coded literals in the composable. |
| Jetson detector | Background subtraction. No model, no weights, no inference runtime. |
| Jetson camera ring | Software complete; **no camera has been connected to the box.** |
| Tracker light aim | `tilt_far` / `tilt_near` / `pan_center` are uncalibrated placeholders. |
| DMX arbitration | Unresolved. `zvision --dmx ola` and `zdeck` must not run together. |
| `CockpitUiState.tiltDeg` | Persisted and clamped, but the renderer uses a compile-time 45°. |
| `EgoStyle.HEX` | Implemented, selected by no concept. |
| mDNS service discovery | Not built. |
| Proximity alarm | Not built. |
| Map touch interaction tests | None. |

---

## 9. Known discrepancies

Recorded rather than quietly fixed, because fixing them is a code change.

**Field-of-view reference disagrees across the two sides of the vehicle.**
`jetson/HARDWARE.md` concludes — with a physics argument — that the Lepton Ultra
Wide's quoted 160° is the *diagonal*, giving ±64° horizontal, and the tablet
agrees: `SurroundRing.COVERED_ARCS` is `-64f..64f`. But `zvision`'s `--fov-ref`
defaults to `h`, and every documented example rig omits `fovref=d`, so the Python
side computes bearings as if the camera covered ±80°. **The two sides currently
disagree by up to 16° at the frame edge.** Either the Python default flips or the
tablet's covered arc widens; until then, treat edge bearings as unverified.

**Camp coordinate is estimated, not geocoded.** `Camp.GALACTIC_RELAY` is a
geometric projection of "Heiau & 2:15" onto the 2026 grid. The authoritative
placement comes from the BM API shortly before the event.

**2026 base map has not been checked on-device.** The city has moved; typing a
known address and confirming it lands on the right corner is outstanding.

---

## 10. Environment constraints behind the choices

- **Heat** — dark devices in direct sun throttle or shut down; vented mounts,
  shade, and insulation between roof and device.
- **Dust** — alkaline playa dust penetrates everything. IP-rated devices, sealed
  enclosures, and note that **thermal optics foul and glass blocks LWIR** — a
  germanium or other IR-transmissive window is required, not a glass one.
- **Power** — a moving head, a Jetson, ten tablets and Starlink is a real 12 V
  budget.
- **Vibration** — damped mounts. This is also the camera stabilisation strategy:
  mechanical damping plus a software detection tracker, not video stabilisation.
- **14 days unattended** — nothing may depend on someone noticing a failure.
  Hence: caches in `filesDir` not `cacheDir`, a log that counts its own losses, a
  systemd unit that refuses to start misconfigured, and a service manager that
  never gives up restarting.

---

## 11. Decisions log — the "why"

- **Fleet bus on fixed multicast groups.** Dynamic-DHCP-proof, no hardcoded IPs,
  fully offline, one-to-many by nature. The subnet-broadcast copy exists because
  real access points drop multicast.
- **Broadcast the whole sensor hub, not just GPS.** Compass heading fills the gap
  GPS course leaves at low speed — and an art car spends most of its time barely
  moving. One hub, one rich shared truth.
- **Camera on a dedicated edge box; broadcast detections, not video.** Right
  physical location, isolates sensor failure, tiny payload, and every viewer is a
  first-class subscriber rather than a client of one tablet.
- **Classical CV first, trained model later on public thermal data.** No playa
  data collection and no self-labelling required to ship the safety core.
- **DMX tracker slaved to detections.** Illuminating the pedestrian is both the
  safety function and the spectacle.
- **The demo threat source defaults to nothing.** A fabricated collision on a
  driver's HUD is worse than a blank one; `ABSENT` is a legible state.
- **A card with no data leaves the rotation.** A passenger display cycling
  through blank panels reads as broken hardware.
- **Everything realtime is link-local.** The vehicle stays fully functional with
  the internet down, which on the playa is the normal case.
