# Resilience audit — "does it come back by itself after a power event?"

**2026-08-13.** High-effort agent audit, investigated **live (read-only)** on the
Jetson + grr and against the code. Scope: automatic recovery to full function with
**zero human interaction** after the playa realities — power loss / battery drain,
devices unplugged and replugged, routers and boxes rebooting, cold power-on of the
whole vehicle at once.

**Bottom line:** the two boxes that must survive unattended (beacon, Jetson)
auto-start correctly, and the multicast senders/receivers self-heal well — **but**
(a) the **tablets** — the screens people look at — do **not** relaunch after a
reboot, and (b) the **Jetson vision pipeline is currently stalled** to ~0.25 Hz
emitting false "all-clear," the exact power-on failure we fear.

Tags: **[CODE-NOW]** fixable in the repo today · **[HW-TEST]** only a hardware test
the owner runs settles it · **[OWNER]** physical/provisioning action.

**Owner to-do after a power event *today* (before any fixes):** tap the Zodiac app
on every tablet (P1-2); ensure the beacon has power (P1-3); the Jetson vision needs
P1-1 diagnosed. Everything else comes back on its own.

---

## P1 — fleet-blinding, or needs a human on the playa

### P1-1. zvision stalled — fleet threat HUD frozen at ~0.25 Hz emitting false all-clear — MEASURED
- **Measured now:** `239.7.7.20:10120` delivered **2 ZTHREAT frames in 8 s** (expect
  ~64 at `--hz 8`). `journalctl -u zvision` logs `select() timeout` on the thermal
  (`/dev/video4`, by-path `…2.3…`) **every ~10 s since boot**, unbroken. The frames
  that do egress are 7-byte empty `ZTHREAT` (zero contacts).
- **Root cause — structure (MEASURED in code):** the runner loop is fully
  synchronous — `app.py:385` `detector.detect(t)` → `capture.py:199`
  `self._cap.read()` blocks on OpenCV's ~10 s V4L2 `select()` timeout. **One dead
  camera throttles the entire fleet broadcast.** No per-camera read timeout, no
  threaded capture, no fault isolation.
- **Root cause — the camera (NOT isolable read-only):** the device is **healthy** —
  `v4l2-ctl -d /dev/video4` = `PureThermal (fw v1.3.0)`, offers `Y16 @
  160x120/160x122 9fps`; `dmesg` shows clean enumeration (`1e4e:0100`), **no
  `ENOSPC`**. It opens but STREAMON delivers no frames. The thermal read **worked on
  the bench 2026-08-07**; what changed is the **just-added camera ring on the *same*
  xHCI controller `3610000.usb`** (see `design/jetson-camera-ring-usb.md` — the USB-C
  in host mode is the same controller/480 bus as Type-A). Whether it's shared-HS-bus
  scheduling, a post-reboot PureThermal firmware state, or the by-path-string-vs-int
  open path (`ThermalCamera` gets the by-path symlink string, not an int index —
  untested on real hardware) can't be told apart without stopping the stream.
- **Impact:** every DRIVER HUD flaps LIVE(empty)↔ABSENT (`NetworkThreatSource`
  `staleMs=1500` clears to all-clear between the 8-s-apart frames). **A blind sensor
  reads as a safe sensor.** Collision/brake alerts can never fire (no contacts ever).
- **Fix — two tracks:**
  - **[CODE-NOW]** Fault isolation: bound the per-camera read (OpenCV
    `CAP_PROP_OPEN_TIMEOUT`/`READ_TIMEOUT`, or a reader thread), and when a camera
    goes stale **broadcast that arc as BLIND/unknown, not all-clear** (P2-1). A
    wedged camera must not gate the emit rate to ~0.1 Hz.
  - **[HW-TEST — owner]** Isolate the thermal: `systemctl stop zvision`, then
    `v4l2-ctl -d /dev/video4 --set-fmt-video=width=160,height=120,pixelformat=Y16
    --stream-mmap --stream-count=30`. Streams → camera fine, it's zvision/OpenCV or
    shared-bus. Then re-test with the ring hub **physically unplugged**, and zvision
    pointed at raw `/dev/video4` vs the by-path string. Don't do this while relying
    on the box.

### P1-2. Tablets do NOT relaunch the cockpit after a reboot — MEASURED (code)
The single biggest "requires a human after every power event."
- **Measured:** `app/src/main/AndroidManifest.xml` has **no `RECEIVE_BOOT_COMPLETED`,
  no boot receiver**; `MainActivity` declares only `category.LAUNCHER` — **no
  `category.HOME`/`DEFAULT`**. Lock-task is entered only from `MainActivity.onResume()`
  → `kiosk.engage()` (`MainActivity.kt:47-53`); `docs/KIOSK.md` step 5 says "Launch
  the app." Device-owner lock task persists the *policy* across reboot, but Android
  does **not** auto-launch the app, and it can't be the Home app (no HOME activity).
