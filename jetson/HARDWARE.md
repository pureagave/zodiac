# Edge-box hardware — bill of materials & wiring

Everything the Jetson node needs to be a sealed, vehicle-powered, dual-camera
threat sensor. The Jetson and FLIR bundle are ordered; this list is the rest, so
nothing blocks bring-up when they arrive.

**Assembly diagram:** `sensor-pod-assembly.svg` — front view · window-mount
cross-section · system layout · build order, in one sheet.

## Shopping list (status 2026-07-30)

**In hand / covered:**
- [x] Jetson Orin Nano Super Dev Kit **+ its included 19 V PSU** (bench power covered)
- [x] **NVMe M.2 2280 SSD, 512 GB** — in hand 2026-07-31 (512 > the 256 spec'd = more headroom for JetPack + models + recordings)
- [x] **FLIR Lepton Ultra Wide + PureThermal Mini USB** (ordered ~2026-08, on the way — **one thermal camera**: 1 board + 1 UW module). **Switched from the 3.5:** the UW is **160° FOV** (vs 57°), **non-radiometric**, and — **measured on the real board 2026-08-07** — **160×120 (4:3), not 120×120** as first recorded, at a native **9 fps**. Wide surround coverage fits the "people all around a slow art car" mission; non-radiometric is fine (zvision detects warm blobs by contrast/motion, not absolute °C). **Code implication — DONE (2026-08-03):** zvision now unprojects pixels through a real lens model (`--lens equidistant` for the fisheye) and defaults `--hfov` to 160. **The 160° is the DIAGONAL — use `fovref=d` (settled 2026-08-07).** FLIR never states the axis (product page, GroupGets, the R200 dewarping note all just say "160°"), so we settled it on physics: the board emits **160×120**, and 160° across the *width* of a 4:3 f-theta frame demands a **~200° diagonal** — not a lens that exists; on the square 120×120 "usable" area FLIR quotes it would be 226°. Read as the diagonal it is an ordinary fisheye and the true horizontal half-angle is **80° × 80/100 = 64°**, so the camera covers **±64°, not ±80°**. This is not cosmetic: `fov_ref` feeds `pixel_to_bearing`, so the wrong choice mis-aims **every edge bearing by up to 16°** — about 2.8 m of miss on a person 10 m away, which is where the tracker light gets pointed. Under real uncertainty we deliberately err toward the *narrower* reading: under-claiming coverage costs a little caution, over-claiming is the confident all-clear the blind-arc report exists to prevent. `LeptonUwFovReferenceTest` pins the arithmetic; the tablet's `SurroundRing.COVERED_ARCS` carries the matching ±64°. Widen it only against a real measurement or a FLIR figure that names the axis. **✅ Settled in code AND measured 2026-08-10.** `--fov-ref` now defaults to `d` (`rig.CameraMount.fov_ref`, `app.py`), so a camera spec that omits `fovref` inherits the diagonal reading instead of the ruled-out horizontal one. **Confirmed physically**, not just argued: a cold target at 85 cm off centre at 1.0 m — a true 40.4° — was reported by the running rig at 42.5°, implying a ~61° horizontal half-FOV against the 64° the config assumes (agreement to ~5%, within the slop of a hand-marked wall). The horizontal reading would have put the true half-FOV at 80° and made that same contact report as **32.3°**, nothing like the observation. `FovReferenceDefaultTest` pins the default and mutation-verifies it. The deployed rig already carried `fovref=d` explicitly, so the running vehicle never had the error — the exposure was a *latent* one for the next camera added without the flag. Mini USB is **not USB-C** — it takes a small USB-B-family cable (GroupGets' page says micro-B; the board in hand took a mini-B, so trust the board). The **PureThermal 3** is the USB-C one. A cable ships in the box. Enumerates plug-and-play as `/dev/videoN` via `uvcvideo` (`GroupGets PureThermal fw:v1.3.0`).
- [x] RGB camera — Arducam day/night IMX462 USB (`B0CQ4QDCXN`)
- [x] Camera-head housing (aluminum CCTV box)
- [x] Jetson enclosure — KKSB Orin Nano Super case (`B0FRJ1WBQF`)
- [x] Car power DC-DC buck converter
- [x] Networking — router + Ethernet cable
- [x] **DMX interface** (FTDI dongle) + **DMX cables** + **moving-head fixture**
- [x] **Thermal window — germanium D20 × 1 mm, 2-sided AR** (Amazon, ~$90, ordered 2026-08-02; D15 was cancelled). Upsized to 20 mm **for the UW's 160° FOV** — at 160° the window must be **mounted ~2 mm from the lens front** to avoid vignetting (required radius ≈ standoff × tan(80°) ≈ standoff × 5.7); D20 at ~2 mm clears essentially the full 160°, D15 would clip corners. **✅ Reconciled 2026-08-10 — the two numbers were never in conflict.** This sizing correctly uses the **80° diagonal** half-angle, because the window is a *circular* aperture and the rays that can clip on it are the **corner** rays, which sit at the full diagonal half-angle. The 64° from the FOV-reference decision is the **horizontal** half-angle, which governs bearings and coverage arcs — a different job. So `radius ≥ standoff × tan(80°) ≈ 5.7 × standoff` stands and ~2 mm for D20 is right; sizing with tan 64° ≈ 2.05 would permit ~4.9 mm and vignette the image corners, which is exactly where the widest-bearing contacts appear. When quoting the 5.7× rule, say which half-angle it uses and why: **80° for anything circular in the optical path, 64° for anything about bearing.** This paragraph and the "~1.7 mm" figures further down should be reconciled to one number. Don't go bigger — flat-window edge rays (~80° incidence) fall outside the AR coating anyway. (Thorlabs/Edmund $200–750 = lab overkill.) **Cut a scrap of HDPE for bench bring-up so a late shipment can't block the Lepton.**

