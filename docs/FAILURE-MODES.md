# Failure modes — what still works when a piece dies

Playa reality: power drops, things get unplugged, boxes reboot. This is the
graceful-degradation map — for each component lost, what functionality survives.
**Verified against the code** 2026-08-19 (not aspirational). The theme: the
**two Samsungs are the resilient core**; everything else is an enhancement.

## The one-line summary

> As long as **one Samsung (S9+ *or* A54) has power**, you have **full independent
> navigation** — its own GPS, the on-device map, and the authority to set a
> destination — with **no beacon, no WiFi, and no Jetson**. The failure to guard
> against is **losing both Samsungs at once**.

## The dependency map

| Component | Provides | Who depends on it |
|---|---|---|
| **Beacon** (XCover) | GPS/heading/speed (NMEA) + sensor channels (`$ZAUD/$ZENV/$ZSHK/$ZBCN/$ZODO`) | the Fires (no GPS chip); the whole fleet's shared position |
| **Jetson** (zvision) | threat contacts (`ZTHREAT`) + coverage (`ZCOVER`) + tracker light | DRIVER threat ring only |
| **WiFi/router** | the multicast fleet bus (`239.7.7.x`) | all cross-device data |
| **Samsungs** (S9+/A54) | **own onboard GPS** + **nav-authority** (only they set/broadcast `$ZNAV`) | destination-setting for the whole fleet |
| **Fires** | passenger/aux displays | — (no GPS, nav-*followers* only) |

Key code facts behind this:
- GPS (`239.7.7.10`) and threats (`239.7.7.20`) are **independent channels** —
  `NavigationController` has zero threat/vision references.
- `FailoverLocationSource` fails NET→SYSTEM (own GPS) within ~3 s, **armed only
  where `FEATURE_LOCATION_GPS` is reported** (the Samsungs; Fires have no receiver
  — `docs/DEVICES.md`).
- Nav-authority = non-Amazon device (`BurnInDeviceProfile.visualModulationSupported`).
  A Fire's drive-to is a **gated no-op** (`CockpitViewModel`: `if (!navAuthority) return`).
- Map, routing, and POI cache are all **on-device** (bundled assets + `filesDir`).

## Scenario by scenario

### Lose the Jetson + cameras
- ✅ **All navigation full** — map, drive-to, routing, callouts (GPS from beacon + on-device map).
- ✅ RADAR/MAP concepts, DRIVER's nav, passenger cards (except "souls"), beacon sensors.
- ❌ DRIVER threat ring + forward thermal → **"NO VISION"** (honest, not fabricated).
- ❌ Collision/brake warnings, the "souls" card, auto-aim tracker light.
- **Verdict:** vision is an add-on; the core map-and-navigate mission is untouched.

### Lose the beacon (GPS source)
- ✅ **S9+ + A54 keep navigating** — auto-failover to their own GPS (~3 s). *Minor:*
  onboard GPS gives course-over-ground, so heading is solid only while moving.
- ❌ **Passenger Fires lose position** (no chip) → SEARCHING / last-known.
- ❌ Fleet-wide: beacon sensor channels (audio scope, `$ZENV` auto-dim → default brightness, shock, odometer).
- **Verdict:** not a single point of failure for the hero + driver.

### Lose WiFi / the router
- ✅ **Each Samsung navigates fully standalone** — own GPS + bundled map + on-device
  routing; still a nav-authority (device-type flag, not network-dependent), so each
  can set its own local target (just can't *share* it).
- ❌ **Fires become static** (no GPS + no beacon).
- ❌ `$ZNAV` sharing, threats, beacon sensors, cross-device coordination.
- **Verdict:** total network loss doesn't stop a Samsung from getting around. (The
  planned Jetson backup-AP would restore *fleet sharing* — not needed for a single
  Samsung to navigate.)

### Lose the hero (S9+)
- ✅ **If the A54 is up:** it's the other authority + has its own GPS → takes over
  setting/broadcasting the destination; Fires follow. Only the big screen is lost.
- ⚠️ **If only Fires remain (both Samsungs gone):** the weak spot —
  - Fires are **followers**: their drive-to is a no-op → **you cannot set a new destination.**
  - Fires have **no GPS** → 100% dependent on beacon + WiFi for position.
  - They can *show* the map + position and follow a pre-set target, nothing more.

### Lose one or more Jetson cameras (once the ring is deployed)
- ✅ **zvision keeps running** — a dead camera is skipped (`app.py`), the others keep producing threats.
- ✅ **A down camera is now VISIBLE** as a dotted-red BLIND wedge on the ring
  (RES-P2-1 / `ZCOVER`), flipping within ~3 s of a camera going non-delivering — it
  no longer masquerades as "all clear."
- ❌ Coverage of that camera's bearing sector (a blind wedge in that direction).
- **Which one matters:** losing the **forward** camera kills forward collision detection; a side/rear loss just drops that arc.
- **Today (single thermal):** the forward ±64° is COVERED, the rest of the ring renders BLIND — correct.

## The two real gaps (tracked in `tasks/open.md`)

1. **Fires-only cannot set a destination.** Nav-authority is Samsung-only; a Fire
   can't self-promote if no Samsung is present. Guard by keeping ≥1 Samsung alive;
   the code fix (a Fire falling back to *local* drive-to when it sees no authority
   on the bus) is a backlog item.
2. ~~A down camera reads as "all clear."~~ **FIXED** by RES-P2-1 (`ZCOVER` blind arcs).

## Operational takeaways

- **Put the S9+ and A54 on separate power/fusing** — one fault must not take both.
  They are the must-survive devices.
- The Fires, the beacon, the Jetson, and the network are all *enhancements* — losing
  any of them degrades gracefully and visibly, never silently.
