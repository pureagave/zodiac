# Jetson edge-box bring-up

End-to-end guide to take a Jetson Orin Nano Super from box to broadcasting live
threats onto the vehicle bus. Steps 1–3 you do once; step 4 onward is where we
plug the camera in together.

The design goal: **every stage is verifiable on its own.** You prove the network
path with `--source fake` before a camera is ever attached, so if something
breaks later you know it's the camera/model, not the bus.

## Bring-up checklist (day the NVMe arrives)

Each box is proven before the next; each maps to a section below.

- [ ] **Flash** JetPack 7.2 / L4T 39.2 to the NVMe from the `grr` Linux host, first boot, confirm MAXN Super — §1
- [ ] **Network**: Ethernet → travel router; note the Jetson's IP (`--iface-ip`) — §2
- [ ] **Install**: `sudo jetson/scripts/install.sh` (zvision service + config) — §3
- [ ] **Prove the bus, no camera**: `python3 -m zvision --source fake -v` → tablet DRIVER HUD shows the moving demo — §4 ✅ this is the big one
- [ ] **Attach camera(s)**: `v4l2-ctl --list-devices`, then `--source thermal` and walk in front of it — §5
- [ ] **Make permanent**: set the real source in `/etc/default/zvision`, `systemctl restart zvision` — §6
- [ ] *(optional, any time)* **DMX light**: `sudo jetson/scripts/install-ola.sh`, patch the dongle, `--dmx ola` — §7

Before you start, run the read-only pre-flight on the flash host:
`jetson/scripts/preflight-flash.sh` checks the BSP staging, the host tools, the
`qemu-aarch64-static` symlink, the baked-in user, the t234 flash XML and free
space, and `--command` prints the exact flash command. It changes nothing.

Prereqs already in hand: Jetson kit + PSU, Lepton+PureThermal, RGB cam, DMX dongle+cables+head, router. Still needed before a *field* install (not for bench bring-up): germanium thermal window, vents, vibration pads, short USB cables.

**Software status, honestly:** the bus, the rig, the broadcaster and the tracker
are built and tested and the box can boot straight into them. Two things are
*not* finished and are not software bugs: the tracker light's aim constants
(`tilt_far_deg` / `tilt_near_deg` / `pan_center_deg`) are **uncalibrated
placeholders** that must be set on the actual mount before enabling `--dmx ola`,
and DMX arbitration between `zvision` and `zodiac-deck` is **unresolved** — see
[DECK.md §3](DECK.md), do not run both with real DMX output.

---

## 0. What you're building

```
 Lepton 3.5 (thermal) ─┐
                       ├─UVC /dev/videoN─▶  Jetson: zvision  ─▶ ZTHREAT/UDP ─▶ tablets' DRIVER HUD
 USB webcam (daytime) ─┘                    (this repo)          239.7.7.20:10120
```

The Jetson only *produces* threats. The tablets already *consume* them
(`NetworkThreatSource`), so once frames hit the wire the HUD lights up with no
tablet-side change.

It has a second, optional output: a **DMX moving-head "tracker" light** (§7) that
points at the same detected contacts and — when there's nothing to follow — pulses
to the music from the beacon's `$ZAUD`. It's independent of the camera, so you can
bring it up separately.

---

## 1. Flash JetPack

**Not SDK Manager.** It's a GUI app (useless over SSH) and its supported hosts
are Ubuntu 20.04/22.04 — our flash host runs 26.04. We use the Jetson Linux
BSP's own CLI flasher instead, which SDK Manager only wraps: no GUI, no NVIDIA
login, headless, and it's the documented path for external NVMe.

**Flash host: `grr`** (`ssh grr`, Tailscale) — x86 Ubuntu 26.04. The Mac cannot
do this; Tegra recovery is a USB protocol and needs an x86 Linux host.
Staged at `~/jetson/Linux_for_Tegra` (L4T 39.2, `apply_binaries.sh` already run).

**Why JetPack 7.2 / L4T 39.2** (not the 6.x this doc used to say): r39.2 added
Jetson Orin Nano Dev Kit support, and its newer toolchain is *more* tolerant of
a new host than 6.x was — which is what made a 26.04 host viable at all.

### Host setup (one time, done on `grr` 2026-08-03/06)