**Still needed (cheap last-mile, no lead time):**
- [ ] **Gore/ePTFE breather vent ×2** (one per box)
- [ ] **Bracket vibration pads** — rubber isolators for the shared mount
- [ ] **Short shielded USB cables** (thermal + RGB + tether)
- [ ] Confirm the DMX chain has a **120 Ω XLR terminator** on the last fixture (commonly forgotten; separate from the cables)
- [ ] *(optional, likely skip)* IR illuminator — headlights already flood the scene; the **thermal is the night primary and needs no illumination at all**. Only revisit if night *RGB* range proves short. See note below.

**On headlights vs. an IR illuminator:** halogen headlights emit strong near-IR (700–1100 nm) that the no-IR-cut IMX462 sees well — a real bonus. **LED headlights emit almost no IR** (phosphor-white), so they help the RGB cam only with their *visible* light. Either way it barely matters: at night the **Lepton thermal is the primary detector and needs zero illumination**, and the headlights light the scene for the RGB cam regardless. So the illuminator is genuinely optional — its only unique value (invisible 940 nm "stealth" light) is moot on a headlight-on art car.
      *(moving-head fixture + its power are vehicle-level — spec with the lighting)*

## Bill of materials

> **The shopping list above is the current state; this table is the original
> costing.** Where they disagree, the list wins — it carries the later decisions.
> The rows that changed are marked **SUPERSEDED**.

