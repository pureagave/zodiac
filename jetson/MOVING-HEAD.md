# LED Moving Head — Manual Reference

> **This is the vehicle's tracker-light fixture.** `zvision/tracker.py` is wired
> to the **11-channel** table in §3.2 below. Two things in here are traps that
> already cost us one bug — see §7 at the end before changing any channel.
>
> ✅ **The fixture is set to 11-CHANNEL and verified on the bench 2026-08-08**
> (§8), so `TrackerConfig`'s defaults match the hardware. It arrived in
> **9-channel** and was switched via the `CHnd` menu item (§4.3). If anyone ever
> factory-resets the head, **re-check `CHnd` first** — in 9-channel these
> defaults drive tilt onto the colour wheel and the dimmer onto "automatic
> mode", which looks exactly like a dead fixture.

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

### 4.3 Top-level menu map — read off the fixture 2026-08-08

**Not in the manual.** The transcribed pages document only §4.2 above, which
sent us looking for a channel-mode option that does exist — it was just behind
an opaque label. Buttons are **MENU / UP / DOWN / ENTER**; MENU cycles.

The abbreviations use `nd` for "Md" (Mode) — the pattern repeats across four
entries, which is what makes the reading reliable rather than a guess.

| Display | Meaning | Notes |
|---|---|---|
| `addr` | DMX start address | the `A.001` idle display |
| **`CHnd`** | **Channel Mode (9 / 11)** | **the 9-vs-11 selector.** Set to **11** |
| `SLnd` | Slave Mode | master/slave daisy-chain |
| `SHnd` | Show Mode | the internal auto program |
| `Sound` | Sound-active mode | |
| `SEnS` | Sound sensitivity | |
| **`BLnd`** | **Signal-loss behaviour** — options `hold` / `auto` / `blac` / `Sound` | shipped as **`auto`**, which is what makes the head run its own show when DMX stops (§8.3). **Set to `blac`** |
| `LEd` | LED options | |
| `diSP` | Display options | |
| `rPAn` | Reverse pan | **`no`** — so `pan_gain` stays **+1.0** |
| `rtiL` | Reverse tilt | |
| `rESt` | Reset | same effect as the ch11 DMX reset |

**`BLnd` is the hardware fix for the signal-loss light show.** Setting it to
`blac` beats any software keepalive, because it still works when the Jetson is
the thing that died — precisely the case a keepalive cannot cover.

Why `blac` and not `hold`: **the vehicle moves.** `hold` freezes the head at its
last pan/tilt *in the fixture's own frame*, so within seconds of the car turning
a held beam is raking across whoever happens to be there, at full brightness,
uncommanded — and it reads as purposeful, which is worse than the disco. `blac`
also matches what the software would do anyway (`dimmer_idle = 0` blacks the
head out with nothing to track), so the failure state and the idle state agree.

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

---

## 8. First bench bring-up — 2026-08-08

First time the fixture was powered with the dongle attached. Everything below
was **measured**, not read off the manual.

### 8.1 As found: 9-channel — then switched to 11 and re-verified

The head **arrived in 9-channel mode.** Settled by three observations, each of
which is impossible under the other mode:

| Test | Observed | Rules out |
|---|---|---|
| `ch5=200, ch6=255` | lamp **strobed** | 11ch — there ch5/ch6 are colour/gobo and cannot strobe |
| `ch2` alternating 0↔255 | head **moved** | 11ch — there ch2 is pan-*fine*, a sub-degree move |
| `ch6` 255→0, nothing else changed | lamp **went dark** | 11ch — there the dimmer is ch8, and ch6 is the gobo wheel |

That live map was **§3.1**: pan 1, tilt 2, colour 3, gobo 4, strobe 5,
**dimmer 6**, speed 7, auto 8, mode/reset 9.

**It was then set to 11-channel via `CHnd` (§4.3) and re-verified**, because the
menu saying "11" is not evidence that the fixture changed personality. Two
independent measurements confirmed it did:

| Test | Observed | Rules out |
|---|---|---|
| `ch6=0`, `ch8=255` | lamp **lit** | 9ch — there ch6 *is* the dimmer and was proved so by mutation minutes earlier |
| `ch3` swung 40↔220, `ch1` held at 128 | head **swept ~190°** | 9ch — there ch3 is the colour wheel and cannot move the head at all |

Holding `ch1` constant is what makes the second test airtight: the motion had
nowhere to come from except ch3. **The live map is now §3.2** and
`TrackerConfig`'s defaults are correct as written.

### 8.2 The head ignores DMX until its motors have homed

**This is the trap that cost the whole first hour.** A fixture that has not
completed its power-up reset accepts DMX and does nothing with it — no
movement, no lamp, no error, display sitting normally at `A.001`. It is
indistinguishable from a dead wire, and it sent us hunting through olad,
libftdi, USB enumeration and the XLR before we ever suspected the fixture.