```bash
sudo apt-get install -y lbzip2 libxml2-utils sshpass abootimg \
                        device-tree-compiler qemu-user qemu-user-binfmt \
                        xmlstarlet nfs-kernel-server
sudo ln -sf /usr/bin/qemu-aarch64 /usr/bin/qemu-aarch64-static
```

> **`nfs-kernel-server` is required, not optional.** `l4t_initrd_flash.sh` boots
> a small initrd on the Jetson and serves the rootfs to it over the USB link via
> NFS, so the flash aborts immediately without it. (An earlier note here said it
> was optional, on the strength of `exportfs` being guarded by `command -v` in
> one code path — that guard is real and the requirement is still hard.) If the
> flash host is also doing something else in production, disable the service
> once you're done: `sudo systemctl disable --now nfs-kernel-server`.

> **Modern OpenSSH breaks the recovery-image build.** L4T's
> `tools/ota_tools/version_upgrade/ota_make_recovery_img_dtb.sh` calls
> `ssh-keygen -t dsa`, and OpenSSH ≥ 9.8 removed DSA entirely
> (`unknown key type dsa`). The failure lands on a bare `check_error`, so all
> you see is `command is failed` / `Error: failed to generate images` with no
> hint. Comment that one line out — the initrd's sshd config only ever
> references rsa/ecdsa/ed25519, so the DSA key it generates is never used:
> ```bash
> cd Linux_for_Tegra/tools/ota_tools/version_upgrade
> sudo cp ota_make_recovery_img_dtb.sh ota_make_recovery_img_dtb.sh.orig
> sudo sed -i 's|^\(\s*\)ssh-keygen -t dsa |\1# ssh-keygen -t dsa |' ota_make_recovery_img_dtb.sh
> ```

> **Gotcha:** on 26.04 `qemu-user-static` no longer exists — it's `qemu-user`
> (still static-pie linked). L4T's scripts hard-code the `-static` name, hence
> the symlink. Also: apt aborts the *whole* transaction on one bad package name,
> so a typo silently installs nothing. Verify with `dpkg -s`.

```bash
# BSP + sample rootfs (~3.3 GB), extract, apply NVIDIA binaries
tar --use-compress-program=lbzip2 -xf Jetson_Linux_R39.2.0_aarch64.tbz2
sudo tar --use-compress-program=lbzip2 -xpf \
     Tegra_Linux_Sample-Root-Filesystem_R39.2.0_aarch64.tbz2 -C Linux_for_Tegra/rootfs/
cd Linux_for_Tegra && sudo ./apply_binaries.sh      # arm64 qemu chroot; must end "Success!"
```

### Bake in the login BEFORE flashing (headless!)

A stock image first-boots into `oem-config`, the interactive account wizard —
with no monitor attached the Jetson just sits there and looks like a failed
flash. Pre-seed the account instead:

```bash
sudo ./tools/l4t_create_default_user.sh -u <user> -p <pass> -n <hostname> --accept-license
```

### Put the board in Force Recovery

J14 button header, on the **board edge under the module**. Authoritative pinout
(carrier board spec SP-11324-001, Table 3-4) — read the silkscreen, don't count pins:

| pin | signal | | pin | signal |
|---|---|---|---|---|
| 1 | PC_LED− | | 7 | GND |
| 2 | PC_LED+ | | 8 | SYS_RESET* |
| 3 | UART2_RXD (debug, 3.3V) | | 9 | GND |
| 4 | UART2_TXD (debug, 3.3V) | | 10 | **FORCE_RECOVERY*** |
| 5 | AC OK | | 11 | GND |
| 6 | Auto-Power-On disable | | 12 | SLEEP/WAKE* (power btn) |

No pin carries a supply rail, so a slip onto a neighbouring pair is at worst a
reset or a power-button press — not damage. The kit ships **no jumpers**; ATX
front-panel momentary switches fit (2.54 mm, 2-pin female) and are far easier
than a jumper cap in that cramped spot.

1. Board **unpowered**. NVMe seated in the M.2 Key-M slot (the kit ships with
   **no storage** — without it the flash has no target).
2. Bridge **FC_REC ↔ GND** (pins 10 ↔ 9) — hold the button.
3. **USB-C** from the Jetson's C port (J5, the *only* port that can flash; the
   Type-A ports are hosts) to `grr`. Any data-capable C cable.
4. Plug in the 19 V barrel jack (J16) — it powers on **automatically**, no
   button press, straight into recovery.
5. Release FC_REC.
6. Confirm on `grr`: `lsusb | grep 0955` → `0955:7523`.

