# Jetson camera-ring USB & bandwidth plan

**Status:** design plan. Measured on the live Jetson 2026-08-11; the **USB-C
host-mode test was run and the topology re-measured 2026-08-13 with the ring
actually attached** — see the correction box below, which **overturns this doc's
original "two independent budgets" premise.**
**Scope:** how to physically attach the target ring — **4 optical + 1 IR
camera** — to the Orin Nano Super without running out of USB bandwidth or
scrambling the per-camera `by-path` identities the rig depends on.

> **Measure, don't guess.** Every "measured" claim came from `lsusb -t`,
> `/sys/kernel/debug/usb/devices`, `v4l2-ctl --list-formats`, and — 2026-08-13 —
> `lsusb -t` with the ring physically attached in host mode. The 480 Mbps /
> 5 Gbps / 10 Gbps ceilings are USB **spec** figures, marked as such.

> ## ⚠️ CORRECTION 2026-08-13 — the USB-C is NOT a second independent budget
>
> This doc originally said the USB-C is a *separate host controller*
> (`3550000.usb`) with its *own independent 480 Mbps budget*. **Measured with the
> ring attached, that is false.**
>
> - `3550000.usb` (tegra-xudc) is the **device-only** controller — it exists only
>   in gadget/flash mode (it's what presented `192.168.55.1` to grr). It plays
>   **no part in host mode.**
> - When the Type-C role switch is flipped to **host**, the port is served by
>   **`3610000.usb` — the *same* xHCI host controller as the four Type-A ports.**
>   In `lsusb -t` the ring's hub appears as **Bus 001 Port 001**, a sibling of the
>   Type-A hub on **Bus 001 Port 002**: **one root hub, one shared 480 Mbps
>   High-Speed bus.**
>
> Consequences:
> - There is **one** HS budget (480 Mbps, Bus 001) and **one** SS budget (10 Gbps,
>   Bus 002) for the whole box. Moving cameras to the USB-C adds **zero** HS
>   bandwidth — every HS optical camera contends for the same 480, whichever port.
> - The **only** way past 480 is genuine SuperSpeed devices on Bus 002 (a USB-3
>   hub + USB-3 UVC cameras). Today's ring is a USB-2 hub + HS Arducams, so it
>   sits on the 480 bus. **SuperSpeed-through-the-Type-C-in-host is still
>   unproven** — the test hardware couldn't exercise it.
> - The one benefit the doc predicted **does still hold**: the ring's `by-path` is
>   `3610000.usb-usb-0:1.4.x` (root **port 1** = the USB-C) while Type-A stays
>   `…:2.x` (root **port 2**), so attaching the ring **did not renumber** the
>   Type-A cameras. Distinct root-port *branches* of the same hub — the reason is
>   "different branch," not "different controller."
>
> **Reboot survival (measured 2026-08-13):** `role=host` **survives a reboot**
> with **no** udev rule / systemd unit / extlinux token setting it — so it is
> re-derived from Type-C CC detection each boot and holds as long as the hub stays
> cabled. No persistence config is needed. All four cameras and all services
> returned on their own.
>
> **Separate issue found in the same reboot (NOT a USB-bandwidth problem):**
> zvision came back "active" but logs a repeating `select() timeout` on its only
> camera, the **thermal** (`…2.3…`) — likely emitting no threat frames. Tracked in
> `tasks/open.md`; under investigation whether reboot-induced or pre-existing.

## The problem

The ring needs five cameras. The Orin Nano dev kit exposes four USB-A ports and
one USB-C, and **the four USB-A ports are already full** (optical + thermal + DMX
+ Stream Deck). So the ring's expansion has to go somewhere deliberate, and the
choice interacts with (a) USB bandwidth and (b) the `by-path` port-pinning the
rig uses for camera→bearing identity.

## Measured topology — one host controller, one HS bus, one SS bus

The single most important fact, **corrected 2026-08-13**: **there is one xHCI
host controller (`3610000.usb`) on this board.** Both the four Type-A ports and
the Type-C port (when in host mode) hang off it and share its single 480 Mbps
High-Speed bus (Bus 001) and single 10 Gbps SuperSpeed bus (Bus 002). Which port
a device uses does **not** change its bandwidth budget.

- **The four Type-A ports are ONE onboard hub** on `3610000.usb`: a "4-Port
  USB 2.0 Hub" (Bus 001, **480 Mbps HS**) and a "4-Port USB 3.0 Hub" (Bus 002,
  **10 Gbps SS**), same silicon.
- **The Type-C port is dual-role.** In **device** mode it is served by
  `3550000.usb` (tegra-xudc) and presents the `192.168.55.1` flash gadget. In
  **host** mode (role switch `usb2-0-role-switch = host`) it is served by
  **`3610000.usb` — the same host controller as Type-A** — and appears as another
  root-hub port on Bus 001. It is **not** an independent budget. Freed from grr
  2026-08-11; role verified to survive a reboot 2026-08-13.
