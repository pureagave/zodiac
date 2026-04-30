package ai.openclaw.zodiaccontrol.core.navigation

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.model.StreetKind

/**
 * "On a street" snap threshold in playa metres. Tuned to the typical BRC
 * street width (~30–50 ft = 9–15 m); 15 m gets us across nearly every
 * radial without false-positiving on adjacent arcs at intersections.
 */
const val ON_STREET_THRESHOLD_M = 15.0

/**
 * Compute the [NavigationCue] for an ego at [egoM] (playa metres, origin =
 * the Man) facing [headingDeg] (degrees clockwise from true north). Pure
 * function — all the geometry comes from [city]. The orchestration is:
 *
 *  1. Snap ego to a named street if it's within [ON_STREET_THRESHOLD_M].
 *  2. If snapped:
 *     - radial → inbound (Esplanade is the next stop) or outbound (last
 *       arc passed) based on heading vs (Man − ego);
 *     - arc → arc name + ticking clock from ego's bearing-from-Man.
 *  3. Otherwise off-street: cast the heading-ray at the trash fence.
 *     Outside the city ring AND heading outward → backward cast,
 *     [NavigationCue.AwayFromClock] (UI prefixes "−"). Otherwise
 *     [NavigationCue.TowardClock].
 */
fun computeNavigationCue(
    egoM: PlayaPoint,
    headingDeg: Int,
    city: PlayaCityModel,
): NavigationCue =
    when {
        city.trashFenceM.size < MIN_FENCE_POINTS -> NavigationCue.Unknown
        else ->
            nearestStreet(egoM, city.streetsM)
                ?.let { cueOnStreet(egoM, headingDeg, it, city) }
                ?: cueOffStreet(egoM, headingDeg, city)
    }

private fun nearestStreet(
    ego: PlayaPoint,
    streets: List<PlayaStreet>,
): PlayaStreet? {
    var best: PlayaStreet? = null
    var bestDist = ON_STREET_THRESHOLD_M
    for (s in streets) {
        val d = pointToPolylineDistance(ego, s.pointsM)
        if (d < bestDist) {
            bestDist = d
            best = s
        }
    }
    return best
}

private fun cueOnStreet(
    ego: PlayaPoint,
    headingDeg: Int,
    street: PlayaStreet,
    city: PlayaCityModel,
): NavigationCue =
    when (street.kind) {
        StreetKind.Radial -> radialCue(ego, headingDeg, street, city)
        StreetKind.Arc ->
            NavigationCue.OnArc(
                arcName = street.name,
                clock = bearingToClock(bearingFromOriginTo(ego), city.axisBearingDeg),
            )
    }

private fun radialCue(
    ego: PlayaPoint,
    headingDeg: Int,
    street: PlayaStreet,
    city: PlayaCityModel,
): NavigationCue {
    val heading = headingUnitVector(headingDeg.toDouble())
    // Man is at the origin; (Man − ego) is just (−ego). Inbound when the
    // heading vector has positive dot with the toward-Man direction.
    val inbound = dot(heading, PlayaPoint(eastM = -ego.eastM, northM = -ego.northM)) > 0
    return if (inbound) {
        NavigationCue.OnRadialInbound(radialName = street.name, nextArc = INNERMOST_ARC)
    } else {
        NavigationCue.OnRadialOutbound(radialName = street.name, lastArc = lastArcPassed(ego, city))
    }
}

/**
 * Walk the inner-to-outer arc list and return the name of the most-recent
 * one ego has already crossed. If ego is still inside Esplanade (no arc
 * passed yet) we fall back to the city's innermost arc — the user's spec
 * doesn't quite cover this corner so [INNERMOST_ARC] keeps the cue stable
 * rather than going blank.
 */
private fun lastArcPassed(
    ego: PlayaPoint,
    city: PlayaCityModel,
): String {
    val r = distanceFromOrigin(ego)
    return city.arcsInnerToOuter
        .takeWhile { name -> (city.arcRadiiM[name] ?: Double.MAX_VALUE) <= r }
        .lastOrNull()
        ?: INNERMOST_ARC
}

private fun cueOffStreet(
    ego: PlayaPoint,
    headingDeg: Int,
    city: PlayaCityModel,
): NavigationCue {
    val heading = headingUnitVector(headingDeg.toDouble())
    // ego vector ≡ (ego − Man); positive dot means heading away from Man.
    val awayFromCity = distanceFromOrigin(ego) > city.cityOuterRadiusM && dot(heading, ego) > 0
    val rayDir =
        if (awayFromCity) PlayaPoint(eastM = -heading.eastM, northM = -heading.northM) else heading
    val hit =
        if (pointInPolygon(ego, city.trashFenceM)) {
            rayPolygonForwardHit(ego, rayDir, city.trashFenceM)
        } else {
            null
        }
    return hit?.let {
        val clock = bearingToClock(bearingFromOriginTo(it.point), city.axisBearingDeg)
        if (awayFromCity) {
            NavigationCue.AwayFromClock(clock = clock, distanceM = it.distanceM)
        } else {
            NavigationCue.TowardClock(clock = clock, distanceM = it.distanceM)
        }
    } ?: NavigationCue.Unknown
}

private const val MIN_FENCE_POINTS = 3
private const val INNERMOST_ARC = "Esplanade"