**Check the head performs its homing sweep at power-up.** If it didn't, force a
motor reset (ch9 held 250–255 for 5 s) *before* concluding anything is broken.

### 8.3 On DMX signal loss the head runs its own light show

Stopping `olad` produced, in order: `rST` on the display, a motor reset, then
the internal auto program — sweeping, cycling gobos and colours, at full
brightness. Restoring the signal returned it to obeying DMX (all-zero universe
⇒ dark and motionless) with no intervention.

**Operational consequence:** any Jetson reboot, `olad` crash or knocked XLR
turns the tracker light into an uncommanded disco, while the driver's HUD still
assumes it is pointing at people. `OlaDmxSink` deliberately swallows send
failures so lighting can never take down the threat broadcaster — but it has no
concept of *keeping the wire alive*. Tracked in `tasks/open.md`.

### 8.4 The decimal point is the signal indicator

`A.001` with a **flashing dot** = valid DMX arriving. Steady dot = no signal.
Cheapest possible "is data reaching the fixture" check, and it needs no tools.

### 8.5 Verified-good software chain (so it never gets re-diagnosed)

- Dongle: FT232R, `0403:6001`, serial **`BG03OCDS`**, USB→3-pin XLR.
- Plugin: **FTDI USB DMX** (`ftdidmx`), port patched to **universe 0**.
- `/dev/ttyUSB*` is *absent by design* — libftdi detaches `ftdi_sio` when olad
  claims the device. Its absence is health, not a fault.
- **olad reads `--config-dir /etc/ola`** (from `/etc/init.d/olad`).
  `/var/lib/ola/conf/` also contains `ola-*.conf` files and **olad never reads
  them** — the exact trap `dmx.py` warns about. Edit `/etc/ola`.
- Health check without a scope: the unnamed olad thread (the FTDI TX thread,
  logged as `Thread ,` with an empty name) burns ~2 jiffies per 3 s at 30 Hz.
  Zero means it is not transmitting.

### 8.6 Colour wheel, measured with the rig's own camera

The head was pointed at the ceiling and the rig's RGB camera (`video0`, the
Microdia Vitade AF — the thermal on `video4` was left to zvision) measured every
slot. Method matters here, because two things make a naive reading wrong:

- **A clipped beam core reads white whatever colour is in it.** Every exposure
  from 5 to 320 gave `lit_max = 255`, so brightness is levelled *per colour*
  via the DMX dimmer to land the core near 200 — bright but unclipped.
- **The camera is not colorimetric.** Auto-exposure and auto-white-balance are
  locked off (with auto-exposure on, the *lit* frame read darker than the dark
  frame), and every colour is then normalised against the open-white slot
  measured through the same optics. That divides out both the camera response
  and the LED's own spectrum, leaving the filter's transmission.

| `ch5` | Colour | Measured | Hue | Sat |
|---:|---|---|---:|---:|
| 0–8 | open white | `#FFFFFF` | — | 0.00 |
| 10–18 | red | `#FF442C` | 6° | 0.83 |
| 20–28 | **green** | `#00FF1F` | 128° | 1.00 |
| 30–38 | **blue / cyan** | `#00C9FF` | 192° | 1.00 |
| 40–48 | pale yellow | `#FFFCB4` | 58° | 0.29 |
| 50–58 | amber | `#FFCF37` | 46° | 0.79 |
| 60–68 | aqua / turquoise | `#1CFFE2` | 172° | 0.89 |
| 70–88 | **lavender / purple** | `#EAAEFF` | 284° | 0.31 |
| 90–98 | yellow | `#FFE854` | 52° | 0.70 |
| 100–118 | pale yellow / yellow-green band | mixed | 50–76° | 0.26–0.42 |
| 120–139 | **green** (second, wider slot) | `#00FF20` | 128° | 1.00 |

Boundaries were walked at 2-unit resolution: transitions sit at 18→20, 28→30,
38→40, 68→70, 88→90 and 118→120. **140 and above is auto colour-spin** — stay
below it.

#### Against Zodiac's palette (`ui/concepts/ConceptTheme`)

| Zodiac | Target | Best slot | Measured | Off by |
|---|---|---|---|---|
| blue `#00BFFF` | 195° | **`ch5=34`** | `#00C9FF` | **3° — effectively exact** |
| red `#FF5555` | 0° | `ch5=14` | `#FF442C` | 6°, and *more* saturated than the UI red |
| green `#00FF66` | 144° | **`ch5=128`** | `#00FF20` | 16° — wheel green is yellower than the UI mint |
| purple `#C77DFF` | 274° | **`ch5=78`** | `#EAAEFF` | 10° hue, but sat 0.31 vs 0.51 — a pale lavender |

