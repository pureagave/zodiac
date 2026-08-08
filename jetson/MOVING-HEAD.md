# LED Moving Head — Manual Reference

> **This is the vehicle's tracker-light fixture.** `zvision/tracker.py` is wired
> to the **11-channel** table in §3.2 below. Two things in here are traps that
> already cost us one bug — see §7 at the end before changing any channel.

> Transcribed from photographed manual pages 2–6. Generic Chinese-OEM LED moving head
> (1× high-power white LED, gobo/color wheel, 9/11-channel DMX).
> Pages 1 and 7+ not captured. Typos in the original have been silently corrected;
> ambiguous items are flagged with `<!-- note -->`.

---

## 1. Safety & Mounting

- Never stand directly below the fixture when mounting, removing, or servicing it.
- Mount from a ceiling/truss or set on a flat level surface.
- Keep at least **0.5 m (1.5 ft)** from any flammable material (decoration, drape, etc.).
- Always install the supplied safety cable to prevent damage/injury if the clamp fails.

### 1.1 Mounting points

Overhead mounting requires experience: calculating working load limits, knowledge of the
installation material, and periodic safety inspection of both the rigging and the fixture.
Complete all rigging and installation **before** connecting the power cord to the wall outlet.

### 1.2 Clamp mounting

The base integrates the mounting bracket, the included **omega bracket**, and the safety
cable rigging point in one unit.

| Item | Spec |
|---|---|
| Clamp fastener | **M10 screw** through the center hole of the omega bracket |
| Clamp rating | Must be appropriately rated for fixture weight (3.1 kg / 6.8 lbs) |
| Safety cable | At least one properly rated cable, attached to the rigging point in the base assembly |

---

## 2. DMX-512 Connection

- Provided XLR cable: female 3-pin XLR out of the controller → male 3-pin XLR into the fixture.
- Multiple heads can be daisy-chained via serial linking.
- Cable must be **two-core screened (shielded)** cable with XLR in/out connectors.

### 2.1 Pinout (3-pin XLR)

| Pin | Function |
|---|---|
| 1 | Ground / shield |
| 2 | Data − (DMX −) |
| 3 | Data + (DMX +) |

### 2.2 Termination

For long cable runs or electrically noisy environments, terminate the chain.
The terminator is an XLR plug with a **120 Ω resistor across pins 2 and 3**, plugged into
the DMX output socket of the **last fixture** in the chain.

---

## 3. DMX Channel Tables

### 3.1 9-Channel Mode

| Ch | Function | Values | Behavior |
|---:|---|---|---|
| 1 | Horizontal operation (Pan) | 0–255 | — |
| 2 | Vertical operation (Tilt) | 0–255 | — |
| 3 | Color | 0–139 | Color select |
| | | 140–255 | Auto color change, slow → fast |
| 4 | Pattern (Gobo) | 0–63 | Fixed pattern |
| | | 64–127 | Pattern dither / shake |
| | | 128–255 | Auto pattern change, slow → fast |
| 5 | Strobe | 0–255 | — |
| 6 | Dimming | 0–255 | — |
| 7 | Horizontal/Vertical operation | 0–255 | Pan/tilt movement speed <!-- original text ambiguous --> |
| 8 | Automatic mode | 0–255 | "Upward adjustment, downward adjustment" <!-- original text ambiguous --> |
| 9 | Mode select | 0–20 | Other mode |
| | | 21–100 | X motor automatic mode |
| | | 101–200 | Y motor automatic mode |
| | | 201–249 | XY motor automatic mode |
| | | 250–255 | **Reset** (hold 5 seconds) |

### 3.2 11-Channel Mode

| Ch | Function | Values | Behavior |
|---:|---|---|---|
| 1 | Horizontal operation (Pan) | 0–255 | — |
| 2 | Horizontal fine tune (Pan fine) | 0–255 | — |
| 3 | Vertical operation (Tilt) | 0–255 | — |
| 4 | Vertical fine tune (Tilt fine) | 0–255 | — |
| 5 | Color | 0–139 | Color select |
| | | 140–255 | Auto color change, slow → fast |
| 6 | Pattern (Gobo) | 0–63 | Fixed pattern |
| | | 64–127 | Pattern dither / shake |
| | | 128–255 | Auto pattern change, slow → fast |
| 7 | Strobe | 0–255 | — |
| 8 | Dimming | 0–255 | — |
| 9 | Horizontal/Vertical operation | 0–255 | Pan/tilt movement speed <!-- original text ambiguous --> |
| 10 | Automatic mode | 0–59 | Other mode |
| | | 60–84 | Automatic mode 3 |
| | | 85–109 | Automatic mode 2 |
| | | 110–134 | Automatic mode 1 |
| | | 135–159 | Automatic mode 0 |
| | | 160–184 | Sound mode 3 |
| | | 185–209 | Sound mode 2 |
| | | 210–234 | Sound mode 1 |
| | | 235–255 | Sound mode 0 |
| 11 | Mode select | 0–20 | Other mode |
| | | 21–100 | X motor automatic mode |
| | | 101–200 | Y motor automatic mode |
| | | 201–249 | XY motor automatic mode |
| | | 250–255 | **Reset** (hold 5 seconds) |