| # | item | est. | status / notes |
|---|---|---|---|
| 1 | **Jetson Orin Nano Super Dev Kit** | $249 | ordered (Arrow, MSRP) |
| 2 | ~~FLIR Lepton 3.5 + PureThermal 3~~ | $283 | **SUPERSEDED** — switched to the **Lepton Ultra Wide + PureThermal Mini** (160° vs 57°, non-radiometric, 160×120 @ 9 fps). Note the *Mini* is not USB-C; the PureThermal **3** is. Still UVC over USB. |
| 3 | **NVMe M.2 2280 SSD** | ~$30 | **512 GB in hand** (the 256 GB here was the original spec). Prefer over microSD — SD cards die from vehicle vibration + playa heat. Boot JetPack from NVMe. |
| 4 | *(fallback)* microSD 64 GB UHS-I A2 | ~$12 | only if not using NVMe; carry a spare |
| 5 | **Daytime/low-light RGB — Arducam IMX462 USB (Sony STARVIS)** | ~$60–80 | day **and** night primary. Prefer the **day/night auto IR-cut** variant (Amazon `B0CQ4QDCXN`). Rolling-shutter is fine at a mutant vehicle's walking pace; if jello ever shows, fall back to a global-shutter mono (Arducam OV9281). See "RGB camera" below. |
| 5a | *(optional, likely skip)* **850/940 nm IR illuminator** | ~$15–25 | **Probably not buying** — see the headlights-vs-illuminator note above: the thermal is the night primary and needs no illumination, and headlights light the scene for the RGB cam anyway. Only revisit if night RGB range proves short. |
| 6 | **Power — 19 V, 5.5×2.5 mm barrel (centre-positive)** | ~$15–35 | bench brick + a car DC-DC to a clean 19 V — see "Power & thermal budget". |
| 7 | **Networking** — pick one | | see below |
| 7a | hardwire **Ethernet** to travel router | $0 | **recommended** for a fixed roof install — most reliable, zero driver fiddling |
| 7b | **Intel AX210** M.2 Key-E card + 2× IPEX antennas | ~$20 | onboard WiFi; native kernel support |
| 7c | USB WiFi dongle (Panda/Alfa) | ~$15 | fastest, ugliest |
| 8 | **Camera-head housing** — aluminum CCTV box + sun shield (IP66) | ~$40–80 | the "sensor pod" at top-centre. Flat front modifies easily: RGB behind the glass, thermal behind a cut port (item 8b). See "Enclosure & mounting". |
| 8a | **Jetson enclosure** — vented aluminum case (KKSB Orin Nano Super `B0FRJ1WBQF`, VESA mount; or Waveshare `B0CG38BS5S`) | ~$25–55 | get the **Orin** Nano version, NOT the old Jetson Nano (different board). Aluminum body = heatsink; keep it OUT of the sealed camera pod, mounted shaded, with the vent/fan filtered for dust. |
| 8b | **Thermal window** — **AR-coated germanium**. **Ordered: D20 × 1 mm, 2-sided AR** (the ~25–33 mm figure here predates the Ultra Wide switch — see the shopping list) | ~$25–60 | the thermal port. **AVOID glass / acrylic / polycarbonate — they block LWIR entirely.** Germanium is the standard (looks like a metal mirror; transparent only to the thermal). HDPE sheet is a cheap test-only substitute; open recess works but risks dust on the lens. |
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
- **FOV.** These ship with wide M12 lenses (~85° H) and the lens is
  interchangeable. Two different jobs, so two different answers:
  - *Co-mounted forward RGB, registered pixel-for-pixel to the thermal:* match
    the thermal's FOV so a fixed 2D transform lines the frames up.
  - *Surround ring cameras (the current plan):* don't match anything — each is
    its own sensor covering its own arc. Pick lenses so the arcs overlap
    slightly and the ring closes, and declare each camera's real FOV, lens
    projection and mounting bearing in its `--camera` spec. `zvision -v` prints
    the resulting coverage and any blind sector.

  **Open:** RGB count and lens FOV are still TBD; that choice is what decides
  whether the ring closes. Note the UW thermal's 160° means the *thermal* alone
  already covers the forward 160°, so the RGB ring mainly owns the sides and rear.
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