**Use `ch5=128` for green, not 24.** Both slots are the same filter, but the
120–139 slot is twice as wide, so an 8-bit DMX value plus wheel mechanical
tolerance has far more margin before it lands on a boundary. A half-seated wheel
washes the colour out — visible as saturation collapsing while hue still looks
plausible, which is why the fine sweep reports saturation at all.

**Roughly a third of the wheel (40–58, 90–118) is amber and yellow, which the
palette bans.** Worth knowing before anyone builds an idle colour chase: those
slots are off-limits by the same rule that governs the screens.

**Colour costs a lot of light.** Open white reached peak 236 at dimmer 86; the
green slot needed dimmer 128 to reach only 154. **The tracker should stay on
open white** (`ch5=0`, which is `TrackerConfig`'s behaviour today, since it never
writes ch5) — a spotlight following a person at night wants every lumen. Colour
belongs to the idle/show state, not to tracking.

### 8.6b Pan travel physically verified — 540° confirmed

`pan_range_deg = 540` and `tilt_range_deg = 270` came from the manual (§5), and
the entire DMX↔angle mapping rests on them: get the pan span wrong and every
commanded aim is off by the ratio, which on the vehicle would read as a bad
`pan_gain` or a crooked mount and cost a calibration session to find.

Measured 2026-08-09 by driving pan end-stop to end-stop as a smooth 16-bit ramp
and counting rotations of the head: **one full turn plus a half**, finishing
facing opposite the start. That is 540°, so the manual is trustworthy here and
nothing downstream changes.

Cheap discriminator worth reusing on any replacement fixture: it is the *final
facing* that identifies the range, not the count. 360° ends facing the way it
started, 540° ends facing backwards, 450°/630° end at right angles.

At `pan = 0` this fixture faces its **LED-config-button side** — i.e. the button
side is one mechanical end stop, not the middle. **Mount the head so the
vehicle's forward direction sits near the middle of pan travel** (~270). That
leaves the full swing available either way before a stop, and keeps the seam
equivalence in §8.6c out of the picture entirely.

### 8.6c A calibrated pan centre used to aim at an end stop (fixed)

`update` clamped the pan target into `[0, pan_range]` *before* asking for the
mechanically-equivalent angle, and `nearest_equivalent_pan` seeded its search
with the raw target and kept it unless an in-range candidate was strictly
nearer. Together: at a calibrated `pan_center_deg` of 60, a contact at az −80
computes to −20, is clamped to **0**, and the head parks on its end stop
pointing at nothing — while the reachable equivalent **340** (the same physical
direction) was available the whole time.

Invisible at the shipped centre of 270, where ±90° of reach never leaves
[180, 360]. It would have first appeared during on-vehicle calibration and read
as a bad mount. Both halves were needed — reordering alone does not fix it,
because the seeded search still returns the unreachable value.

### 8.7 Gobo wheel, mapped the same way

`ch6` 0–63 are the fixed patterns (64–127 is shake, 128+ is auto-change — both
out of scope). **Eight slots, exactly 8 DMX units each**, so the safe values are
the centres: **4, 12, 20, 28, 36, 44, 52, 60**. Boundaries measured at 6→8,
14→16, 22→24, 30→32, 38→40, 46→48, 54→56.

| `ch6` | Centre | Shape |
|---|---:|---|
| 0–7 | **4** | open — plain round spot, no pattern |
| 8–15 | 12 | rose / layered concentric swirl |
| 16–23 | 20 | dot rosette — ring of ovals around an open centre |
| 24–31 | **28** | **triangle outline** |
| 32–39 | **36** | **six-pointed star** |
| 40–47 | 44 | sunburst — wavy rays around an open centre |
| 48–55 | 52 | pinwheel — curved blades |
| 56–63 | 60 | shattered fragments |

**Only two read as vector geometry: the triangle (28) and the six-pointed star
(36).** Those are the ones that sit with an 80s phosphor-vector aesthetic; the
rose, rosette, sunburst, pinwheel and fragments are disco florals and will look
like a hired dancefloor light, not like Zodiac.

**The tracker must stay on the open slot.** `TrackerConfig` never writes ch6, so
it defaults to 0 — correct, and for the same reason as open white: a patterned
beam thrown at a person is harder to see, not easier. Gobos belong to the idle
state.

### 8.8 Method note

Observation lag over chat made single-frame tests ambiguous more than once —
a report would arrive describing the *previous* frame. **Alternating a channel
on a fixed cycle and asking "what repeats?" is lag-proof** where "what do you
see now?" is not. The 9-channel finding was confirmed with a mutation test
(change exactly one channel, predict the change, verify) rather than by
accumulating agreeable evidence.
