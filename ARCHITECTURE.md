# Zodiac Control — System Architecture

The software + hardware design for the Zodiac mutant vehicle: a fleet of Android
tablets showing an 80s green-phosphor cockpit over a live Black Rock City map,
plus a sensor/safety layer (shared GPS, thermal pedestrian detection, a steerable
tracker light) for driving a heavy art car through crowds at night.

> This is the living design reference. For the dated decision log see
> [`SYNC.md`](SYNC.md); for the current public snapshot see [`README.md`](README.md).

**Status legend:** ✅ built · 🚧 in progress · 📋 planned

---

## 1. Design commitments

Two principles shape everything:

- **Offline-first / LAN-local.** Every realtime, safety-relevant data path runs
  on the vehicle's local WiFi with **no dependence on the internet.** Starlink is
  aboard but treated as unreliable; only non-safety extras (BM API discovery
  cache, weather) touch the WAN, and those are cached offline-first.
- **One-to-many by default.** Sensors are shared — *one* GPS, *one* thermal
  camera → *every* tablet. Data is published on the local network so any number
  of displays (driver, passengers, crew) subscribe to what they need.

---

## 2. Hardware — devices & roles

| Device | Role | Compute / OS | Display | Status |
|---|---|---|---|---|
| **Hero dashboard** | Main cockpit — address entry, full map, all concepts | Galaxy Tab S9+ (SM-X810), Android 13/14 | 12.4" OLED | ✅ in use |
| **Driver night display** (×2, 1 spare) | Dim night HUD for the driver — thermal threats + minimal nav | Galaxy A54, OLED | 6.4" OLED | 🚧 HUD built; phones to buy ~2026-07-23 |
| **Fleet tablets** | Passenger / crew displays (RADAR / MAP) | mixed Fire HD 10 / Galaxy Tab | LCD / OLED | ✅ |
| **Vehicle Sensor Hub** | Broadcasts vehicle telemetry — GPS + compass + IMU tilt + speed + **mic/lux/shock/health/odometer** | XCover Pro (SM-G715U) now; Pi + u-blox + IMU later | (screen off) | ✅ Zodiac Beacon app (`:beacon`) — GPS + compass verified; 5 proprietary sensor channels added (§4) |
| **Thermal edge box** | Pedestrian detection at the vehicle front; drives the DMX light | Jetson Orin Nano Super (~$249, 67 TOPS) + FLIR camera | — | 🚧 `zvision` software built + tested (motion-detector bring-up); hardware to bench-flash (`jetson/DEPLOY.md`) |
| **DMX tracker light** | Steerable "we-see-you" spotlight slaved to thermal detections; pulses to `$ZAUD` when idle | moving-head + USB-DMX (in hand) | — | 🚧 `zvision/tracker.py`+`dmx.py`+OLA built + tested; on-vehicle calibration pending |
| **Proximity alarm** | Cheap, robust "something's close in front" hard alarm | ESP32 + 24GHz radar / TF-Luna LiDAR + red beacon | red beacon | 📋 |
| **Network** | WiFi AP + DHCP (single subnet, dynamic) | travel router; Starlink = WAN (non-critical) | — | ✅ |

**Why the roles split this way:**
- The **driver display is OLED** because a mostly-black UI at low brightness
  preserves the driver's dark adaptation; an LCD backlight floods light even
  through "black" pixels and wrecks night vision.
- The **Sensor Hub is a rugged LCD phone** (screen irrelevant — it's headless).
- The **thermal camera lives on a dedicated edge box at the front**, not on the
  GPS phone (wrong physical location, and the phone is busy/modest) and not on
  the driver's tablet (couples the sensor to one display; a mid phone doing
  capture+detect+render+rebroadcast is stretched).

---

## 3. Network & the fleet bus

Single L2 subnet, **dynamic DHCP → no hardcoded IPs anywhere.**