- **Impact:** on cold power-on / any battery-drain reboot, **every tablet boots to
  the stock Fire/Samsung launcher** (Fire = ad home) until a person finds and taps
  Zodiac. Downstream is fine once launched (`CockpitViewModel.init:151` restores the
  persisted source, default **NET**, and self-heals) — purely a launch gap, but it
  defeats "power it on and it works."
- **Fix [CODE-NOW]:** add a `BOOT_COMPLETED` receiver (+ permission) that starts
  `MainActivity`; for kiosked units add `category.HOME`+`DEFAULT` so it can be set as
  the device-owner Home activity (which *does* auto-launch and re-enters lock task on
  boot). **[HW-TEST]** verify on a provisioned tablet — not exercisable in CI.

### P1-3. Beacon battery = single point of failure for ALL fleet GPS/nav — MEASURED (known)
- The XCover beacon is the fleet's only GNSS. It went flat on the bench 2026-08-11
  running unplugged (`docs/DEVICES.md`, `tasks/open.md`). ~2-4 day battery is margin
  for a failed charger, not an operating mode.
- **Fix [OWNER — hardware]:** wire to permanent vehicle power on a circuit **that does
  not die with the ignition** (most useful parked, engine off, crowd milling). Not
  code-fixable; software warns via `$ZBCN` battery % but only if someone is looking.

---

## P2 — silently degraded

### P2-1. A stalled/blind Jetson is indistinguishable on the wire from a genuine all-clear — MEASURED
`ThreatBroadcaster` emits `format_frame([])` for both "I see nothing" and "I can't
see" (P1-1 emits the latter as the former right now). CLAUDE.md's "an empty-but-live
feed is a real all-clear" is being violated by a *stalled* feed. **[CODE-NOW]:** carry
a per-arc health/blind bit (or a heartbeat / `$ZDMX`-style health line) so the HUD
shows "SENSOR BLIND," not "all clear." Pairs with P1-1.

### P2-2. `olad` has no auto-restart — MEASURED
`systemctl show olad`: **`Restart=no`**, `Type=forking`. Unlike
zvision/zodiac-track (`Restart=always` + `StartLimitIntervalSec=0` correctly in
`[Unit]`), a crashed `olad` **stays dead until reboot**. Latent today (`--dmx none`),
but an un-recovered SPOF for the tracker light the moment DMX is enabled.
**[CODE-NOW/config]:** systemd drop-in `Restart=always` (+ `StartLimitIntervalSec=0`).