- **Recommended (revised 2026-08-07) — a separate, tiny box for the thermal.**
  The CCTV box is far bigger than the thermal camera needs: the PureThermal Mini
  USB board is **19.5 × 15.32 mm**, and the whole camera with the Lepton in its
  socket is roughly **20 × 16 × 12 mm** plus the mini-B connector overhang. Give
  it its own pod and leave the RGB in the small housing it already ships with.
  - **Why a thick-walled box is fine anyway — nose the lens into the bore.** At
    160° the D20 window has to sit ~1.7 mm from the lens (below), which reads
    like "the front wall must be paper-thin". It doesn't: bore the port to the
    window's own diameter, seat the window in a counterbore on the **outer**
    face, and push the board forward on standoffs until the **lens sits inside
    that bore**, ~1–1.5 mm behind the window's rear face. The bore wall is then
    *beside* the lens, not in front of it, so it never enters the cone and wall
    thickness stops mattering. The Lepton module (≈11.7 × 12.7 mm) drops into a
    Ø20 bore with room to spare, and its lens front is ~11.5 mm above the PCB —
    so the PCB lands 5–10 mm behind the inner face on ordinary M2 standoffs.
  - **The box: Hammond `1550Z101`** — **50 × 45 × 24 mm**, diecast aluminum,
    one-piece silicone gasket, IP66/67/68 + NEMA 4X, −40 °C to +150 °C, ~$15–20
    (Digi-Key / Mouser / Jameco). The **6 mm lid** is the part that matters: it
    is thick enough to hold a proper counterbored window seat with an O-ring
    groove, and it is the only face that needs machining. Amazon-tomorrow
    fallback if the wait is the problem: Fielect `B0CGV3PC7B`, 64 × 58 × 35 mm,
    IP65 — bigger and a lesser seal, but still a fraction of the CCTV box.
  - **Cable exit:** feed the **mini-B end first, from outside in**, through an
    M12/M16 gland — the mini-B plug is ~7.5 × 4.5 mm and clears an 8 mm bore,
    whereas the USB-A end (12 × 4.5 mm) never will. Gland on a side wall, Gore
    vent on the back.
  - **Does someone already sell a Lepton housing? Surveyed 2026-08-07 — no, not
    one that is sealed.** What exists splits cleanly in two: (a) *indoor shells*
    — GroupGets' official OpenMV case, the `GG-24` breakout case, and a
    community **3D-printable hard enclosure built around the PureThermal Mini's
    exact PCB** (PETG, friction-fit, ~1 hr print, cults3d). Dimensionally ideal,
    but unsealed and its window module is LDPE grocery-bag film. (b) *sealed
    germanium housings* — moviTHERM IP67/IP69K and FLIR's own `T912366`, with
    DLC/AR germanium already installed, but built for A-series/Boson bodies at
    several hundred to low-thousands. Nobody makes the intersection, which is
    why we build it. Don't re-survey this.
  - **Field spare — polyolefin/LDPE shrink film.** The community's outdoor-Lepton
    testing (hackaday.io/project/159615) found sandwich bag, cling wrap and
    polyolefin shrink film all quite transparent to LWIR; every objection they
    raised was **temperature error from film reflections**, which does not apply
    to us — the UW is non-radiometric and zvision detects by contrast/motion,
    not absolute °C. A film window also has **essentially zero standoff**, so it
    sidesteps the 1.7 mm vignetting constraint entirely: no counterbore, no
    O-ring seat, no brittle optic. It is not the primary because LDPE softens
    well below what a dark pod reaches in playa sun, and it abrades and
    punctures. **Cut a spare port ring sized for shrink film and pack it** —
    germanium is brittle, we will be far from a replacement, and a $1 film
    window keeps the thermal channel alive if it cracks on the drive out.
  - **Cost of splitting the pod:** two boxes means two independent mount angles.
    `rig.py` already carries a per-camera `mount_az`, so this is a calibration
    entry, not a code change — but bolt both pods to the *same* rigid bracket or
    the cross-camera dedup has nothing stable to work against.
