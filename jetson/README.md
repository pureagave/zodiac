# zvision — Zodiac edge-box (Jetson) software

Runs on the roof-mounted **Jetson Orin Nano Super**. Turns thermal / RGB camera
frames into vehicle-relative **threat contacts** and broadcasts them on the
fleet bus, where every tablet's DRIVER night-HUD is already listening. A second,
optional output drives a **DMX moving-head "tracker" light** that points at those
same contacts and — when idle — pulses to the beacon's `$ZAUD` audio (see the
[DMX section in DEPLOY.md](DEPLOY.md#7-bring-up-the-dmx-tracker-light-optional-independent-of-the-camera)).

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
python3 -m unittest discover -s tests -t . # 80 tests, standard library only
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
| `zvision/tracker.py` | DMX tracker light — target-select + pan/tilt map + slew + idle sound show |
| `zvision/dmx.py` | DMX transport — `FakeDmxSink` (stdlib) + `OlaDmxSink` (posts to `olad`) |
| `zvision/audio_bus.py` | `$ZAUD` listener — beacon mic levels for the idle sound-reactive show |
| `zvision/app.py` | CLI runner / broadcast + tracker loop |
| `systemd/zvision.service` | auto-start unit |
| `scripts/install.sh` | provision to `/opt/zodiac/jetson` + enable service |
| `scripts/install-ola.sh` | OLA (`olad`) + `ftdidmx` plugin, CPU-pinned, on-boot |

## Docs

- **[DEPLOY.md](DEPLOY.md)** — full hardware bring-up (flash → network → prove-with-fake → camera → permanent → DMX tracker light)
- **[HARDWARE.md](HARDWARE.md)** — edge-box bill of materials, wiring, power & thermal budget
- **[DETECTOR.md](DETECTOR.md)** — roadmap from today's motion blobs to the trained thermal model (H100)
