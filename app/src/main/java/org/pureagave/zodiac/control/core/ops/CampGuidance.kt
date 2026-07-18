package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Bearing + distance from the vehicle to a fixed target (the camp), for the
 * "return to camp" quick action and the dust-event "shelter bearing". Pure
 * geometry over the existing [PlayaProjection] — does not touch the navigator.
 *
 * @property bearingDeg true-north bearing (0..360, clockwise from N) to steer.
 * @property distanceM straight-line distance in metres.
 */
data class CampGuidance(
    val bearingDeg: Double,
    val distanceM: Double,
)

/**
 * Compute guidance from the live ego fix to [target]. Both points are projected
 * through [projection] (anchored on the Golden Spike) into the shared playa
 * metre frame, so this stays consistent with everything else the cockpit draws.
 */
fun campGuidance(
    ego: LatLon,
    target: LatLon,
    projection: PlayaProjection,
): CampGuidance {
    val e = projection.project(ego)
    val t = projection.project(target)
    val dEast = t.eastM - e.eastM
    val dNorth = t.northM - e.northM
    val bearing = ((Math.toDegrees(atan2(dEast, dNorth)) % 360.0) + 360.0) % 360.0
    return CampGuidance(bearingDeg = bearing, distanceM = hypot(dEast, dNorth))
}