- **Alternative — one aluminum CCTV "box" housing as the sensor pod** (flat glass
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
- **Mounting the windows (numbers, revised 2026-08-07 for the Ultra Wide):** the
  window is a *stop*, and a flat stop at standoff `s` only passes a half-angle
  `θ` if its radius `r ≥ s · tan θ`. At the UW's 80° half-angle `tan θ = 5.67`,
  so the ordered **D20 (r = 10 mm) window must sit within 10 / 5.67 ≈ 1.7 mm of
  the lens front** — and every ring in front of it (retainer lip, bezel, shroud
  edge) has to clear the same cone at *its* own standoff. This is the single
  tightest number in the pod, and it is why the lens goes *into* the port bore
  rather than behind the wall. The old "~25 mm window, ~57° FOV" note was
  written for the Lepton 3.5 and is 3× more forgiving than what we now have;
  ignore it. The surround is *out of the optical path*, so
  it can be any opaque, heat/UV-stable material. Best: a **custom two-port front
  plate** (3D-printed **ASA/ABS**, not PLA — PLA sags in playa heat; or laser-cut
  aluminum) with a counterbored seat + O-ring / high-temp RTV seal + light
  retainer per window. Simpler: seat the germanium in a drilled hole on a
  shoulder and seal the surround with RTV or a plastic bezel. **Germanium is
  brittle** — seat it on a compliant O-ring (never metal-on-metal), don't
  over-torque the retainer, and keep RTV off the clear aperture (mask the face,
  seal the edge only). Recess both windows behind the shroud so dust/rain don't
  hit them head-on and you can wipe them.
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

- **Supply:** the kit powers from a **5.5×2.5 mm barrel, centre-positive**; the
  carrier accepts **9–20 V** but use **19 V** for full MAXN.
  - *Bench:* the kit's 19 V/2.37 A adapter, or a 19 V/≥3 A (65 W) brick for
    headroom.
  - *Car:* **never wire raw 12 V to the barrel** — it dips below 9 V on cranking
    and spikes on load-dump. Use a rugged DC-DC to a **clean, stable 19 V, ≥4 A**,
    on a **fused** feed. Pick: a **sealed/potted boost** (Oumefar 12→19 V 8 A
    152 W IP67, `B08FC4WM16`) — set/lock the pot to 19.0 V — **plus a fuse (~10 A)
    + an automotive load-dump TVS** on its input (a boost passes a 40 V spike
    through; the TVS clamps it). *Or* a true **buck-boost** (LTC3780 adjustable, set
    to 19 V, mounted inside the dust-proof box) for inherent up/down regulation.
    Same 5.5×2.5 mm barrel tip as bench.
- **Draw:** Orin Nano Super in MAXN ≈ up to 25 W; add ~2–5 W for two USB cameras.
  Budget the supply for ~40 W continuous (≈2 A @ 19 V) to be safe.
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
- [ ] RGB + thermal co-mounted rigidly on one bracket (small baseline). **The ~57° matched-FOV note here is obsolete** — it was written for the Lepton 3.5. The thermal is now the 160°-quoted Ultra Wide (±64° horizontal on the diagonal reading) and the RGB is ~85° horizontal, so the FOVs do *not* match and registration is by declared `az`/`fov`/`lens` in the rig spec, not by overlaying frames
- [ ] Cameras at top-centre facing the direction of travel

## Assembly order

See `sensor-pod-assembly.svg` for the labeled diagram. Each step is verifiable
before the next:

1. **Bench-flash the Jetson** — JetPack, MAXN-Super power mode; confirm
   `zvision --source fake` lights a tablet's DRIVER HUD (proves compute + bus +
   HUD before any optics are involved).
2. **Mount both cameras** rigidly on the front plate, small baseline, each
   aligned to its own port.
3. **Seat + edge-seal the windows** — germanium close to the Lepton lens, RGB
   glass in its port, on O-rings / high-temp RTV; keep sealant off the clear
   apertures.
