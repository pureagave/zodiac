package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.LatLon

/**
 * Destinations for the "drive to" quick-nav feature. Each carries its target
 * coordinate; the cockpit shows bearing + distance + a heading-relative arrow
 * to the active one (see `ui/ops/opsReadout`), defaulting to [HOME].
 *
 * Coordinates are the 2025 positions (the Man is the Golden Spike origin; the
 * Temple + camp are provisional until the 2026 Golden Spike / BM API geocode —
 * see [Camp]). All three shift slightly with the yearly city rotation.
 */
enum class NavTarget(
    val label: String,
    val location: LatLon,
) {
    HOME("HOME", Camp.GALACTIC_RELAY),
    MAN("MAN", GoldenSpike.Y2025),
    TEMPLE("TEMPLE", LatLon(lon = -119.196622, lat = 40.791815)),
}