- **Discovery via the transport itself.** One-to-many streams ride **fixed
  multicast groups** — the group address is baked into the app, independent of
  whatever DHCP hands out. A source announces itself simply by transmitting;
  subscribers join the group and listen. No IP config, no handshake. ✅
  *(Telemetry rides `239.7.7.10:10110`, threats `239.7.7.20:10120`; both the
  beacon/Jetson senders and the tablet consumers also send/accept a
  subnet-broadcast copy + hold a `MulticastLock`, for APs that drop multicast.)*
- **mDNS / DNS-SD** (Android `NsdManager`) for richer **service discovery +
  health/status** and any unicast/TCP endpoints. Fully link-local — works with
  no internet. 📋
- **Android broadcast/multicast gotcha (learned & handled).** The WiFi driver
  silently filters broadcast/multicast frames from apps to save power unless the
  app holds a `WifiManager.MulticastLock` (+ `CHANGE_WIFI_MULTICAST_STATE`).
  This is baked into `NetworkLocationSource`. ✅
- Everything above is link-local → **immune to Starlink outages.**

### Channels

| Channel | Source | Consumers | Payload | Status |
|---|---|---|---|---|
| **Vehicle telemetry** | Sensor Hub (`:beacon`) | all tablets | position, GPS speed & course, **compass heading**, IMU tilt, fix quality, sat count, UTC | ✅ position/heading/tilt/health; NMEA on `239.7.7.10:10110` |
| **Sensor channels** | Sensor Hub (`:beacon`) | all tablets | `$ZAUD` mic level, `$ZENV` lux, `$ZSHK` shock-g, `$ZBCN` health, `$ZODO` odometer | ✅ built (tablets auto-dim on `$ZENV`) |
| **Thermal threats** | Jetson edge box | all tablets + DMX light | per-contact bearing, size, collision flag (`ZTHREAT` on `239.7.7.20:10120`) | 🚧 protocol + both ends built; camera/model in progress |
| **Destination / nav** | Hero dashboard | all tablets | active drive-to target | 📋 |
| **Vehicle commands** (future) | TBD | vehicle | heading / speed (transports are fake today) | 📋 |
| **Raw thermal** (optional) | Jetson | opt-in viewers | MJPEG/RTSP thermal video for screens that want the literal heatmap | 📋 |

Port note: NMEA keeps the de-facto **10110**; other channels get their own
groups/ports.

---

## 4. Vehicle Sensor Hub — broadcast everything

The "GPS device" is really a **vehicle sensor hub**: it publishes *all* the
telemetry it can read, not just position.

- **GNSS:** lat/lon, altitude, GPS speed (over ground), GPS course (over ground),
  fix quality, satellite count, UTC time.
- **Compass heading (magnetometer)** — *distinct from GPS course.* GPS course is
  the direction of *motion* and is meaningless when stopped or crawling; the
  magnetometer gives true pointing direction at any speed. **Important for a slow
  art car** that spends a lot of time barely moving.
- **IMU (accel / gyro)** — motion, tilt, vibration/shock. Optional but cheap.

**Rationale:** one device, one broadcast, one rich shared truth for every
display; the compass fills the gap GPS course leaves at low speed.

