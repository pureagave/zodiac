package org.pureagave.zodiac.control.core.geo

/**
 * Published Golden Spike (Man) coordinates for each Burning Man year.
 * Source: Innovate GIS dataset, taken as the inner endpoint of every radial
 * street and cross-checked against the CPN named "The Man".
 */
object GoldenSpike {
    val Y2025: LatLon = LatLon(lon = -119.20300709606865, lat = 40.78696344894566)

    /**
     * 2026 Man — the "The Man" CPN point in the 2026 Innovate GIS dataset. The
     * city translated ~583 m SW from 2025 but did NOT rotate: every 2026 radial
     * still puts the 12:00 axis at 45.0° true (Temple 44.9°, the 4:30 portal
     * exactly 180.0°, 3:00 → 135.1°, 9:00 → 315.1°), so [BRC_AXIS_BEARING_DEG]
     * is unchanged. Not yet wired as the active origin — see the 2026 migration
     * task (art/camp POIs stay 2025 until BM releases 2026 locations).
     */
    val Y2026: LatLon = LatLon(lon = -119.20788409599999, lat = 40.783247448000054)

    /**
     * The BRC year the cockpit is deployed for, and its Golden Spike origin — the
     * single place to flip when the city moves. Every projection, nav target, and
     * the bundled map layers key off these. Axis bearing is stable (still 45°, see
     * [org.pureagave.zodiac.control.core.navigation.BRC_AXIS_BEARING_DEG_2025]).
     */
    val ACTIVE: LatLon = Y2026
    const val ACTIVE_YEAR: Int = 2026
}
