# zvision — Zodiac edge-box (Jetson) software

Runs on the roof-mounted **Jetson Orin Nano Super**. Turns thermal / RGB camera
frames into vehicle-relative **threat contacts** and broadcasts them on the
fleet bus, where every tablet's DRIVER night-HUD is already listening.

```
 camera(s) ──▶ detector ──▶ DriverThreat[] ──▶ ThreatBroadcaster ──▶ fleet bus
  Lepton /                 (rel_az, size,       ZTHREAT frames        239.7.7.20:10120
  USB RGB                   collision, id)      (UDP, TTL 1)          + subnet broadcast
                                                                          │
                                              tablets: NetworkThreatSource ▶ DRIVER HUD
```

The wire format (`zvision/threat_protocol.py`) is a **byte-exact mirror** of the
tablet's Kotlin `ThreatProtocol` — the two sides share no code, only the frozen
protocol. The round-trip tests here also guard that contract.

## Quick start (no hardware needed)

```bash
cd jetson
python3 -m zvision --source fake -v        # emit synthetic contacts, print each frame
python3 -m unittest discover -s tests -t . # 29 tests, standard library only
```

`--source fake` needs nothing installed — it's how you prove the bus and light
up the HUD before any camera or model exists. Point it at a tablet on the same
WiFi and the DRIVER concept comes alive.

## With a camera (bring-up)

```bash
python3 -m zvision --source thermal --device /dev/video0 --hz 10 -v
```

`--source thermal|rgb` runs `MotionDetector`: background-subtraction blobs →
contacts. **No trained model required** — plug in a UVC camera (Lepton via
PureThermal, or a USB webcam) and moving bodies show up on the HUD immediately.
The trained thermal/RGB model drops in behind the same `detect()` signature
later.

## Layout

| file | role |
|---|---|
| `zvision/threat_protocol.py` | ZTHREAT wire format (mirrors Kotlin) |
| `zvision/fleet_bus.py` | multicast group/port constants (mirrors Kotlin) |
| `zvision/broadcaster.py` | UDP sender — multicast **and** subnet broadcast |
| `zvision/detector.py` | `FakeDetector` (stdlib) + `MotionDetector` (cv2) |
| `zvision/geometry.py` | bbox → (rel_az, size) + constant-bearing collision rule |
| `zvision/capture.py` | UVC camera wrapper (cv2) |
| `zvision/app.py` | CLI runner / broadcast loop |
| `systemd/zvision.service` | auto-start unit |
| `scripts/install.sh` | provision to `/opt/zodiac/jetson` + enable service |

## Docs

- **[DEPLOY.md](DEPLOY.md)** — full hardware bring-up (flash → network → prove-with-fake → camera → permanent)
- **[HARDWARE.md](HARDWARE.md)** — edge-box bill of materials, wiring, power & thermal budget
- **[DETECTOR.md](DETECTOR.md)** — roadmap from today's motion blobs to the trained thermal model (H100)
