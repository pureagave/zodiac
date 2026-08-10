# Devices — what runs where, and why

The fleet, by role. Software facts here are verified against the code; hardware
model numbers, OS versions and on-device behaviour are **operator-recorded** —
they cannot be checked from this repository, and are marked where that matters.

---

## 1. Role map

| Role | Device | Software | Display | Status |
|---|---|---|---|---|
| Hero dashboard | Galaxy Tab S9+ (`SM-X810`) | `:app`, RADAR or MAP | 12.4" OLED | In use |
| Driver night display | Galaxy A54 (`SM-A546V`) | `:app`, DRIVER concept | 6.4" OLED | HUD built; night legibility unverified |
| Passenger / crew displays | Fire HD 10 (`KFTUWI`), Fire HD 8, spare tablets | `:app`, passenger carousel or RADAR/MAP | LCD | In use |
| Sensor hub | XCover Pro (`SM-G715U`) | `:beacon`, headless | screen off | Built; GPS + compass verified |
| Vision edge box | Jetson Orin Nano Super | `zvision` | — | Software built; no camera attached |
| Light control surface | Elgato Stream Deck Mini | `zdeck` | 6 keys | Driven on real hardware |
| Tracker light | DMX moving head + FTDI USB-DMX | `zvision` / `zdeck` via `olad` | — | Characterised; aim uncalibrated |
| Network | travel router (AP + DHCP) | — | — | In use |
| Proximity alarm | ESP32 + 24 GHz radar or LiDAR | — | red beacon | Not built |

---

## 2. Why the roles split this way

**The driver display is OLED, and that is not a preference.** A mostly-black UI
at low brightness preserves scotopic (rod) vision. An LCD backlight floods light
through pixels that are nominally black and wrecks the driver's dark adaptation.
Low luminance matters more than colour choice here.

**The sensor hub is a rugged phone with an irrelevant screen**, because it is
headless — it runs a foreground service with the display off. Ruggedness and
battery matter; the panel does not.

**The camera lives on a dedicated box at the front of the vehicle.** Not on the
GPS phone — wrong physical location, and that phone is already busy. Not on the
driver's tablet — that couples the sensor's life to one display, and a mid-range
phone doing capture, detection, rendering and re-broadcast at once is stretched.
The edge box broadcasts *detections*, so every screen is a first-class subscriber
and the payload stays tiny.

**The Fire HD 10 is the performance floor, but not the visual reference.** It has
the weakest GPU in the fleet and is GC-sensitive, so it is the right device to
profile against. It is *not* sufficient to certify visual parity: it takes a
different graphics precision path than the Samsungs, which shows up in the CRT
halo rendering. Check the look on a Samsung too.

---

## 3. Android devices

### Galaxy Tab S9+ — hero dashboard

`SM-X810`, 12.4" OLED. Operator-recorded: One UI 8 / Android 16 (API 36),
2800×1752 at 340 dpi.

This is the device the burn-in mitigation exists for. All four burn-in phases
have been verified on it.

**Desktop/DeX windowing gotcha.** In desktop mode apps open as freeform windows
with a caption bar and a persistent taskbar overlaying the bottom edge, which
reads as the UI being cut off. A reinstall reverts a maximised app back to
windowed, the taskbar cannot be toggled off over adb, and "Pin this app" is
hidden while desktop mode is active. For a clean fullscreen dashboard, either
screen-pin the app (kiosk) or exit desktop mode. Note `adb exec-out screencap`
captures the app framebuffer *without* the taskbar overlay, so a screenshot
shows true app rendering even when the screen does not.

The app forces edge-to-edge plus immersive mode (status and navigation bars
hidden), which is required because targetSdk 35 enforces edge-to-edge on
Android 15+.

**Wireless adb.** Classic `adb tcpip 5555` does not survive a USB unplug or
reboot — the tablet reverts to USB-only. `adb connect` answering *"Connection
refused"* (a TCP reset, not a timeout) means the tablet is up and on the network
but adbd is not listening; waking the screen does nothing. Re-arm by plugging in
USB once and re-running `adb tcpip 5555`, or use Android's native Wireless
debugging pair-with-code flow, which is stickier.

**Testing discipline:** keep brightness manual and low, and sleep the display
after every screenshot. It is an OLED panel and this project's whole burn-in
effort is about not cooking it.

### Galaxy A54 — driver night display

`SM-A546V`, OLED. Operator-recorded: Android 16.