---

## 4. Onboard Menu

### 4.1 Hold mode / signal loss

Set to hold mode and unplug the DMX (512) signal line for ~30 minutes, then power off —
the system automatically saves the last received DMX values.

### 4.2 Fine-tuning (motor offsets)

From address display `A001`, **press the 4th key for ~10 seconds** to open the menu:

| Menu item | Range | Meaning |
|---|---|---|
| `H` | 000–255 | X motor (pan) offset |
| `Y` | 000–255 | Y motor (tilt) offset |
| `C` | 000–255 | Color wheel motor offset |
| `G` | 000–255 | Pattern (gobo) motor offset |
| `DEFR` | — | Initialize/reset the 4 parameters above |

**To save:** press the 4th key again for ~10 seconds. This step is mandatory —
without it the settings are not stored.

---

## 5. Technical Parameters

| Parameter | Value |
|---|---|
| Input power | AC 100–240 V, 50/60 Hz |
| Output | Max 1700 lux @ 5 m |
| Light source | 1× high-power white LED |
| DMX channels | 9 / 11 |
| Pan (X axis) | 540° |
| Tilt (Y axis) | 270° |
| Control modes | DMX-512 / Master-Slave / Auto run / Sound active |
| Display | LED display |
| Internal programs | 8 (sound mode controllable via DMX-512) |
| Net weight | 3.1 kg / 6.8 lbs |
| Packing size | 45 × 31 × 47 cm / 17.7 × 12.2 × 18.5 in |

---

## 6. Practical Notes (not from the manual)

- 11-channel mode is 9-channel mode plus 16-bit pan/tilt (coarse+fine on ch 1–4).
  If you need smooth slow moves, use 11ch and drive pan/tilt as 16-bit values.
- Channel 11 (or 9 in 9ch mode) holding 250–255 for 5 s triggers a motor reset —
  keep it parked at 0 during normal show playback to avoid accidental resets.
- Auto/sound modes on ch 10 only apply when the fixture is otherwise idle;
  driving pan/tilt/color explicitly while ch 10 is above 59 gives unpredictable results.
- No pan/tilt speed inversion channel is documented; motor offsets are the only trim.

---

## 7. How zvision drives this fixture

`TrackerConfig` in `zvision/tracker.py` holds the wiring; these are the
manual-derived facts behind its defaults.

| Config | Value | Why |
|---|---|---|
| `pan_channel` / `pan_fine_channel` | 1 / 2 | 16-bit pan (§3.2) |
| `tilt_channel` / `tilt_fine_channel` | 3 / 4 | 16-bit tilt |
| `dimmer_channel` | **8** | §3.2 — **not 5** |
| `pan_range_deg` | 540 | §5 |
| `tilt_range_deg` | 270 | §5 |

**11-channel, not 9.** 8-bit pan across 540° is ~2.1° per step, which is
visibly steppy on a slow follow. The fine channels are the whole reason to use
the longer personality. `NINE_CHANNEL_OVERRIDES` exists for a head stuck in the
short mode.

### Traps

1. **The dimmer is channel 8. Channel 5 is the colour wheel.** We had it on 5,
   where 140–255 means "auto colour change, fast" — so the head would have spun
   colours at full speed while the real dimmer sat at 0 and the fixture looked
   dead. Found by reading this manual against the channel table; `test_tracker`
   now asserts it, and both tests that had hardcoded channel 5 were agreeing
   with the bug.
2. **Channel 11 at 250–255 for five seconds is a motor reset.** Mid-show that
   is a head that stops obeying us. We never write it, and a test asserts we
   never do.
3. **Channel 10 above 59 hands control to the internal auto/sound programs**,
   which fight explicit pan/tilt writes. Same treatment: never written, asserted.

### Still to determine on the bench

- **Channel 9 (pan/tilt movement speed)** — the manual doesn't say which end of
  0–255 is fast. We leave it at 0. If the head lags badly behind a walking
  contact, this is the first thing to try, and it interacts with the
  `pan_slew_dps` / `tilt_slew_dps` ceilings we impose in software.
- `pan_center_deg` / `pan_gain` / `reach_half_deg` still need the one-time
  vehicle calibration (aim dead ahead, then at a known bearing).
- **Fine-tune menu** (§4.2) offers motor offsets `H`/`Y`/`C`/`G` — a hardware
  alternative to `pan_center_deg` if the mount ends up mechanically skewed.
  Remember the save step: press the 4th key ~10 s again or nothing is stored.