- **The onboard Type-A hub is FULL:** optical (port 1), FTDI/DMX (port 2),
  PureThermal (port 3), Stream Deck (port 4). The USB-C is the only free
  expansion *port* — it just is not a free *bandwidth* budget.

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

**4 optical + IR totals, against the single 480 Mbps HS bus:**
- **Compressed (MJPEG 720p @ 8): ~50 Mbps** — fits the one 480 Mbps bus with ~8×
  headroom. Bandwidth is a non-issue.
- **Raw (YUYV 720p @ 8): ~470 Mbps** — at the 480 ceiling; usable HS is only
  ~320–400 Mbps, so it **will not fit.** There is no second HS bus to split onto.

## The rule

The decision tree is **compressed vs raw**, against a **single** 480 Mbps HS bus
(corrected 2026-08-13 — there is no second HS budget to split onto):

1. **MJPEG / H.264 @ 8 Hz** → the ring's *data* is only ~50 Mbps, but see the
   measured box below: the real limit is **isochronous bandwidth *reservation***,
   not data, and it caps the count at each resolution well below what the data
   rate implies. The real constraints become **resolution × camera-count**,
   **physical ports**, **hub tiers**, and **decode CPU**.
2. **Raw YUYV @ 8 Hz** → ~470 Mbps will not fit the single 480 bus. Splitting
   Type-A vs USB-C-in-host does **not** help — it is the same bus. Drop to 480p
   (39 Mbps/cam → all five fit) or go SuperSpeed.
3. **If still choosing cameras** → buy **USB-3 (SuperSpeed) UVC**; they land on
   the 10 Gbps SS bus (Bus 002) and the 480 bottleneck disappears even raw. (Still
   one *shared* 10 Gbps bus, not per-port — but 10 Gbps shared is ample.)

## ⚠️ MEASURED 2026-08-17 — the limit is iso *reservation*, not data bandwidth

Ran the real four-camera simultaneous-stream test (4× Arducam B0590, MJPEG, on
the powered hub on the USB-C). The theoretical "~50 Mbps, 8× headroom" above is
true for *data* and **misleading for capacity**: a UVC camera reserves USB
isochronous bandwidth by its descriptor's worst case, **not** its actual MJPEG
payload, so a handful of cameras exhaust the bus's iso budget (≈80% of 480 Mbps)
and further `VIDIOC_STREAMON` calls fail with `-ENOSPC` — *fail to start, do not
degrade*. The `uvcvideo` `FIX_BANDWIDTH` quirk (part of this L4T kernel's default
`quirks=0xFFFFFFFF`) is already active and is what makes any multi-cam work at all.

Measured, all four streaming at once (30 fps, the only interval these offer):

| Config | Result |
|---|---|
| **4 × 640×480 MJPEG** | ✅ all four ~28 fps, 0 bandwidth errors |
| **2 × 1280×720 + 2 × 640×480 MJPEG** | ✅ all four ~28 fps, 0 errors |
| **3 × 1280×720 + 1 × 640×480** | ❌ only **2** of the 720p streams start; the 3rd `-ENOSPC` |
| **4 × 1280×720 MJPEG** | ❌ only 2 start; the rest `-ENOSPC` / "Not enough bandwidth for altsetting" |
| 4 × 1080p | won't fit (larger than 720p) |

**Exactly two cameras can run 720p at once — and no physical arrangement changes
that.** Tested (2026-08-17) with all four on the USB-C hub, split across the hub's
5 G/10 G sections, and **split across the two xHCI *root ports*** (2 on the Type-A
onboard hub `1-2`, 2 on the USB-C powered hub `1-1`). All three give the identical
2×720p ceiling. So the isochronous budget is **shared across the whole
`3610000.usb` controller (Bus 001)**, *not* per-root-port — moving cameras to the
Jetson's own Type-A ports does **not** add a second budget. The only escape is
USB-3 cameras on Bus 002.

MJPEG frames validated as clean JPEGs (SOI/EOI intact) — the broad quirks setting
is not corrupting framing. So:

- **Force MJPEG explicitly** (`fourcc=MJPG`). The B0590 lists **YUYV first**, so
  OpenCV's default negotiation picks raw YUYV — which won't fit even a couple of
  cameras. This is the single most important camera setting.
- **Resolution is the capacity knob.** For a four-camera ring on this one 480 bus:
  **640×480 on all four**, or **720p on the 1–2 forward (collision-critical)
  cameras + 640×480 on the side/rear** — both measured to fit. Do **not** ask for
  720p on all four.
- **Final resolution is a detector-tuning decision** (people at 5/10/20 m, day and
  night — a blocked hardware task). 640–720 is the practical envelope here; more
  range/resolution across all cameras needs **USB-3 (SuperSpeed) cameras** on
  Bus 002, which these USB-2 B0590s cannot use.
- The camera streams 30 fps; the pipeline samples at its own `--hz` (8–10). The
  bus carries the full 30 fps regardless — that is what the reservations above
  reflect.
- If a future kernel changes the `uvcvideo` default away from including
  `FIX_BANDWIDTH`, pin `options uvcvideo quirks=128` in `/etc/modprobe.d/` (the
  current all-ones default is blunt but functional and streams clean frames).

