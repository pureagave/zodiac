package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.geo.LatLon

/**
 * Zodiac's home camp — Galactic Relay, whose bar (the Crocodile Milking Table)
 * is at **Heiau & 2:15** in Black Rock City. The "return to camp" / "shelter
 * bearing" features point here from the live GPS fix.
 *
 * **[GALACTIC_RELAY] is a geometric estimate on the 2026 grid** (the exact camp
 * geocode comes from the BM API the Sunday before gates; swap it in then):
 *   - **2:15** radial → 112.5° true (axis 45° + 2:15 clock offset).
 *   - **Heiau** is the 2026 *H* street; BRC ring *radii* are stable year over
 *     year while only the names change, so it shares the H-street radius
 *     **1555.2 m** from the Man (measured on the Innovate GIS street data).
 *   - Projected from that (bearing, radius) about [GoldenSpike.ACTIVE], the 2026
 *     Man (equirectangular, matching `PlayaProjection`).
 * Good to well within a block — ample for a bearing from km away; replace for
 * exact frontage.
 */
object Camp {
    /** Galactic Relay — Heiau & 2:15, on the 2026 grid (estimate; see class docs). */
    val GALACTIC_RELAY: LatLon = LatLon(lon = -119.1908380, lat = 40.7779012)

    /** Human-facing BRC address for labels. */
    const val GALACTIC_RELAY_ADDRESS: String = "Heiau & 2:15"
}