**Implementation path:**
- Bring-up ✅ — **GPSd Forwarder** app (NMEA GPS only) on the XCover, verified
  end-to-end (its GPS drove a tablet's ego over WiFi).
- **Zodiac Beacon (`:beacon`) ✅** — the custom broadcaster is built: reads GNSS +
  magnetometer + IMU and publishes `$GPHDT` (true heading), `$ZTLM` (tilt+speed),
  and five proprietary sensor channels (`$ZAUD`/`$ZENV`/`$ZSHK`/`$ZBCN`/`$ZODO`)
  on the telemetry group. GPS + compass verified; the sensor channels are newer.
  The production Pi + u-blox is the later hardware swap.
- Tablet side — `NetworkLocationSource` consumes the beacon's NMEA ✅: GPS +
  `$GPHDT` heading + `$ZTLM` tilt, plus the five sensor channels into
  `core/telemetry/*` via its `beaconSensors` flow; `$ZENV` lux auto-dims the
  screen (`ScreenBrightness.luxToBrightness`).

---

## 5. Software — the Android cockpit app

Package `org.pureagave.zodiac.control`. Kotlin + Jetpack Compose. Reactive core:
`CockpitViewModel` subscribes to repository/source flows and exposes a single
`StateFlow<CockpitUiState>` to Compose. Manual DI in `ZodiacApplication`.

- **`CockpitScreen` dispatcher** → concepts (a concept ≈ a device role/mode):
  - **RADAR** — *Aliens* M41A sweep scope. ✅
  - **MAP** — *Alien* Nostromo gauge wall. ✅
  - **DRIVER** — Star-Wars-'83 vector night HUD (see §7). 🚧 built: renders
    threat contacts from `data/vision/NetworkThreatSource` (or the fake) via
    `DriverNightScreen`; waiting on real thermal detections from the Jetson
- **`LocationSource` abstraction** — FAKE / SYSTEM / BLE / USB ✅, **NET** ✅
  (`NetworkLocationSource`, verified end-to-end with the real phone). Routed +
  registry + runtime selector chips (chips auto-populate from the enum). NET also
  parses the beacon's five sensor channels → `beaconSensors` flow (`$ZENV` lux
  auto-dims the screen via `ScreenBrightness`). ✅
- **`ThreatSource` abstraction** — FAKE / NET (`data/vision/*ThreatSource` behind
  `RoutedThreatSource`); consumes `ThreatProtocol` frames from the Jetson threat
  group → `DriverNightScreen`. ✅ (real detections pending the edge box)
- **`TransportAdapter`** (vehicle commands) — all fake today; real BLE/USB/WiFi
  transports are a future milestone. 📋
- **Playa map + navigation** — GeoJSON → binary cache → `PlayaMapRepository`;
  `PlayaProjection` (equirectangular on the Golden Spike, active year
  `GoldenSpike.ACTIVE` = **2026**); `PlayaRoute` street-following routing across
  the BRC polar grid ✅; drive-to targets HOME / MAN / TEMPLE / BATH / ADDR ✅.
- **Playa discovery** — offline-first BM API cache → RADAR contacts / MAP
  markers. ✅
- **OLED burn-in mitigation** — idle state machine + pixel-shift + dim/sleep,
  wrapping every concept. ✅
- **Preferences** — DataStore (GPS source, map mode, concept, tilt, zoom,
  burn-in config). ✅

---

## 6. Sensors & detection

### Thermal pedestrian detection (Jetson + FLIR) 📋

- **Camera:** PureThermal 3 + FLIR Lepton 3.5 (clean UVC + docs) *or* InfiRay
  256×192 (higher res, needs frame-format parsing). USB-UVC. Forward cone
  ~57° HFOV → ~11 m wide at 10 m; a forward cone, not peripheral.
- **Compute:** Jetson Orin Nano Super (67 TOPS — *overkill*; thermal resolution
  and data are the real bottleneck, not compute).
- **v1 — classical CV, zero ML, zero training data:** threshold → blobs →
  track → bearing / size / range-estimate / velocity / collision. Ships the
  safety core with no labeled frames.
- **v2 — ML, still no playa data:** fine-tune a pretrained YOLO on **public
  thermal datasets** (Teledyne FLIR Free ADAS ~26k labeled thermal frames;
  LLVIP; KAIST) on the **H100** — an afternoon. The night playa is an *easier*
  domain (cool background, hot people, low clutter) so it transfers well.
  Optional domain-gap closer: one night of backyard footage on the real camera,
  ~an hour to label in Roboflow/CVAT.
