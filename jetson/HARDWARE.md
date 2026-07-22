# Edge-box hardware — bill of materials & wiring

Everything the Jetson node needs to be a sealed, vehicle-powered, dual-camera
threat sensor. The Jetson and FLIR bundle are ordered; this list is the rest, so
nothing blocks bring-up when they arrive.

## Still to order (shopping list)

Ordered: Jetson Orin Nano Super, FLIR Lepton 3.5 + PureThermal 3. Everything else,
grouped for one pass:

- [ ] NVMe M.2 2280 SSD 256 GB (+ optional spare microSD)
- [ ] RGB camera — Arducam day/night IMX462 USB (`B0CQ4QDCXN`)
- [ ] *(optional)* 850/940 nm IR illuminator
- [ ] 12 V → 19 V DC-DC converter (≥5 A, matching barrel)
- [ ] Networking: Ethernet cable **or** Intel AX210 M.2 card + antennas
- [ ] Camera-head housing (aluminum CCTV box + sun shield, IP66)
- [ ] Jetson enclosure (vented / fan-cooled box)
- [ ] Thermal window — **AR-coated germanium** (the thermal can't see through glass/plastic)
- [ ] Gore/ePTFE breather vent ×2 (one per box)
- [ ] Shared bracket + rubber vibration isolators
- [ ] Shielded USB cables (short)

## Bill of materials

| # | item | est. | status / notes |
|---|---|---|---|
| 1 | **Jetson Orin Nano Super Dev Kit** | $249 | ordered (Arrow, MSRP) |
| 2 | **FLIR Lepton 3.5 + PureThermal 3** | $283 | ordered (GroupGets) — thermal, connects as **UVC over USB** |
| 3 | **NVMe M.2 2280 SSD, 256 GB** | ~$30 | **prefer over microSD** — SD cards die from vehicle vibration + playa heat. Boot JetPack from NVMe. |
| 4 | *(fallback)* microSD 64 GB UHS-I A2 | ~$12 | only if not using NVMe; carry a spare |
| 5 | **Daytime/low-light RGB — Arducam IMX462 USB (Sony STARVIS)** | ~$60–80 | day **and** night primary. Prefer the **day/night auto IR-cut** variant (Amazon `B0CQ4QDCXN`). Rolling-shutter is fine at a mutant vehicle's walking pace; if jello ever shows, fall back to a global-shutter mono (Arducam OV9281). See "RGB camera" below. |
| 5a | *(optional)* **850/940 nm IR illuminator** | ~$15–25 | extends night range past the camera's short onboard LEDs (people at 10–30 m). 940 nm = invisible; 850 nm = brighter, faint red glow. |
| 6 | **Vehicle power: 12 V → 19 V DC-DC**, ≥5 A (barrel to match the kit) | ~$15–25 | the kit's 19 V/3.4 A brick is wall-only. Orin Nano draws ~7–25 W; size the converter with margin. |
| 7 | **Networking** — pick one | | see below |
| 7a | hardwire **Ethernet** to travel router | $0 | **recommended** for a fixed roof install — most reliable, zero driver fiddling |
| 7b | **Intel AX210** M.2 Key-E card + 2× IPEX antennas | ~$20 | onboard WiFi; native kernel support |
| 7c | USB WiFi dongle (Panda/Alfa) | ~$15 | fastest, ugliest |
| 8 | **Camera-head housing** — aluminum CCTV box + sun shield (IP66) | ~$40–80 | the "sensor pod" at top-centre. Flat front modifies easily: RGB behind the glass, thermal behind a cut port (item 8b). See "Enclosure & mounting". |
| 8a | **Jetson enclosure** — separate vented / fan-cooled box | ~$25–50 | keep the Jetson OUT of the sealed, sun-baked camera pod (it throttles) — USB-tether the cameras to it. |
| 8b | **Thermal window** — **AR-coated germanium** (~25–33 mm) | ~$25–60 | the thermal port. **AVOID glass / acrylic / polycarbonate — they block LWIR entirely.** Germanium is the standard (looks like a metal mirror; transparent only to the thermal). HDPE sheet is a cheap test-only substitute; open recess works but risks dust on the lens. |
| 8c | **Gore/ePTFE breather vent** (adhesive or screw-in, IP66/68) | ~$5–15 | one per box — equalizes pressure + vents humidity while blocking water AND dust, so a sealed box doesn't become a sun-baked pressure cooker. |
| 9 | **Shared mount + bracket** (rubber vibration isolators) | ~$10–20 | one rigid bracket, top-centre, facing travel — also kills SSD / rolling-shutter jitter. |
| 10 | short **shielded USB cables** (thermal + RGB + camera↔Jetson tether) | ~$10–15 | keep runs short; use active USB if the tether exceeds ~3 m. |

> GPS/telemetry is the **Sensor Hub** node (XCover phone now, Pi later), *not*
> the Jetson — the roof GPS antenna belongs to that box. The Jetson is
> vision-only. Both broadcast on the same fleet bus.

## RGB camera (daytime / low-light — item 5)

- **Sensor:** Sony **STARVIS IMX462** (2 MP, ~0.001 lux, high near-IR sensitivity).
  Budget alternative: IMX291; more resolution/data: IMX678 (4K). All are USB UVC
  → shows up as `/dev/videoN`, no drivers.
- **Why no IR-cut = night capability.** Dropping the IR-cut filter makes the
  sensor near-IR sensitive (real night vision under an IR illuminator) and much
  better in low light. The cost is slightly-off daytime colour from IR bleed —
  irrelevant for ML detection. The **day/night ICR** variant (`B0CQ4QDCXN`) gets
  both: a *mechanical* filter that's in by day (true colour) and out at night
  (max sensitivity), auto-switched. That's the recommended buy for "max data,
  day and night." Its onboard 940 nm LEDs are short-range only — add item 5a to
  see people at 10–30 m.
- **FOV — match the Lepton (~57° H).** These ship with wide M12 lenses (~85° H).
  Either crop the RGB down to the thermal frame, or swap the **interchangeable
  M12 lens** to ~4–6 mm (~60°) for a tighter match. Set each camera's `--hfov`
  in zvision so bearings are correct.
- **Alignment is software, one-time.** Co-mount both cameras **rigidly on one
  bracket, close together (small baseline), same look direction**; then a fixed
  2D scale/offset/rotation registers the thermal ↔ RGB frames. At 10–30 m the
  parallax from a few-cm baseline is negligible, so no stereo rig is needed. A
  rigid plate is what keeps the calibration from drifting.
- **Where to buy:** Amazon — day/night `B0CQ4QDCXN` (recommended), plain
  low-light `B0CXXBD7KX`, 4K IMX678 `B0CXXFGTYB`. Industrial-grade: e-con
  Systems / Leopard Imaging (buy direct).

## Enclosure & mounting

**The wrinkle: the thermal camera can't see through glass or a polycarbonate
dome — LWIR is blocked.** So a standard security dome/housing works for the RGB
but not the thermal; the thermal always needs its own port with an
LWIR-transmissive window (germanium) or an open recess. That single constraint
drives the enclosure choice — you can't drop both behind one glass front.

**Build it as TWO separate windows side by side, one per camera — not a single
split pane:** germanium for the thermal, ordinary glass for the RGB. Germanium is
*opaque to visible light*, so the RGB can't share the germanium port; glass
blocks LWIR, so the thermal can't share the glass. Neither material does both
(the one that does, ZnSe, is expensive/tinted — skip it). In the aluminum box:
keep the housing glass for the RGB and cut a second port next to it for the
germanium, each aligned in front of its own lens.

- **Recommended — one aluminum CCTV "box" housing as the sensor pod** (flat glass
  front + sun shield, IP66, ~$40–80; e.g. GledeYeTec 14.5" `B0D49FF6GB`, or a
  compact 6" box `B09FQF27GD`). Cheap, buyable, looks the part on a sci-fi art
  car, and the flat front modifies cleanly: keep the glass for the RGB, cut a
  second port for the thermal behind an HDPE/germanium window (or open under the
  shroud). Both camera boards live here, top-centre, facing travel, on the shared
  bracket.
- **Germanium sourcing:** for a round port, buy a **stock Ø1" (25.4 mm) AR-coated**
  window and size the port to it — Thorlabs (`WG91050-E3`/`-F`, 8–12 µm) or
  Edmund / ISP Optics, ~$100–150. Truly custom size/shape → **Knight Optical** or
  **Crystran** (bespoke, single-unit, add a **DLC** hard coat for the exposed
  face). Cheap eBay/AliExpress germanium is fine for prototyping. Don't cut it
  yourself — it's a brittle semiconductor, ground/polished by optics shops.
  **Buy early: germanium supply is tight/pricey (export controls) and stock isn't
  always replenished.**
- **Premium / bombproof — Dotworkz BASH or S-Type** (IP68, IK10, purpose-built
  for AI/vision cameras on *mobile/fleet* deployments — literally the art-car use
  case; ~$150–400 at dotworkz.com). Seriously rugged and futuristic; still needs
  a thermal port. Worth it if you want it intentional and indestructible, overkill
  for a single burn.
- **Keep the Jetson in its OWN vented box, not the sealed camera pod.** Baking the
  compute in a sun-heated sealed housing on the playa = thermal throttling. Put
  the two low-heat camera boards in the sensor pod and tether them by USB to a
  separate vented/fan-cooled Jetson enclosure mounted lower or shaded.

## Cooling, venting & paint

**Vent — yes, but a membrane, not a hole.** A fully sealed box becomes a sun-baked
pressure cooker (and pumps moisture as it heats/cools); an open vent lets playa
dust straight in. The fix is a **Gore/ePTFE breather vent** (item 8c): the
membrane equalizes pressure and lets humidity out while blocking water *and* dust
(pores ~20,000× smaller than a water droplet). One per box.

- **Camera pod** — the cameras are low-power (~1–2 W each); the heat is almost all
  *solar*. Light/reflective paint + the housing's sun shroud (air gap over the
  top) + bolting the camera boards to the aluminum body (it heatsinks) + a Gore
  vent is enough — **no fan needed**. Keeping it cool also protects *thermal image
  quality*: the Lepton's sensor drifts when its body runs hot, not just at the
  survival limit.
- **Jetson box** — this is the real heat (~25 W) and must be actively managed:
  1. **Shade it** — the single biggest win. Mount the Jetson box inside/under the
     vehicle, not roof-top in the sun; it doesn't need to be at the cameras (USB
     tether).
  2. **Filtered airflow** — the kit's heatsink fan + a filtered intake/exhaust (or
     an IP-rated filtered fan). A big finned conduction enclosure can go fanless,
     but at 25 W in playa heat give it generous fins + shade.
  3. Sanity-check with `tegrastats` in the sun — the SoC throttles ~85–90 °C.