4. **Fit a Gore vent** to each box (pod + Jetson).
5. **Mask BOTH windows**, paint the pod fluor-green, cure, unmask.
6. **Mount** — pod top-centre facing travel on the vibration-isolated bracket;
   Jetson box shaded + vented; USB-tether the cameras.
7. **Wire** — power 12 V → DC-DC → 19 V; network Ethernet or AX210.
8. **Heat check** — `tegrastats` in the sun (SoC < ~85 °C under load); confirm
   every camera enumerates (`v4l2-ctl --list-devices`); give each one a
   `--camera` spec with its measured mounting bearing, FOV and lens model, and
   check the verbose banner reports the blind sectors you expect.
9. **Verify end-to-end** — `--source thermal` (+ rgb), walk in front, confirm
   contacts on the DRIVER HUD.

## DMX tracker light (control)

The moving-head "tracker" light is a downstream consumer of the *same* detection
stream that drives the HUD — the Jetson already computes each contact's azimuth,
so pointing a light at a person is just mapping that az (+ vertical position) to
the fixture's pan/tilt DMX channels.

- **DMX interface (the buy):** a USB→DMX512 dongle on the Jetson.
  - **Note:** the once-ideal DMXKing ultraDMX Micro (~$65) is **discontinued**
    (successor = the ultraDMX MAX, ~$150 / 2-universe / overkill for one head).
  - **Recommended (budget, in stock): a cheap FTDI FT232 OLA dongle** (~$15–25) —
    works with OLA's `ftdidmx` plugin. The many near-identical clones are all the
    same thing; when picking one, look for a **genuine FT232R/RL** chip (NOT
    CH340), a **3-pin XLR DMX** output (avoid the bare "USB→RS485" terminal-block
    versions), and an **OLA / QLC+ / FreeStyler** compatibility note. It's
    host-timed, so **pin OLA to a dedicated Orin CPU core** so the ML workload
    can't jitter the DMX timing — that removes its only real downside. This is
    done, and not with `taskset`/`isolcpus`: `scripts/install-ola.sh` writes a
    systemd drop-in with `CPUAffinity` (default core 5, overridable via
    `DMX_CORE`). Verify with `systemctl show olad -p CPUAffinity`. Plenty for a single slow-slewing tracker head.
  - **Buffered / no-fuss:** DMXKing ultraDMX MAX (~$150, `B0C7HHYK18`) — the
    Micro's hardware-timed successor, bombproof but 2-universe overkill; or a
    **Eurolite USB-DMX512 PRO** (~$50) if you can find US stock. The Enttec DMX
    USB Pro (~$170) is reliable but overkill.
  - **Alternative:** an **Art-Net node** (DMX over Ethernet) to drive the light
    over the vehicle network and keep it off the Jetson's USB — fits the fleet
    bus, adds a hop.
- **Software (built):** the `zvision` runner drives the head in-process from the
  *same* per-frame contact list the HUD broadcaster gets — enable with
  `python -m zvision --dmx fake` (log-only, proves select→map→slew with no
  hardware) or `--dmx ola` (transmits). `zvision/tracker.py` is the pure mapping +
  target-selection + slew logic; `zvision/dmx.py` is the transport — `FakeDmxSink`
  (stdlib) and `OlaDmxSink`, which posts the universe to a local **OLA** (`olad`)
  over its HTTP API (no `ola` pip dep). `olad` owns the USB→DMX512 dongle
  (`ftdidmx`) and the timing. Calibrate `--dmx-pan-center` / `--dmx-pan-gain`
  against the camera FOV once on the vehicle. Local USB-DMX keeps it low-latency,
  which matters for a light that must *follow*.
- **Also need:** a 3-pin XLR **DMX cable** + a **120 Ω terminator** on the last
  fixture; and the moving-head fixture + its power (50–200 W, vehicle-level).