- **Realistically achievable:** reliable person detection ~20–30 m; count
  *separated* people; **bike-vs-walker mostly from velocity** (a bike is
  ~3–5× faster — cheap and robust) + ML class up close. **Limits:** counting a
  tight cluster (blobs merge) and shape-ID at long range — thermal *resolution*,
  not compute or data.
- **Monocular range/depth:** size-based range `D = f·H/h` (humans are a known
  ~1.7 m, ~±25%); **time-to-contact from growth rate** (looming — the best
  collision signal, no absolute range needed); constant-bearing-while-growing =
  collision course. Radar/LiDAR optional later for calibrated range.
- **Output:** broadcast **detections** (small metadata), *not* raw video —
  every device renders its own view. Raw thermal MJPEG/RTSP available as a
  separate opt-in stream for anyone who wants the actual picture.

### Standard vision (RGB) camera — 24-hour capability 📋

A cheap USB/CSI RGB webcam (~$15–40) alongside the thermal, co-boresighted so
their bearings align. It fills exactly the gap thermal has:
- **Daylight** — thermal degrades midday (playa hotter than skin, contrast
  inverts); RGB is great in daylight. Complementary: RGB by day, thermal by
  night, both at dusk → **24-hour coverage.**
- **The ML is *easier*** — RGB person/bike detection is a solved problem
  (COCO-pretrained YOLO, **zero custom training data**).
- **Fusion** — RGB (detail/classification) + thermal (night/heat) is more
  robust than either alone; the Jetson has the inputs (2× CSI + USB) and
  headroom (67 TOPS) to run both and merge their detections onto the bus.

### DMX gimbal tracker light 🚧 (software built)

A steerable follow-spot slaved to the thermal detections — a "we see you"
headlight that spotlights the detected pedestrian. Safety *and* spectacle.

- **Software built** — the `zvision` runner drives the head in-process from the
  same per-frame contact list the HUD broadcaster gets. `zvision/tracker.py` is
  the target-selection + pan/tilt mapping + slew logic; `zvision/dmx.py` is the
  transport (`FakeDmxSink` + `OlaDmxSink` → local `olad` over HTTP). Prove with
  `--dmx fake` (log-only), transmit with `--dmx ola`. OLA bring-up is scripted
  (`jetson/scripts/install-ola.sh`, `jetson/DEPLOY.md §7`). On-vehicle pan
  calibration (`--dmx-pan-center` / `--dmx-pan-gain`) is the remaining step.
- The Jetson already has **bearing (+ rough range)** per contact → map to
  **pan/tilt** (one-time calibration of the light's pan-zero to the vehicle's
  forward axis) → **DMX**.
- **USB-DMX interface:** a genuine FTDI FT232R/RL OLA dongle (~$15–25, `ftdidmx`
  plugin) is the buy; olad is CPU-pinned to a dedicated Orin core so the ML
  workload can't jitter DMX timing. See `jetson/HARDWARE.md` for selection.
- **Behavior:** slew to the nearest / collision-course contact; **when idle
  (no contact) it pulses to the beacon's `$ZAUD` audio** — a sound-reactive
  show (`zvision/audio_bus.py`). `--dmx-no-sound` disables the idle show.
- **Caveats:** finite slew speed (fine at 5 mph, not for sprinters); one target
  at a time → priority logic; moving heads pull real power (100–300 W+) →
  vehicle power budget; dust on the fixture.

### Proximity alarm (cheap, do-first) 📋

ESP32 + 24GHz radar (LD2410 ~$10) or TF-Luna LiDAR (~$20) + red beacon,
~$50–80. An independent hard "something's close in front" alarm that works even
if everything else is down. **Skip ultrasonic parking sensors** — poor on the
dusty, windy, open playa. Red alert reuses the app's reserved red-for-faults.

---

## 7. Night-vision design (driver display)

- **OLED + mostly-black UI at low brightness** preserves scotopic (rod) vision;
  low luminance matters more than color.