### P2-3. Tracker-light fail-safe does NOT cover a wedged-but-streaming Jetson — MEASURED
The three fail-safes (`BLnd=blac`, `dmxpark` `ExecStopPost`, software kill) all assume
the stream *stops*. P1-1 proves the real failure is **wedged-but-still-streaming**
(loop alive at 0.25 Hz). With DMX on, that freezes the beam at its last aim/brightness
and rakes it across people as the vehicle turns — `ExecStopPost` never runs (process
doesn't exit), `BLnd=blac` never triggers (signal never stops). Concrete instance of
why the `tasks/open.md` **hardware kill-switch** P0 is needed. **[OWNER — hardware]:**
inline labelled kill switch within the driver's reach, before enabling `--dmx ola`.

### P2-4. Beacon boot start is not direct-boot-aware — MEASURED (code) / confirmed (device)
`BootReceiver` is not `directBootAware`; `PREF_AUTO_START` is in credential-encrypted
SharedPreferences (`BootReceiver.kt:43-44`). Works **only because the phone has no
secure lock credential** (confirmed 2026-08-13). **If anyone ever sets a PIN**, a 3am
thermal reboot leaves the fleet blind until someone physically unlocks the phone.
**Fix [OWNER/provisioning]:** hard rule "never set a lock credential on the beacon";
optionally **[CODE-NOW]** make the receiver/service `directBootAware` + move the flag
to device-protected storage.

### P2-5. Beacon transmit socket is wildcard-bound — MEASURED (already tracked)
`TelemetryBroadcaster` opens a plain `MulticastSocket()` with no `Network.bindSocket`;
a second route (cellular/VPN/backup-AP) could carry the fleet's only GPS off the
vehicle LAN silently. Already `tasks/open.md:13-35`, deferred to the backup-AP
milestone; mitigation is the XCover's airplane-mode + WiFi-only config. No new action.

---

## P3 — hygiene / latent

### P3-1. Jetson boots with the WRONG clock; journal is volatile — MEASURED
`journalctl --list-boots` shows one boot stamped **`2026-03-24 13:45`** while `uptime`
says minutes — no reliable persistent RTC, boots at a stale epoch until NTP corrects.
Consequences: pre-NTP logs mis-timestamped (wrecks post-power-event forensics); the
journal doesn't persist across reboots (evidence lost on reboot); any wall-clock output
on the box (e.g. `zvision.tracklog` breadcrumbs) is wrong on a cold boot with no
network. **[CODE-NOW/config]:** enable persistent journald; confirm the tracklog uses
GPS/monotonic time, not `time.time()`.

### P3-2. zvision subnet-broadcast target computed once; multicast NIC not pinned — MEASURED
`broadcaster.py:71-72` computes the `x.x.x.255` broadcast target **once** at
construction from `local_ip()`. If zvision starts before the DHCP lease (router-vs-
Jetson cold-boot race — `network-online.target` can be satisfied by link-up, not a
lease), the fallback is stuck at `127.0.0.255` for the process lifetime — dead on any
AP that eats multicast. Also `IP_MULTICAST_IF`/`--iface-ip` unset while the
`l4tbr0`/`usb0` gadget bridge (192.168.55.1) is up → multicast could egress the wrong
NIC. **[CODE-NOW]:** re-derive the broadcast target periodically / after lease; set
`--iface-ip` to the `enP8p1s0` address in `/etc/default/zvision`. (Egress is fine
*now* — frames confirmed leaving via 192.168.86.235 — this is cold-boot insurance.)

### P3-3. The ring is fragile hardware and its identity is port-only — MEASURED
`dmesg` shows both ring Arducams **resetting** (hub-behind-hub instability: USB-C →
VIA `2109:2824` → Genesys `05e3:0610` → 2× Arducam). Both report `SerialNumber:
SN0001`, so they're distinguishable **only by port** — a replug into different ports
silently rotates a camera's contact set onto the wrong bearing, and fails silently.
The `tasks/open.md` "USB device identity" backlog (label cables, udev names, startup
identity check asserting distinct devices + expected model) should land **before** the
ring enters `ZVISION_ARGS`. Config today lists **only** the thermal — the 4 ring
cameras aren't consumed yet, so the surround ring is not actually active.

### P3-4. Jetson is DHCP + hostname doesn't resolve — MEASURED
`192.168.86.235/24 … dynamic`; `getent hosts zvision` → `127.0.1.1` (local only). The
fleet bus is unaffected (fixed multicast group + fixed `/24` broadcast), but the
management IP and the `zodiac-track-serve` HTTP server can move after a router reboot,
and nothing reaches the box by name. **[OWNER]:** DHCP reservation for the Jetson MAC
on the travel router.

### P3-5. grr validation still unrun — MEASURED (known)
`grr` up 10 days; `rescue.service` present but **inactive/dead**. The rescue-link /
reboot-`fsck.repair` / HDMI-capture validation is still **UNRUN**. grr isn't on the
vehicle power-on path, but the rescue path being unverified means a corrupted-boot
Jetson may not be field-recoverable as assumed. **[HW-TEST — owner]:** run the three.

---

## Genuinely resilient — verified, no action

- **NET GPS receiver** (`NetworkLocationSource`) — CLAUDE.md's claim is **true in
  code**: retries forever with capped backoff, rebuilds the socket + re-joins IGMP
  after 20 s silence (`REJOIN_SILENT_MS`), survives a router reboot.
- **Threat receiver** (`NetworkThreatSource`) — same hardening (rebuild + rejoin at
  10 s, watchdog clears to all-clear at 1.5 s). (Its consumer problem is the
  Jetson-side stall P1-1, not this class.)
- **Beacon** — real power-on appliance: `BootReceiver` (BOOT_COMPLETED +
  MY_PACKAGE_REPLACED, auto-start default true), foreground service, `WAKE_LOCK` +
  battery-opt exemption, transmit socket + DHCP targets re-derived each tick. Robust
  to router reboot and to starting before the network.
- **Jetson services** — `zvision`, `zodiac-track`, `zodiac-track-serve` all `enabled`,
  `Restart=always`, `StartLimitIntervalSec=0` in `[Unit]` → no start-limit wedge under
  power flapping. Only `olad` is the exception (P2-2).
- **Once launched, the app self-heals** — persisted source (default NET) restarts on
  init; no hard ordering dependency on network-up. The whole cold-boot ordering problem
  is absorbed by retry-everywhere **except** the tablet-launch gap (P1-2).