*Already powered?* Hold FC_REC, tap RESET (pins 8↔7), release FC_REC. With two
buttons fitted you never touch the power cable — and recovery is repeatable,
which matters during bring-up.

### Flash

```bash
cd ~/jetson/Linux_for_Tegra
sudo ./tools/kernel_flash/l4t_initrd_flash.sh \
     --external-device nvme0n1p1 \
     -c ./tools/kernel_flash/flash_l4t_t234_nvme.xml \
     --showlogs --network usb0 \
     jetson-orin-nano-devkit-super internal
```

- `t234` = Orin (`t264` is Thor — wrong file, don't grab it by mistake).
- `--network usb0` runs the bulk transfer as ethernet-over-USB, which NVIDIA
  notes is more reliable than raw USB. Still just the one USB-C cable — no
  physical Ethernet needed for flashing.
- `internal` as rootdev writes **QSPI as well as** the NVMe. Going from
  shipped firmware to r39.2 is a big jump, so the bootloader update matters —
  **do not interrupt the flash**, a half-written QSPI is the one way to brick it.
- Add `--no-flash` to build the images with no board attached (de-risks the
  toolchain before you're standing there holding a button).

Ethernet **is** needed right after the flash — that's how you reach the box.

> **You can also reach it over the flash cable.** The Jetson brings up a USB
> gadget network on first boot and answers at **`192.168.55.1`** (the host side
> appears as an `enx…` interface). That works before any DHCP lease exists, and
> it's the quickest way in if Ethernet isn't ready — `ssh zodiac@192.168.55.1`
> straight from the flash host. Handy for exactly the moment you most need it.

3. Set the **Super** power mode (this is the whole point of the "Super"):
   ```bash
   sudo nvpmodel -p --verbose | grep POWER_MODEL   # list the IDs first
   sudo nvpmodel -m 2        # MAXN_SUPER on JetPack 7.2
   nvpmodel -q               # confirm: "NV Power Mode: MAXN_SUPER"
   sudo jetson_clocks --show # optional: check the ceilings actually moved
   ```
   > **The mode ID is not 0.** On JetPack 7.2 / L4T 39.2 the table is
   > `0=15W · 1=25W · 2=MAXN_SUPER`, and the board ships on **1**. This doc
   > previously said `-m 0`, which sets the *slowest* mode — following it left
   > the box **slower than stock** while reporting success. Verified on the real
   > board 2026-08-06: MAXN_SUPER took GPU max 612 → **1020 MHz** and CPU
   > 1497 → **1728 MHz**. Always list the modes rather than trusting an ID.

   It reverts on every reboot unless you change the default:
   ```bash
   sudo sed -i 's|^< PM_CONFIG DEFAULT=1 >|< PM_CONFIG DEFAULT=2 >|' /etc/nvpmodel.conf
   ```

4. **OpenCV is not preinstalled** — `import cv2` fails on a fresh JetPack 7.2.
   (Only the `fake` source is stdlib-only, so the bus can be proven without it;
   any real camera needs it.)
   ```bash
   sudo apt-get install -y nvidia-opencv libopencv-python
   ```
   `nvidia-opencv` alone installs the C++ libraries but **not** the Python
   bindings — `libopencv-python` is the package that provides `cv2`. Still
   **don't `pip install opencv-python`**: the apt build is the one matched to
   this JetPack. Note it reports `cv2.cuda.getCudaEnabledDeviceCount() == 0` and
   carries no CUDA in its build info — fine, since the motion detector is pure
   CPU work and the trained model will run through TensorRT, not OpenCV CUDA.

> **Thermal reality check.** The carrier board spec rates the dev kit at
> **0 °C to 35 °C ambient** (SP-11324-001 §1). Playa daytime runs 38–40 °C —
> *above* the rated range. The shaded mount, the aluminum KKSB case and a
> vented (not sealed) Jetson box are therefore load-bearing, not nice-to-have.
> Watch `tegrastats` under sun load during bring-up.

> **Serial console backstop.** J14 pins 3/4 are a **3.3 V** debug UART. A CP2102
> USB-TTL cable on `grr` gives console into the Jetson from anywhere — worth
> wiring for a box that lives headless in a vehicle. Crossover: adapter TXD→pin 3,
> RXD→pin 4, GND→pin 7/9/11, **5 V lead unused**. 115200 8N1. Receive-only
> (RXD+GND) gets you the full boot log with zero risk to the board.

## 2. Get it on the vehicle network

Pick one. Ethernet is the most reliable for a fixed roof install.

- **Wired (recommended):** RJ45 → travel router. Nothing else to do.
- **M.2 WiFi:** fit an Intel AX210 in the M.2 Key-E slot + two antennas, then
  join the car's AP (`nmcli device wifi connect <ssid> password <pw>`).
- **USB WiFi dongle:** simplest, join the AP the same way.

Find the Jetson's IP on that network — you'll want it for `--iface-ip`:
```bash
ip -4 addr show | grep -E 'inet .*(eth|wlan)'
```

> **Multicast across a wired↔wireless bridge:** if the Jetson is *wired* and the
> tablets are *wireless*, some routers drop multicast at the bridge (IGMP
> snooping). zvision already sends a **subnet-broadcast copy** of every frame, so
> it works regardless — but if you ever see fake threats on a same-band test and
> nothing across the bridge, that's the cause, and the broadcast leg is the fix.

## 3. Install zvision

**Clone directly into `/opt/zodiac`** — the same path the service runs from, so
there is exactly one copy of the code on the box and `git log` always answers
"what is actually running". Set up 2026-08-07; before that the box carried two
untracked copies (`/opt/zodiac` and `~/zodiac`) that had silently drifted four
days behind `main`, and nothing on the box could tell you that.

```bash
sudo mkdir -p /opt/zodiac && sudo chown "$USER:$USER" /opt/zodiac
git clone https://github.com/pureagave/zodiac.git /opt/zodiac
cd /opt/zodiac
sudo jetson/scripts/install.sh          # installs the systemd service
```

This installs a systemd service and a config file at `/etc/default/zvision` —
**outside** the repo, so a `git pull` never clobbers the rig's configuration.

Note what `install.sh` actually does, because it is less than it sounds:

- it **enables and starts** `zvision.service` immediately — the service is not
  left disabled. It is *configured to fake* (`ZVISION_ARGS=--source fake --hz 10`)
  until you edit that file, which means it will broadcast three synthetic
  contacts, one of them a recurring phantom collision, to every tablet on the
  network. Fine on a bench; do not leave it that way on the vehicle.
- it copies **only the `zvision` package** — not `zdeck`, not `tests`.
- it installs **only `zvision.service`** — not `zodiac-deck.service`,
  `zodiac-track.service`, `zodiac-track-serve.service` or the Stream Deck udev
  rule. Those are installed by hand (see [DECK.md](DECK.md) for the deck).
- it does not create `/var/lib/zodiac/track`, which the breadcrumb logger needs.

### Credentials: none live on this box

**The repo is public, so pulling needs no key at all** — the HTTPS clone above
is anonymous. Do not put a deploy key on the Jetson for read access; it would
protect nothing that isn't already public.

For **pushing** from the Jetson, use SSH agent forwarding rather than a resident
key. The push remote is already set to SSH:

```bash
git -C /opt/zodiac remote -v
# origin  https://github.com/pureagave/zodiac.git (fetch)
# origin  git@github.com:pureagave/zodiac.git     (push)
```

One-time on the **Mac**, so the agent actually holds an identity to forward:

```bash
ssh-add --apple-use-keychain ~/.ssh/id_ed25519   # prompts once, then Keychain remembers
ssh-add -l                                        # should list the key
```

Then connect with `-A` and the Jetson can push as you, for that session only:

```bash
ssh -A zodiac@192.168.86.235
cd /opt/zodiac && git push
```

**Why not a read-write deploy key:** this box lives unattended in a vehicle at
Burning Man. A push credential stored on it is a push credential to `main` for
anyone who walks off with the box or gets a shell on it. Agent forwarding
grants that power only while you are logged in, and leaves nothing behind. The
trade-off is that unattended pushes are impossible — which is the correct
answer for an art car.

### Updating the box

```bash
ssh zodiac@192.168.86.235
cd /opt/zodiac && git pull
sudo systemctl restart zvision
python3 -c "import sys; sys.path.insert(0,'/opt/zodiac/jetson')"   # sanity
```

Check what is deployed before trusting it — `git -C /opt/zodiac log --oneline -1`.

## 4. Prove the bus with NO camera

Before touching the camera, confirm the whole path to the HUD:

```bash
cd /opt/zodiac/jetson
python3 -m zvision --source fake -v
```

You should see one line per frame (`3 contacts -> N targets  ZTHREAT;...`).
Now on a **tablet**, switch to the **DRIVER** concept — you should see three
moving contacts, one going red as it "closes." **If that works, the network,
multicast/broadcast, and HUD are all proven.** Everything after this is just
swapping the fake detector for a real camera.

## 5. Attach the camera(s)

Plug in the PureThermal/Lepton (and optionally the USB webcam). Confirm they
enumerate:
```bash
v4l2-ctl --list-devices          # find which /dev/videoN is which
v4l2-ctl -d /dev/video0 --all    # sanity: resolution/format
```
Lepton Ultra Wide is **160×120**, not the 120×120 originally assumed — measured
on the real sensor, see §7b and `capture.py`. Each RGB camera will be a further
`/dev/videoN`.

Run the real detector:
```bash
python3 -m zvision --source thermal --device /dev/video0 --hz 10 --hfov 160 -v
```
Walk in front of the camera — you should see contacts appear, their `rel_az`
tracking left/right and `size` growing as you approach, on both the console and
the HUD.

`--hfov` must match your lens (Lepton **Ultra Wide** = 160°, the default) and
`--lens` its projection — `equidistant` for a fisheye like the UW,
`rectilinear` for an ordinary lens like the RGB modules. Together they set the
angle mapping, so calibrate if bearings look compressed or stretched. If your
lens' quoted FOV is a *diagonal* rather than a width, say so with `--fov-ref d`;
on a wide lens over a near-square sensor the two differ by tens of degrees.

**Sanity check it physically.** Stand at a known bearing (say 45° off the nose,
paced out on the ground) and confirm the console's `rel_az` agrees. Do it once
at the centre and once near the frame edge — the edge is where a wrong lens
model shows up, and it's the same number that aims the tracker light.

### 5b. The surround rig (multiple cameras)

One thermal forward plus RGB around the body. Repeat `--camera` per camera,
each with its mounting bearing (`az`, +right off the nose) and optics:
```bash
python3 -m zvision -v \
  --camera thermal:/dev/video0:az=0:fov=160:lens=fisheye:name=thermal \
  --camera rgb:/dev/video2:az=120:fov=90:lens=pinhole:name=stbd-aft \
  --camera rgb:/dev/video4:az=-120:fov=90:lens=pinhole:name=port-aft
```
zvision merges them into one full-circle contact list: bearings rotated into
vehicle terms, ids namespaced per camera, and one person seen by two
overlapping cameras collapsed to a single contact (`--merge-deg`, default 8°).

Verbose start-up prints each camera's arc **and any blind sector** — check that
before you go hunting for contacts that were never visible:
```
     thermal: thermal /dev/video0 az=+0° fov=160°h equidistant -> covers -80°..+80°
    stbd-aft: rgb /dev/video2 az=+120° fov=90°h rectilinear -> covers +75°..+165°
    port-aft: rgb /dev/video4 az=-120° fov=90°h rectilinear -> covers -165°..-75°
  blind: +166°..+195°
```
Measure each camera's `az` against the vehicle's actual nose, not by eye — an
error there rotates every contact from that camera and swings the tracker light
onto the wrong person. A camera that won't open (or dies mid-run) costs you its
arc, not the run; if *none* open, zvision exits rather than broadcasting a
confident "all clear" while blind.

## 6. Make it permanent

Edit `/etc/default/zvision` to the real source, then let systemd run it:
```bash
sudo nano /etc/default/zvision
#   ZVISION_ARGS=--source thermal --device /dev/video0 --hz 10 --iface-ip 192.168.0.x
sudo systemctl restart zvision
journalctl -u zvision -f          # live logs
systemctl status zvision          # confirm active + auto-start
```
It now starts on boot and restarts on crash.

## 7. Bring up the DMX tracker light (optional, independent of the camera)

The moving-head "tracker" light points at whatever the detector sees, and pulses
to the music (the beacon's `$ZAUD`) when there's nothing to follow. It's a
downstream consumer of the *same* detection stream, so it needs no camera to test.

```bash
sudo jetson/scripts/install-ola.sh     # olad + ftdidmx plugin + CPU-pinned + on-boot
```

Then plug in the USB→DMX dongle and patch it once (needs the port enumerated):
```bash
ola_dev_info                                  # find the ftdidmx device + port
ola_patch -d <device> -p <port> -u 0          # patch to universe 0  (or web UI :9090)
ola_set_dmx -u 0 -d 128,0,128,0,0,0,0,255     # pan/tilt/dimmer test — head centres AND lights
```

> **Do not use `-d 128,0,128,0,255` for this** — the older form of this command,
> still printed by `install-ola.sh`. In the 11-channel mode this fixture runs in,
> the fifth slot is the **colour wheel**, and 255 there means "auto colour change,
> fast". The **dimmer is channel 8**, so that command pans and tilts a head that
> stays dark while spinning colours — indistinguishable from a dead fixture, and
> precisely the trap [MOVING-HEAD.md §7](MOVING-HEAD.md) documents.
> The eight values above are pan, pan-fine, tilt, tilt-fine, colour, 0, 0, dimmer.
>
> If the head still does nothing, **check it completed its power-up homing sweep**
> before blaming software — see MOVING-HEAD.md §8.2.

Prove the pipeline, then go live:
```bash
python3 -m zvision --source fake --dmx fake -v   # logs target/pan/tilt/dim, no hardware
python3 -m zvision --source fake --dmx ola        # sends the universe to olad → dongle → head
```

Calibrate once on the vehicle: aim the head dead-ahead and set `--dmx-pan-center`
to that fixture pan angle; `--dmx-pan-gain` (~1.0) maps camera az→pan (negate if the
head pans mirror-imaged). To make it permanent, add the DMX flags to
`ZVISION_ARGS` in `/etc/default/zvision` alongside the source, e.g.:
```
ZVISION_ARGS=--source thermal --device /dev/video0 --hz 10 --dmx ola
```
`--dmx-no-sound` disables the idle sound show if you only want threat-following.

---

## 7b. The thermal camera — what the real board actually does

Measured 2026-08-07 on the Lepton Ultra Wide + PureThermal Mini. Several of
these contradict what the datasheets and this document previously said.

| | |
|---|---|
| enumerates as | `GroupGets PureThermal (fw:v1.3.0)` → `/dev/video0` + `/dev/video1`, `uvcvideo`, no drivers |
| resolution | **160×120 (4:3)** — *not* 120×120 |
| rate | **9 fps** native. `--hz 10` asks for more than it makes. |
| formats | UYVY, **Y16**, GREY, RGBP, BGR3 — **no MJPEG** |
| bandwidth | 160×120×2×9 ≈ **2.8 Mbps**. Irrelevant to the USB budget; the RGB ring is the contention. |
| connector | small USB-B family (**not** USB-C — that's the PureThermal 3) |

**The 8-bit output is unusable, and this is the important one.** A real indoor
frame through the 8-bit path came back with a standard deviation of **0.12
counts** — flat. The Lepton's AGC is reachable only via vendor UVC extension
units; `v4l2-ctl --list-ctrls` shows a *read-only* brightness and nothing else.
Background subtraction on that detects nothing, forever, and looks exactly like
"the camera works but there's nothing to see".

The same scene in **Y16 raw** has hundreds of counts of structure, and a hand
reads **+100 to +360** above background. So `zvision` opens the thermal through
`capture.ThermalCamera`, which takes Y16 and does its own percentile stretch.
Two traps found doing it:

* **Force the V4L2 backend.** OpenCV otherwise picks GStreamer, which silently
  ignores `CAP_PROP_CONVERT_RGB` (`unhandled property`) and hands back an
  already-converted 8-bit frame — std ~86 versus ~176 for true `uint16` raw.
* **Only crop telemetry rows when they exist.** The Y16 *122-row* mode appends 2
  Lepton telemetry rows, which read as a permanent band of false motion along
  the bottom edge. But asking for 120 returns 120 — an unconditional crop ate
  two real image rows.

**FFC is normal.** Every few minutes the internal shutter closes and the whole
frame goes uniform for an instant (spread collapses, then recovers). Don't read
it as a fault. It also means absolute counts drift, which is why the stretch is
per-frame and only *relative* structure is trusted.

## 8. Tuning on playa (laptop only — no keyboard, mouse or monitor)

Assume the flashed image is final and the *tuning* is not. Nothing below needs
a screen on the Jetson.

**Access:** MacBook joins the vehicle's travel-router WiFi → `ssh zodiac@zvision`
(the Jetson is on Ethernet to the same router as the tablets and beacon; no
internet required). Backstop if networking dies: the CP2102 serial console on
J14 pins 3/4 into the laptop, 115200 8N1.

**Your monitor is the tablets.** Change a value, restart, watch contacts appear
or vanish on the DRIVER HUD. That's a better feedback loop than a display on the
Jetson, because you're seeing exactly what the driver sees.

### The loop

```bash
# 1. Try it in the foreground first and watch the contacts scroll by
sudo systemctl stop zvision
python3 -m zvision -v --camera thermal:/dev/video0:az=0:fov=160:minarea=0.02

# 2. Happy? Validate the exact line before committing it to the service
python3 -m zvision --check <the same args>

# 3. Write it in and restart
sudo nano /etc/default/zvision      # ZVISION_ARGS=...
sudo systemctl restart zvision && journalctl -u zvision -f
```

**Always `--check` before writing `/etc/default/zvision`.** The unit is
`Restart=always`, so a typo'd flag becomes a crash loop — miserable to unpick
from a laptop in the dust. `--check` validates everything, prints the resolved
rig and blind arcs, and exits without opening a camera or touching the network.

### What you'll actually be tuning

Set a rig-wide default with the flag; override one camera with the spec key.

| flag | spec key | what it does | reach for it when |
|---|---|---|---|
| `--min-area` | `minarea` | min blob area, fraction of frame | dust/shimmer/shade-cloth make phantom contacts (raise) · people missed at range (lower) |
| `--match-dist` | `match` | track association distance | ids churn as people walk (raise) · two passers swap ids (lower) |
| `--far-h` / `--near-h` | `farh` / `nearh` | bbox height → the 0..1 range proxy | **expect to change these first** — pure guesswork until real bodies stand at real distances |
| `--collision-az-rate` | `azrate` | deg/s still counted as constant bearing | "! BRAKE !" too twitchy (lower) · missing real intercepts (raise) |
| `--collision-min-size` | `minsize` | how near before collision can trip | alarms firing on distant contacts (raise) |
| `--merge-deg` | — | cross-camera duplicate collapse | one person shows twice in an overlap (raise) · two close people merge (lower) |
| — | `az` | that camera's mount bearing | **measure against the vehicle nose** — an error here rotates that camera's whole contact set and swings the tracker light |
| `--fov-ref` | `fovref` | is the quoted FOV width or diagonal | edge bearings consistently off |

```bash
# rig-wide default, with one noisier camera pulled back
python3 -m zvision --check --min-area 0.008 \
  --camera thermal:/dev/video0:az=0:fov=160:lens=fisheye:name=thermal \
  --camera rgb:/dev/video2:az=105:fov=87:lens=pinhole:name=stbd:minarea=0.02 \
  --camera rgb:/dev/video4:az=-105:fov=87:lens=pinhole:name=port \
  --camera rgb:/dev/video6:az=180:fov=87:lens=pinhole:name=aft
```

> **Re-flashing on playa is possible** — `grr` travels as the camp's audio
> player, so the full BSP toolchain is on-site. Keep `~/jetson/Linux_for_Tegra`
> on it. That makes the OS layer recoverable, not one-shot.

### If `grr` itself is down

Everything above depends on it, so know the recovery path before you need it.
`grr` has **no serial console** (its `/dev/ttyS*` are 8250 driver stubs, no real
UART) — a console means HDMI + USB keyboard.

| symptom | do this |
|---|---|
| router/WiFi dead, grr otherwise fine | **direct cable to the spare NIC `enp2s0`** — set the laptop to `192.168.99.2/24`, then `ssh zodiac@192.168.99.1`. Works with router, WiFi and Tailscale all dead. |
| won't boot / stuck early | HDMI monitor + USB keyboard. `Ctrl+Alt+F3` for a text TTY (it boots to `graphical.target`). |
| unclean shutdown (vehicle power) | should self-heal — `fsck.repair=yes` is in the GRUB cmdline so it auto-repairs instead of waiting at an interactive prompt. Confirm with `cat /proc/cmdline`. |

The rescue NIC is the Ethernet port whose MAC ends **`:7a:b5`** (the camp port is
`:7a:b2`); `sudo ethtool -p enp2s0` blinks its LED. Boot-config changes, backups
and rollback commands are recorded **on the box** at
`/root/zodiac-hardening-backup-*/CHANGES.md` — a separate Claude instance
manages that station, so changes get logged there as well as here.

## Troubleshooting

| symptom | cause / fix |
|---|---|
| HUD shows fake demo threats, not the Jetson's | tablet isn't receiving — check same subnet, and that the DRIVER source is network (fake is the fallback when no frames arrive) |
| Console shows frames, HUD shows nothing | multicast dropped at router bridge — broadcast leg should cover it; verify tablet and Jetson share a `/24`, check AP client-isolation is **off** |
| `-> 0 targets` in logs | no network route yet (`network-online.target` not reached) — check `ip addr`, set `--iface-ip` |
| `could not open camera` | wrong `/dev/videoN` (`v4l2-ctl --list-devices`), or cv2 missing on a non-JetPack box (`pip install opencv-python numpy`) |
| bearings compressed/stretched | `--hfov` doesn't match the lens, or `--lens` has the wrong projection (fisheye = `equidistant`, ordinary = `rectilinear`); if the datasheet FOV is a diagonal, add `--fov-ref d` |
| one camera's contacts all sit at the wrong bearing | that camera's `az=` doesn't match how it's actually bolted on — measure it against the nose |
| one person shows up twice in a camera overlap | raise `--merge-deg`; lower it if two people standing close together get collapsed into one |
| `no cameras opened`, exit 3 | none of the rig's devices enumerated — `v4l2-ctl --list-devices`, check USB power/hub |
| a camera vanished but the run continued | by design — check stderr for `camera <name> failing, dropped`; that arc is blind until it's back |
| contacts flicker / bad ids | motion detector is bring-up-grade; the trained model replaces it — for now raise `--hz` and ensure a stable mount |
| `--dmx ola` runs but the head doesn't move | **First: did it complete its power-up homing sweep?** An un-homed head accepts DMX and silently discards it (MOVING-HEAD.md §8.2). Then: universe not patched (`ola_dev_info` → `ola_patch`), or the wrong universe (`--dmx-universe`). Confirm with `ola_set_dmx -u 0 -d 128,0,128,0,0,0,0,255` — the trailing 255 is the ch8 dimmer, without it the head moves in the dark. |
| DMX flickers / stutters | another OLA plugin is fighting for the FT232 (leave only `ftdidmx` on), or olad isn't CPU-pinned — re-run `install-ola.sh`; check `systemctl show olad -p CPUAffinity` |
| olad won't grab the dongle | kernel `ftdi_sio` may hold it as `/dev/ttyUSB0`; ftdidmx uses libftdi and should detach it — if not, `sudo modprobe -r ftdi_sio` (or blacklist it) |
| head moves but never pulses to music | no `$ZAUD` arriving (beacon not broadcasting / different subnet), or `--dmx-no-sound` set. The sound show only runs when *idle* (no contact to follow). |

## Wire protocol (reference)

One UDP datagram per frame, ASCII, to `239.7.7.20:10120` (+ subnet broadcast):
```
ZTHREAT;<id>:<relAzDeg>:<size>:<collision>;<id>:<relAzDeg>:<size>:<collision>...
```
`collision` is `0`/`1`; a bare `ZTHREAT` means **all clear**. `relAzDeg` is a
**full-circle** bearing off the nose, ±180 (it was capped at ±90 while there was
only a forward camera). A frame carries at most **32 contacts**, prioritised
collision-first then largest-first, so it stays inside one MTU — a fragmented
multicast datagram over lossy WiFi essentially never arrives.

`zvision/threat_protocol.py` and the tablet's Kotlin
`core/vision/ThreatProtocol.kt` are **two hand-written implementations, and
neither is authoritative.** The shared truth is the measured golden corpus at
`protocol/threat-protocol-golden.json`, which both test suites read and both fail
loudly on if it is missing. Before it existed the two sides had silently drifted
in ten measured ways — `0x1p3` in an azimuth field became a live contact bearing
8° on the driver's HUD while this side rejected the same frame. Both sides now
check explicit numeric grammars (`-?[0-9]{1,9}` for ids,
`-?[0-9]{1,9}(\.[0-9]{1,6})?` for numbers) rather than delegating to the host
language's parser.

**Changing the format means changing three things**: this side, the Kotlin side,
and regenerating the corpus by re-running the differential comparison. Do not
hand-edit the JSON — see `protocol/README.md` and `docs/PROTOCOLS.md` §3.

> The DRIVER HUD's *perspective* view draws only the forward arc, but the
> **surround ring carries every contact** all the way round — so rear contacts are
> displayed, just as plan-view blips rather than wireframe figures. See
> `design/surround-driver-hud.md`.