Runs the DRIVER concept — the mostly-black vector HUD. Same OLED brightness
discipline as the S9+.

**Wireless debugging only** in practice, because a charge-only USB-C cable
enumerates nothing at all on the host (no Samsung device appears in the USB bus
listing) — do not chase adb when that is the situation. Pairing that works:
Developer options → tap the *Wireless debugging row itself*, not the toggle →
Pair device with pairing code → `adb pair <ip>:<port> <code>` with the code as a
command-line argument (piping it to stdin fails with a protocol fault). The pair
port and the connect port are different, and the pairing code is single-use.
After pairing, discover the endpoint with `adb mdns services` and `adb connect`.
A stale unauthorised transport often lingers alongside the good one, so always
target `-s <ip:port>`.

**Outstanding:** nobody has verified that the HUD's dim rim colour and small
hollow blips are legible at night brightness, through dust, by someone actually
driving. One evening on-device at real brightness is the acceptance test.

### Fire HD 10 — passenger display and performance floor

`KFTUWI` (codename "tungsten"). Operator-recorded: Android 11 / Fire OS 8,
API 30, arm64-v8a, 1920×1200 in landscape.

Also a passenger-display target alongside the Fire HD 8 10th gen, which is why
`:app` targets minSdk 28 rather than 30. Older 8" Fires are Fire OS 6/5 (API
25/22) and are **not** supported — check any candidate with
`adb shell getprop ro.build.version.sdk`.

Two Fire-specific behaviours are baked into the code:

- **Burn-in visual modulation is gated off.** `BurnInDeviceProfile` skips the
  brightness breathe and dim layer when `Build.MANUFACTURER` is `Amazon` — an LCD
  cannot burn in, and it is the slowest GPU in the fleet. Pixel-shift and
  backlight stepping still apply everywhere, the latter for power.
- **`adb pull` of the app log does not work.** Fire OS denies shell access to
  `/sdcard/Android/data`, so the rolling log cannot be retrieved that way. The
  hidden bottom-right long-press log viewer exists specifically for this device.

The Fire lockscreen is an advertisement, which is why kiosk provisioning disables
the keyguard.

### XCover Pro — sensor hub

`SM-G715U`. Operator-recorded: Android 10, which is why `:beacon` is minSdk 29.

Runs `:beacon` headless as a foreground service holding a partial wake lock, with
the screen off. It has a debug activity that shows the live status readout, but
that is for bring-up.

Roles it fills: GNSS, magnetometer, IMU, microphone level, ambient light,
shock and its own battery/health telemetry.

**Planned replacement:** a Raspberry Pi Zero 2 W with a u-blox USB GNSS receiver
and a roof antenna. The travel router keeps the AP and DHCP role in both cases.

**Earlier bring-up** used a spare iPhone running GPS2IP, which validated
`NetworkLocationSource` end-to-end before any custom app existed.

---

## 4. The edge box

### Jetson Orin Nano Super

Roof-mounted. Runs `zvision`, `zdeck`, the breadcrumb tracklog service and its
read-only HTTP server. Its four USB-A connectors are a single onboard 4-port hub.

**Do not re-cable the cameras to free a port.** Adding a hub upstream changes
every `by-path` device path in `ZVISION_ARGS` and the cameras stop resolving. The
Stream Deck is HID and is found by vendor:product id, so it is immune to that
trap — which is why it took the fourth port.

Compute is not the bottleneck for this workload; thermal resolution and available
training data are.

### Cameras

Per `jetson/HARDWARE.md` — one forward ultra-wide thermal plus RGB cameras around
the body.

| | Part | Notes |
|---|---|---|
| Thermal | FLIR Lepton Ultra Wide on a PureThermal Mini USB board | **160×120**, 9 fps native, `Y16` raw. The quoted 160° FOV is argued to be the *diagonal* |
| RGB | Arducam day/night IMX462 USB, ~85° horizontal M12 lens | count and final lens FOV still to be decided |

**No camera has been connected to the box yet.** Everything downstream is proved
with `--source fake`.

> **Open discrepancy:** the tablet treats the thermal camera as covering ±64°
> (the diagonal reading), while `zvision`'s `--fov-ref` defaults to horizontal
> and so computes ±80°. The two sides disagree by up to 16° at the frame edge.
> See [`PROTOCOLS.md`](PROTOCOLS.md) §4.

Thermal optics need an **IR-transmissive window** — germanium or similar. Glass
blocks LWIR entirely. Playa dust also fouls the optic, so this is a cleaning item
as well as a materials one.

