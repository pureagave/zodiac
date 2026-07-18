package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.clockToBearing
import kotlin.math.cos
import kotlin.math.sin

/**
 * A resolved "drive to" destination — a display [label] + its [location].
 * Unifies the fixed [NavTarget] presets (HOME / MAN / TEMPLE) with an arbitrary
 * discovery POI picked from the nearby panel, so the ops readout and the RADAR
 * target blip guide to either exactly the same way (bearing + distance via
 * [campGuidance]).
 */
data class DriveTarget(
    val label: String,
    val location: LatLon,
)

/** The preset destination as a [DriveTarget]. */
fun NavTarget.toDriveTarget(): DriveTarget = DriveTarget(label = label, location = location)

/**
 * A typed-in city address as a [DriveTarget]: the point at [ringName]'s ring
 * radius (via [StreetRingRadiiM]) on [clock]'s radial bearing, labelled e.g.
 * "2:15 & H". Null when the ring letter isn't recognised. Uses the shared
 * [projection] so it lands in the same frame as everything else the cockpit
 * draws and routes.
 */
fun addressTarget(
    clock: ClockTime,
    ringName: String,
    projection: PlayaProjection,
): DriveTarget? {
    val radius = StreetRingRadiiM[ringName.trim().uppercase()] ?: return null
    val bearingRad = Math.toRadians(clockToBearing(clock))
    val point = PlayaPoint(eastM = radius * sin(bearingRad), northM = radius * cos(bearingRad))
    return DriveTarget(label = "${clock.format()} & $ringName", location = projection.unproject(point))
}