- Palette: **dim green + red + purple only — no white, no yellow.**
- **Hollow wireframe** contact figures (filled shapes emit too much light), with
  distance level-of-detail: compact **bust** far, full **walking** figure near.
- **Deep-red lock brackets** on a collision-course contact; the alarm burst is
  the one thing allowed to be bright.
- **Heading arch** tape across the top (on-playa: entrance clock labels;
  in-city: current street/radial). Destination **name** top-center.

---

## 8. Playa environment (the constraints behind the choices)

- **Heat** — dark devices in sun throttle/shut down → vented mounts, shade,
  insulation between roof and device.
- **Dust** — alkaline playa dust gets into everything → IP67 tablets/phones,
  sealed enclosures, and note **thermal optics foul** (LWIR needs a
  Ge/IR-transmissive window; glass blocks it).
- **Power** — moving head + Jetson + tablets + Starlink is a real 12V/mains
  budget.
- **Vibration** — damped mounts (also the thermal-camera stabilization
  strategy: mechanical damping + a software detection tracker, not video
  stabilization).

---

## 9. Cost / BOM (rough, incremental)

| Item | Cost | Notes |
|---|---|---|
| Driver OLED phones (A54 ×2) | ~$300 | 1 spare, provisioned before playa |
| Thermal camera | ~$150–283 | InfiRay 256×192 / PureThermal 3 + Lepton 3.5 |
| Jetson Orin Nano Super | ~$249 | scarce — may pay a markup or wait |
| USB-DMX interface | ~$25–140 | OpenDMX → Enttec Pro |
| Proximity alarm | ~$50–80 | ESP32 + radar/LiDAR + beacon |
| XCover Pro / gimbal light | owned | — |
| Mounts / cabling / power | TBD | — |

---

## 10. Roadmap / status

- ✅ Shared-WiFi GPS (`NET`) verified end-to-end with the real phone.
- ✅ Street-following BRC routing; drive-to; discovery; burn-in; RADAR/MAP.
- ✅ 2026 base-map migration (`GoldenSpike.ACTIVE` = 2026; on-device verify pending).
- ✅ **Telemetry hub** (`:beacon`) — broadcasts compass + IMU tilt + five sensor
  channels (`$ZAUD`/`$ZENV`/`$ZSHK`/`$ZBCN`/`$ZODO`); tablets consume + auto-dim.
- ✅ Fleet bus on **fixed multicast groups** (+ subnet-broadcast fallback). mDNS
  service discovery still 📋.
- 🚧 DRIVER night HUD — renders threat contacts (network/fake source) built; next
  = real on-playa/in-city nav context.
- 🚧 **zvision edge box** — motion-detector bring-up + threat protocol + DMX
  tracker built and tested; hardware bench-flash + trained thermal model next.
- 📋 **Jetson trained thermal detection** (classical CV → public-thermal-data ML).
- 📋 **Proximity alarm.**
- 📋 Real vehicle transports (BLE/USB/WiFi) replacing the fakes.

---

## 11. Decisions log (the "why")

- **Fleet bus = fixed multicast groups + mDNS** — dynamic-DHCP-proof (no
  hardcoded IPs), fully offline, one-to-many by nature.
- **Broadcast full vehicle telemetry, not just GPS** — compass heading fills the
  gap GPS course leaves at low speed; one hub, rich shared truth.
- **FLIR on a dedicated edge box (Jetson); broadcast detections, not video** —
  right physical location (front), isolates sensor failure, every viewer is a
  first-class subscriber, tiny payload.
- **ML: classical CV first, then fine-tune on *public* thermal data** — no playa
  data collection or self-labeling required; H100 makes training trivial.
- **DMX tracker light slaved to detections** — doubles as safety (illuminates
  the pedestrian) and art.
- **Everything realtime is link-local** — the vehicle stays fully functional
  with the internet down.
