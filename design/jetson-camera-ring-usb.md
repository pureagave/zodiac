# Jetson camera-ring USB & bandwidth plan

**Status:** design plan. Measured on the live Jetson 2026-08-11; host-mode test
of the USB-C scheduled 2026-08-12.
**Scope:** how to physically attach the target ring — **4 optical + 1 IR
camera** — to the Orin Nano Super without running out of USB bandwidth or
scrambling the per-camera `by-path` identities the rig depends on.

> **Measure, don't guess.** Every "measured" claim below came from `lsusb -t`,
> `/sys/kernel/debug/usb/devices`, and `v4l2-ctl --list-formats` on the running
> box. The 480 Mbps / 5 Gbps / 10 Gbps ceilings are USB **spec** figures, marked
> as such. The USB-C's SuperSpeed-in-host capability is dev-kit spec and is the
> thing the 2026-08-12 test exists to confirm on this unit.

## The problem

The ring needs five cameras. The Orin Nano dev kit exposes four USB-A ports and
one USB-C, and **the four USB-A ports are already full** (optical + thermal + DMX
+ Stream Deck). So the ring's expansion has to go somewhere deliberate, and the
choice interacts with (a) USB bandwidth and (b) the `by-path` port-pinning the
rig uses for camera→bearing identity.

## Measured topology — two independent host budgets

The single most important fact: **which host controller a device hangs off
decides its bandwidth budget, not which port.**

- **The four Type-A ports are ONE onboard hub** on controller `3610000.usb`:
  a "4-Port USB 2.0 Hub" (bus 1, **480 Mbps HS**) and a "4-Port USB 3.0 Hub"
  (bus 2, **10 Gbps SS**), same silicon. All four Type-A ports therefore **share
  one 480 Mbps High-Speed lane** and one 10 Gbps SuperSpeed lane. Spreading
  USB-2 cameras across the four Type-A ports adds **zero** bandwidth — identical
  upstream pipe.
- **The USB-C is a SEPARATE controller**, `3550000.usb` (tegra-xudc), carrying
  its **own independent 480 Mbps HS + SuperSpeed budget**. It is the only way to
  add a second USB-2 budget. It is a **dual-role** port (role switch
  `usb2-0-role-switch`, currently `role=device` — that is why it presented the
  `usb0`/`192.168.55.1` gadget to the flash host `grr`). Freed from grr
  2026-08-11.
- **The onboard Type-A hub is FULL:** Arducam optical (port 1), FTDI/DMX
  (port 2), PureThermal (port 3), **Stream Deck (port 4)**. The Stream Deck took
  the port earlier notes called "1 free," so the USB-C is now the real expansion
  port.

## Camera inventory & bandwidth

Measured device facts:

| Device | Bus speed | Formats offered | Notes |
|---|---|---|---|
| **Optical (Arducam)** | **USB 2.0 (480M)** | `MJPG`, `H264`, `YUYV` | onboard compression available |
| **IR (PureThermal / Lepton)** | **12 Mbps full-speed** | `Y16`/`GREY`/`UYVY`/… | 160×120 → ~3 Mbps; negligible, put it anywhere |

Pipeline runs `--hz 8` (8 fps), so per-camera load is low. Bitrate per **optical**
camera at 8 Hz:

| Format | 640×480 | 1280×720 | 1920×1080 |
|---|---|---|---|
| **YUYV** (raw, 2 B/px) | 39 Mbps | 118 Mbps | 265 Mbps |
| **MJPEG** (~10:1) | ~5 Mbps | ~12 Mbps | ~25 Mbps |
| **H.264** (~40:1) | ~2 Mbps | ~4 Mbps | ~6 Mbps |

**4 optical + IR totals:**
- **Compressed (MJPEG 720p @ 8): ~50 Mbps** — fits behind *one* 480 Mbps lane
  with ~8× headroom. Bandwidth is a non-issue.