**Paint (fluorescent green is a good call — a light colour reflects sun, so it
*helps* cooling):**
- **Mask BOTH windows first.** Paint is 100% opaque to LWIR — even a thin coat
  kills the thermal — and obviously blocks the RGB. Mask the germanium window and
  the RGB glass before spraying.
- Don't paint over the Gore vent or a fan intake (clogs them).
- Fluorescent pigments fade under playa UV — cosmetic only; re-coat as wanted.

## Wiring

```
 12V vehicle ──▶ DC-DC 12→19V ──▶ Jetson barrel
                                    │
   Lepton/PureThermal ──USB──▶  Jetson  ──Ethernet──▶ travel router ──▶ tablets
   USB webcam ─────────USB──▶      │ (or M.2 AX210 WiFi)
                                    └─ NVMe SSD (boot)
```

## Power & thermal budget

- **Draw:** Orin Nano Super in MAXN ≈ up to 25 W; add ~2–5 W for two USB cameras.
  Budget the DC-DC for ~40 W continuous to be safe.
- **Heat:** the playa is hot and the box is sealed — the SoC will throttle if the
  heatsink can't breathe. Favor a large passive heatsink + a small filtered
  intake over an open fan (dust kills fans). Watch `tegrastats` during a hot run.
- **Vibration:** SSD > SD card here; isolate the mount; strain-relieve every USB
  connector.

## Environmental checklist (playa)

- [ ] Enclosure sealed against dust (gaskets + cable glands)
- [ ] Cooling path that doesn't ingest raw dust
- [ ] SSD boot (not SD) + a spare
- [ ] Every connector strain-relieved and taped
- [ ] Power converter fused on the 12 V side
- [ ] Cameras behind a cleanable window if enclosed; thermal needs an IR-transmissive window (germanium/HDPE) — **not glass**, glass blocks LWIR
- [ ] RGB + thermal co-mounted rigidly on one bracket (small baseline), FOVs roughly matched (~57°) for clean frame registration
- [ ] Cameras at top-centre facing the direction of travel
