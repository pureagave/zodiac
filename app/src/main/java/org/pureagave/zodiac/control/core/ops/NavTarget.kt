package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon

/**
 * Destinations for the "drive to" quick-nav feature. Each carries its target
 * coordinate; the cockpit shows bearing + distance + a heading-relative arrow
 * to the active one (see `ui/ops/opsReadout`), defaulting to [HOME].
 *
 * Coordinates are the 2026 positions. The Man is the active Golden Spike origin;
 * the Temple is the 2026 Innovate GIS "The Temple" CPN; the camp is projected on
 * the 2026 grid (see [Camp]). All shift with the yearly city move.
 */
enum class NavTarget(
    val label: String,
    val location: LatLon,
) {
    HOME("HOME", Camp.GALACTIC_RELAY),
    MAN("MAN", GoldenSpike.ACTIVE),
    TEMPLE("TEMPLE", LatLon(lon = -119.201499636, lat = 40.78809942300006)),
}
