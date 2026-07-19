# Edge-box hardware — bill of materials & wiring

Everything the Jetson node needs to be a sealed, vehicle-powered, dual-camera
threat sensor. The Jetson and FLIR bundle are ordered; this list is the rest, so
nothing blocks bring-up when they arrive.

## Bill of materials

| # | item | est. | status / notes |
|---|---|---|---|
| 1 | **Jetson Orin Nano Super Dev Kit** | $249 | ordered (Arrow, MSRP) |
| 2 | **FLIR Lepton 3.5 + PureThermal 3** | $283 | ordered (GroupGets) — thermal, connects as **UVC over USB** |
| 3 | **NVMe M.2 2280 SSD, 256 GB** | ~$30 | **prefer over microSD** — SD cards die from vehicle vibration + playa heat. Boot JetPack from NVMe. |
| 4 | *(fallback)* microSD 64 GB UHS-I A2 | ~$12 | only if not using NVMe; carry a spare |
| 5 | **USB webcam — global shutter** (e.g. Arducam USB GS, ELP module) | ~$40–80 | daytime primary. Global shutter = no "jello" on a bumpy vehicle. A C920/Brio works but is rolling-shutter. |
| 6 | **Vehicle power: 12 V → 19 V DC-DC**, ≥5 A (barrel to match the kit) | ~$15–25 | the kit's 19 V/3.4 A brick is wall-only. Orin Nano draws ~7–25 W; size the converter with margin. |
| 7 | **Networking** — pick one | | see below |
| 7a | hardwire **Ethernet** to travel router | $0 | **recommended** for a fixed roof install — most reliable, zero driver fiddling |
| 7b | **Intel AX210** M.2 Key-E card + 2× IPEX antennas | ~$20 | onboard WiFi; native kernel support |
| 7c | USB WiFi dongle (Panda/Alfa) | ~$15 | fastest, ugliest |
| 8 | **Sealed enclosure** + cable glands | ~$25–40 | playa dust is abrasive and gets everywhere. Seal it; the kit heatsink needs some airflow — use a filtered vent or a large passive heatsink, not an open fan. |
| 9 | **Vibration-damped mount** (rubber isolators) | ~$10 | protects the SSD and keeps the cameras from shaking |
| 10 | short **shielded USB cables** (thermal + RGB) | ~$10 | keep runs short; USB over long thin cables browns out cameras |

> GPS/telemetry is the **Sensor Hub** node (XCover phone now, Pi later), *not*
> the Jetson — the roof GPS antenna belongs to that box. The Jetson is
> vision-only. Both broadcast on the same fleet bus.

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