## Recommendation

Ring behind **one powered hub on the Type-C port (host mode)**, streaming
**MJPEG** (forced `fourcc=MJPG`), each camera pinned by `by-path`. Resolution per
the measured table above — **not** 720p on all four.

- **Powered hub is required** — the port cannot source VBUS for four cameras.
- **Prefer the Type-C for identity stability, not bandwidth.** A hub on the
  Type-C lands on root **port 1** (`3610000.usb-usb-0:1.x`) while Type-A is root
  **port 2** (`…:2.x`), so adding it **does not renumber** the existing Type-A
  cameras — verified 2026-08-13. (Adding a hub *upstream of the Type-A side* would
  rewrite every `2.x` path — that is the trap this sidesteps.) Bandwidth is the
  same 480 either way.
- **Keep hub tiers shallow.** The ring as tested is Type-C → VIA USB-2 hub →
  Genesys sub-hub → 2 Arducams (`by-path …1.4.3` / `…1.4.4`). Two cascaded hubs
  work but add a re-enumeration failure point in the dark; a single powered hub is
  better.

## Failure modes & gotchas

- **Oversubscription is a hard stop, not graceful degradation.** An isochronous
  UVC stream that can't fit **fails to START** (`-ENOSPC`, "no bandwidth"). Test
  the **whole ring streaming simultaneously**, never one camera at a time, and
  watch `dmesg`. This matters more now that everything shares one 480 bus.
- **Identical Arducams share the firmware serial `SN0001`** — two of the same
  model can be told apart **only by port** (`by-path`), never by serial/`by-id`.
  Confirmed 2026-08-13: both ring cameras report `SN0001`. Port pinning is the
  only camera→bearing identity the hardware offers; a re-plug into a different
  port silently rotates that camera's whole contact set onto the wrong bearing.
- **The thermal camera is the fragile one.** It offers `Y16`/`UYVY`/`GREY`/… and
  OpenCV's default format negotiation can leave it in `select() timeout` with no
  frames (observed post-reboot 2026-08-13). Pin its format/resolution explicitly
  rather than trusting the default. Tracked in `tasks/open.md`.
- **Compression trades USB for CPU.** MJPEG/H.264 save the bus but cost decode on
  the Orin (H.264 can use the HW decoder; MJPEG is cheap on CPU). At 8 Hz +
  background subtraction, either is fine — MJPEG is the usual sweet spot.
- **Level-mount still applies.** Bandwidth doesn't change the `CameraMount`
  level-mount requirement (≤5°, gated by `validate_mount`) or the per-camera `az`
  calibration against the vehicle nose — see `HARDWARE.md`.

## Open / scheduled

- [x] **2026-08-13 — USB-C host-mode test DONE.** Role flips to `host`; a powered
      hub + two Arducam B0590s enumerate and stream (images captured). Enumerated
      at **480M HS** (the hub + cameras are USB-2). Role **survives a reboot** with
      no persistence config. `by-path` `…1.4.3` / `…1.4.4`, distinct from Type-A,
      **no renumbering**. **Topology premise corrected — see the box up top.**
- [ ] **SuperSpeed-through-the-Type-C-in-host is still UNPROVEN** — the 480M test
      couldn't exercise it. Only a **USB-3 hub + a USB-3 camera** on the Type-C,
      confirming a SuperSpeed companion appears on **Bus 002**, settles it. Needed
      only if the ring ever wants raw/headroom beyond the shared 480.
- [ ] **Thermal `select() timeout` after reboot** — zvision's only camera came up
      blind post-reboot; determine root cause (format negotiation vs enumeration
      order vs the added hub load) and pin the fix. Tracked in `tasks/open.md`.
- [ ] Decide final optical count + lens FOV to close the ring (the UW already
      covers the forward 160°; RGB owns the sides/rear).
- [ ] Calibrate each camera's `az` once mounted.
- [ ] Note: once the USB-C carries cameras it is **no longer the flash/recovery
      path** — reconnect to grr and switch back to device mode to reflash L4T.
      (Confirmed 2026-08-13: in host mode the `192.168.55.1` gadget to grr is gone.)

## Measured vs datasheet — explicit

- **Measured 2026-08-11:** optical = USB 2.0; IR = 12 Mbps full-speed; the four
  Type-A ports are one shared hub on `3610000.usb`; USB-C is a separate dual-role
  controller `3550000.usb` **in device mode**; onboard hub is full; current
  periodic load ~0%; Arducam offers MJPG/H264/YUYV.
- **Measured 2026-08-13 (ring attached, host mode):** the Type-C **in host mode**
  is served by **`3610000.usb`** (same controller and HS bus as Type-A), **not**
  `3550000.usb` — so it is **not** a second independent budget; `role=host`
  survives a reboot with no persistence; ring cameras enumerate HS on Bus 001 as
  `…1.4.3` / `…1.4.4`; two same-model Arducams both report serial `SN0001`.
- **Still datasheet / unproven:** SuperSpeed **host** capability of the Type-C on
  this unit — the 480M test could not exercise it.
