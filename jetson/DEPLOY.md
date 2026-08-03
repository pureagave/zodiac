# Jetson edge-box bring-up

End-to-end guide to take a Jetson Orin Nano Super from box to broadcasting live
threats onto the vehicle bus. Steps 1–3 you do once; step 4 onward is where we
plug the camera in together.

The design goal: **every stage is verifiable on its own.** You prove the network
path with `--source fake` before a camera is ever attached, so if something
breaks later you know it's the camera/model, not the bus.

## Bring-up checklist (day the NVMe arrives)

Each box is proven before the next; each maps to a section below.

- [ ] **Flash** JetPack 6.x to the NVMe, first-boot Ubuntu, `nvpmodel -m 0` (MAXN Super) — §1
- [ ] **Network**: Ethernet → travel router; note the Jetson's IP (`--iface-ip`) — §2
- [ ] **Install**: `sudo jetson/scripts/install.sh` (zvision service + config) — §3
- [ ] **Prove the bus, no camera**: `python3 -m zvision --source fake -v` → tablet DRIVER HUD shows the moving demo — §4 ✅ this is the big one
- [ ] **Attach camera(s)**: `v4l2-ctl --list-devices`, then `--source thermal` and walk in front of it — §5
- [ ] **Make permanent**: set the real source in `/etc/default/zvision`, `systemctl restart zvision` — §6
- [ ] *(optional, any time)* **DMX light**: `sudo jetson/scripts/install-ola.sh`, patch the dongle, `--dmx ola` — §7

Prereqs already in hand: Jetson kit + PSU, Lepton+PureThermal, RGB cam, DMX dongle+cables+head, router. Still needed before a *field* install (not for bench bring-up): germanium thermal window, vents, vibration pads, short USB cables. Software is 100% ready — the box just needs to boot.

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

1. Download the **Jetson Orin Nano Super** SD-card image (JetPack 6.x) from
   NVIDIA, or use the NVMe/USB installer if you fitted an SSD.
2. Flash with Balena Etcher / `dd`, boot, complete the Ubuntu first-run setup.
3. Set the **Super** power mode (this is the whole point of the "Super"):
   ```bash
   sudo nvpmodel -m 0        # MAXN SUPER
   sudo jetson_clocks        # lock clocks up (optional, for benchmarking)
   nvpmodel -q               # confirm
   ```
4. `python3 --version` — JetPack ships Python 3; `import cv2` should already
   work (CUDA-enabled). **Do not `pip install opencv-python` on the Jetson** — it
   shadows the system build.

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

```bash
git clone https://github.com/pureagave/zodiac.git
cd zodiac
sudo jetson/scripts/install.sh          # copies to /opt/zodiac/jetson, installs service
```

This installs a systemd service (disabled-to-fake by default) and a config file
at `/etc/default/zvision`.

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
Lepton Ultra Wide is 120×120. Each RGB camera will be a further `/dev/videoN`.

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
ola_set_dmx -u 0 -d 128,0,128,0,255           # pan/tilt/dimmer test — the head should move
```

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
| `--dmx ola` runs but the head doesn't move | universe not patched (`ola_dev_info` → `ola_patch`), or the wrong universe (`--dmx-universe`). Confirm with `ola_set_dmx -u 0 -d 128,0,128`. |
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
only a forward camera). Defined in `zvision/threat_protocol.py`, mirrored from
the tablet's Kotlin `core/vision/ThreatProtocol.kt`. Changing it means changing
both sides.

> The DRIVER HUD still *draws* only the forward half — it places a contact by
> `az / THERMAL_HALF_FOV_DEG` — so rear contacts ride the bus today but aren't
> displayed. The surround HUD layout is the follow-up.