- **Raw (YUYV 720p @ 8): ~470 Mbps** — at the 480 ceiling; usable HS is only
  ~320–400 Mbps, so it **will not all fit on one bus.**

## The rule

The decision tree is **compressed vs raw**, not port count:

1. **MJPEG / H.264 @ 8 Hz** → the whole ring is ~50 Mbps; put all five behind
   **one** controller and forget bandwidth. Real constraints become **physical
   ports** and **decode CPU**.
2. **Raw YUYV @ 8 Hz** → cap **~2× 720p per 480 Mbps controller**; split 2 optical
   on the Type-A hub + 2 optical on the USB-C, IR anywhere. Or drop to 480p
   (39 Mbps/cam) and all five fit one bus.
3. **If still choosing cameras** → buy **USB-3 (SuperSpeed) UVC**; they land on
   the idle **10 Gbps** SS lane and the 480 bottleneck disappears even raw.

## Recommendation

Ring behind **one powered hub on the freed USB-C**, streaming **MJPEG @ 8 Hz**,
each camera pinned by `by-path`.

- **Powered hub is required** — the port cannot source VBUS for four cameras.
- A hub on the USB-C gets its own `3550000.usb-…` `by-path` sub-chain, so adding
  it **does not renumber** the existing Type-A cameras — this sidesteps the
  identity-churn trap documented in `tasks/open.md` (§ USB device identity: an
  upstream hub on the Type-A side rewrites every `2.x` path). This is the whole
  reason to prefer the USB-C over expanding the Type-A side.
- Keeps the Type-A hub for DMX + Stream Deck, and keeps the ring on a dedicated
  480 Mbps controller (~50 Mbps used).

## Failure modes & gotchas

- **Oversubscription is a hard stop, not graceful degradation.** An isochronous
  UVC stream that can't fit **fails to START** (`-ENOSPC`, "no bandwidth"). Test
  the **whole ring streaming simultaneously**, never one camera at a time, and
  watch `dmesg`.
- **Compression trades USB for CPU.** MJPEG/H.264 save the bus but cost decode on
  the Orin (H.264 can use the HW decoder; MJPEG is cheap on CPU). At 8 Hz +
  background subtraction, either is fine — MJPEG is the usual sweet spot.
- **Level-mount still applies.** Bandwidth doesn't change the `CameraMount`
  level-mount requirement (≤2°, no tilt term) or the per-camera `az` calibration
  against the vehicle nose — see `HARDWARE.md` and the surround-vision items.

## Open / scheduled

- [ ] **2026-08-12 — USB-C host-mode test.** Switch `usb2-0-role-switch` to
      `host`, plug a **powered hub + one camera**, confirm it enumerates
      **SuperSpeed**, capture the stable `by-path`, and verify the role **survives
      a reboot** (make it persistent in the device tree if it does not). Does not
      touch running zvision (different controller) or LAN access (Ethernet is
      separate).
- [ ] Decide final optical count + lens FOV to close the ring (the UW already
      covers the forward 160°; RGB owns the sides/rear).
- [ ] Calibrate each camera's `az` once mounted.
- [ ] Note: once the USB-C carries cameras it is **no longer the flash/recovery
      path** — reconnect to grr and switch back to device mode to reflash L4T.

## Measured vs datasheet — explicit

- **Measured 2026-08-11:** optical = USB 2.0; IR = 12 Mbps full-speed; the four
  Type-A ports are one shared hub on `3610000.usb`; USB-C is a separate dual-role
  controller `3550000.usb`; onboard hub is full; current periodic load ~0%;
  Arducam offers MJPG/H264/YUYV.
- **Datasheet / USB spec:** 480 Mbps HS, 5 Gbps SS Gen1, 10 Gbps SS Gen2
  ceilings; the USB-C's SuperSpeed **host** capability on this dev kit — to be
  confirmed by the 2026-08-12 test.