### DMX tracker light

A moving head driven over DMX512 from an FTDI FT232 USB dongle
(`0403:6001`), through a local `olad` with only the `ftdidmx` plugin enabled and
the daemon pinned to a dedicated CPU core so vision work cannot jitter DMX
timing.

Everything below was **measured on the bench**, and two of the numbers contradict
the fixture manual:

| | Value | Provenance |
|---|---|---|
| Channel mode | 11-channel | the fixture arrived in 9-channel; switched and re-verified three independent ways |
| Pan range | 540° | manual, confirmed |
| **Tilt range** | **180°** | **measured — the manual says 270°** |
| Pan | ch 1 coarse, ch 2 fine | measured |
| Tilt | ch 3 coarse, ch 4 fine | measured |
| Colour wheel | ch 5 | values ≥ 140 trigger auto colour-spin |
| Dimmer | **ch 8** | measured — *not* ch 5 |
| Never written | ch 10, ch 11 | auto programs; mode-select / motor reset |

Two operational lessons that belong at the top of any dead-fixture checklist:

1. **The head silently discards DMX until its power-up homing sweep completes.**
   No movement, no lamp, no error, display sitting normally. It is
   indistinguishable from a severed cable. Force a motor reset before concluding
   anything is wrong in software — this cost an hour of diagnosing a healthy
   software stack.
2. **Signal-loss behaviour was set to blackout in the fixture menu.** As shipped
   it was `auto`, which meant cutting DMX made the head run its internal auto
   program — sweeping, cycling colours, full brightness — until signal returned.
   On a moving vehicle a Jetson reboot would have turned the tracker light into a
   disco. `hold` was rejected too, because a held beam rakes across bystanders as
   the vehicle turns and reads as purposeful.

Beam aim (`tilt_far_deg`, `tilt_near_deg`, `pan_center_deg`, `pan_gain`) is
**uncalibrated** — the shipped values are nominal placeholders and must be set on
the actual mount before enabling real DMX output.

Full detail: [`../jetson/MOVING-HEAD.md`](../jetson/MOVING-HEAD.md).

### Stream Deck Mini

Elgato Stream Deck Mini, USB `0fd9:0063`, firmware `3.03.002` — 6 keys in a 2×3
grid, 80×80 pixel key images. Six functions: BLACKOUT, LAMP, HOME, DIM−, DIM+,
COLOUR. Single page; there is no paging or mode switching.

Found by vendor:product id over HID, so it survives USB re-cabling. Access is
granted by a udev rule putting it in the `plugdev` group.

**Do not run `zvision --dmx ola` and `zdeck` at the same time.** See
[`../jetson/DECK.md`](../jetson/DECK.md) — arbitration is unresolved, and the
tracker would overwrite a BLACKOUT within about 125 ms, making the kill flicker
instead of hold. Today this is safe only because `zvision`'s DMX output defaults
to `none`.

---

## 5. Network

A travel router provides the AP and DHCP on a single /24. Starlink is aboard as
WAN but is treated as non-critical — nothing realtime depends on it.

There are **no hardcoded IPs anywhere.** Both realtime streams ride fixed
multicast groups compiled into every part, with a subnet-directed broadcast copy
for access points that drop multicast. See [`PROTOCOLS.md`](PROTOCOLS.md) §1.

Fleet target is 8–10 tablets in one vehicle. Per-tablet GPS receivers do not
scale for that — Bluetooth GPS pucks cap at roughly five simultaneous clients —
which is the whole reason for a single shared sensor hub.

---

## 6. Environment

Every constraint below is why something in the design looks the way it does.

- **Heat** — dark devices in direct sun throttle or shut down. Vented mounts,
  shade, and insulation between the roof and the device.
- **Dust** — alkaline playa dust penetrates everything. IP-rated devices and
  sealed enclosures; and note the thermal optic fouls.
- **Power** — a moving head (100–300 W+), a Jetson, ten tablets and Starlink is a
  real 12 V budget.
- **Vibration** — damped mounts. This is also the camera stabilisation strategy:
  mechanical damping plus a software detection tracker, not video stabilisation.
- **14 days unattended** — nothing may depend on someone noticing a failure. This
  is why the discovery cache lives in `filesDir` rather than `cacheDir`, why the
  log counts its own losses, why the `zvision` unit refuses to start
  misconfigured, and why nothing gives up restarting.
