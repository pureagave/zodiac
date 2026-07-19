# Jetson edge-box bring-up

End-to-end guide to take a Jetson Orin Nano Super from box to broadcasting live
threats onto the vehicle bus. Steps 1–3 you do once; step 4 onward is where we
plug the camera in together.

The design goal: **every stage is verifiable on its own.** You prove the network
path with `--source fake` before a camera is ever attached, so if something
breaks later you know it's the camera/model, not the bus.

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
Lepton 3.5 is 160×120. A USB webcam will be a second `/dev/videoN`.

Run the real detector:
```bash
python3 -m zvision --source thermal --device /dev/video0 --hz 10 --hfov 57 -v
```
Walk in front of the camera — you should see contacts appear, their `rel_az`
tracking left/right and `size` growing as you approach, on both the console and
the HUD. `--hfov` must match your lens (Lepton 3.5 standard ≈ 57°); it sets the
left/right angle mapping, so calibrate it if bearings look compressed/stretched.

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

---

## Troubleshooting

| symptom | cause / fix |
|---|---|
| HUD shows fake demo threats, not the Jetson's | tablet isn't receiving — check same subnet, and that the DRIVER source is network (fake is the fallback when no frames arrive) |
| Console shows frames, HUD shows nothing | multicast dropped at router bridge — broadcast leg should cover it; verify tablet and Jetson share a `/24`, check AP client-isolation is **off** |
| `-> 0 targets` in logs | no network route yet (`network-online.target` not reached) — check `ip addr`, set `--iface-ip` |
| `could not open camera` | wrong `/dev/videoN` (`v4l2-ctl --list-devices`), or cv2 missing on a non-JetPack box (`pip install opencv-python numpy`) |
| bearings compressed/stretched | `--hfov` doesn't match the lens |
| contacts flicker / bad ids | motion detector is bring-up-grade; the trained model replaces it — for now raise `--hz` and ensure a stable mount |

## Wire protocol (reference)

One UDP datagram per frame, ASCII, to `239.7.7.20:10120` (+ subnet broadcast):
```
ZTHREAT;<id>:<relAzDeg>:<size>:<collision>;<id>:<relAzDeg>:<size>:<collision>...
```
`collision` is `0`/`1`; a bare `ZTHREAT` means **all clear**. Defined in
`zvision/threat_protocol.py`, mirrored from the tablet's Kotlin
`core/vision/ThreatProtocol.kt`. Changing it means changing both sides.
