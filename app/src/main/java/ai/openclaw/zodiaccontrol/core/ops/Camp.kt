package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.LatLon

/**
 * Zodiac's home camp — Galactic Relay, whose bar (the Crocodile Milking Table)
 * is at **Heiau & 2:15** in Black Rock City. The "return to camp" / "shelter
 * bearing" features point here from the live GPS fix.
 *
 * **[GALACTIC_RELAY] is provisional.** The authoritative camp coordinate will
 * come from the Burning Man API camp geocode (released the Sunday before gates)
 * and the 2026 Golden Spike (published early July 2026); swap it in when either
 * lands. Until then this is a principled geometric estimate, not a guess:
 *   - **2:15** radial → 112.5° true (axis 45° + 2:15 clock offset).
 *   - **Heiau** is the 2026 *H* street; BRC ring *radii* are stable year over
 *     year while only the names change, so it shares the radius of 2025's H
 *     street **Herbert** — measured at **1555.2 m** from the Man across the
 *     2025 Innovate GIS street data.
 *   - Projected from that (bearing, radius) about [GoldenSpike.Y2025]
 *     (equirectangular, matching `PlayaProjection`).
 * Good to well within a block — ample for a bearing from km away; replace for
 * exact frontage.
 */
object Camp {
    /** Galactic Relay — Heiau & 2:15 (provisional; see class docs). */
    val GALACTIC_RELAY: LatLon = LatLon(lon = -119.1859412, lat = 40.7816113)

    /** Human-facing BRC address for labels. */
    const val GALACTIC_RELAY_ADDRESS: String = "Heiau & 2:15"
}
