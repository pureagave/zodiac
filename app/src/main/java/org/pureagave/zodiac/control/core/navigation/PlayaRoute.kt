package org.pureagave.zodiac.control.core.navigation

import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.model.StreetKind
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A drivable route across Black Rock City. [waypointsM] are the ordered corners
 * from the first approach point to the destination; inside the city they are
 * lifted straight off the real GIS street polylines (radial + ring arc), so the
 * drawn line lies on the drawn streets rather than on an idealised circle.
 * [entranceRadial] is the clock street we enter the city on (e.g. "5:30"), or
 * null when the destination is out on the open playa and a straight line is legal.
 */
data class PlayaRoute(
    val waypointsM: List<PlayaPoint>,
    val entranceRadial: String?,
)

/**
 * Route from [egoM] to [destM] the way you actually drive Black Rock City:
 * you may cut straight across the **open playa** (inside the Esplanade, out
 * past the city, or in the 10:00–2:00 mouth), but inside the city annulus you
 * follow the grid. For a camp/art in the city the route is:
 *
 *   playa → nearest entrance **radial** ∩ Esplanade → out that radial to the
 *   destination **ring** → along the ring to the address.
 *
 * Unlike the earlier idealised-polar version, every in-city corner is snapped
 * to the nearest vertex of the actual [city] street polylines: the entrance
 * radial's real points from the Esplanade out to the ring, then the ring arc's
 * real points along to the address. That keeps the route on the streets instead
 * of cutting across camps. Pure geometry over the projected [city] model —
 * unit-testable, no Compose. When the destination is on the open playa (or the
 * ring/radial can't be resolved) the route is a single straight leg. Being deep
 * in the grid already is handled approximately (the radial approach is skipped
 * once you're past the Esplanade) — see [nextWaypoint] for how guidance advances.
 */
fun routeTo(
    egoM: PlayaPoint,
    destM: PlayaPoint,
    city: PlayaCityModel,
): PlayaRoute {
    val straight = PlayaRoute(listOf(destM), null)
    val esplanadeR = city.arcsInnerToOuter.firstOrNull()?.let { city.arcRadiiM[it] }
    val destR = distanceFromOrigin(destM)
    // Can't model the city, or the target is the Man itself → straight line.
    if (esplanadeR == null || destR < ORIGIN_EPSILON_M) return straight

    val destBearing = bearingFromOriginTo(destM)
    val destClock = bearingToClock(destBearing, city.axisBearingDeg)
    val clockHours = destClock.hours + destClock.minutes / MINUTES_PER_HOUR

    // City routing only applies out in the blocks (beyond the Esplanade) and
    // within the 2:00–10:00 arc; everything inside the Esplanade, in the
    // 10:00–2:00 mouth, or past the outer road is free-drive open playa.
    val inCity =
        clockHours in CITY_MIN_CLOCK..CITY_MAX_CLOCK &&
            destR > esplanadeR + RING_MARGIN_M &&
            destR <= city.cityOuterRadiusM + OUTER_MARGIN_M

    // Real street polylines for the destination's ring (nearest by radius) and
    // its entrance radial (nearest by bearing). Both are gathered across every
    // matching GIS segment; when the target is open playa these come out empty.
    val ringName = if (inCity) city.arcRadiiM.minByOrNull { abs(it.value - destR) }?.key else null
    val arcPts = city.streetsM.filter { it.kind == StreetKind.Arc && it.name == ringName }.flatMap { it.pointsM }
    val entranceName =
        if (inCity) {
            city.streetsM
                .filter { it.kind == StreetKind.Radial }
                .minByOrNull { angularDistanceDeg(radialBearing(it), destBearing) }
                ?.name
        } else {
            null
        }
    val radialPts = city.streetsM.filter { it.kind == StreetKind.Radial && it.name == entranceName }.flatMap { it.pointsM }
    if (arcPts.isEmpty() || radialPts.isEmpty()) return straight

    val ringR = city.arcRadiiM.getValue(requireNotNull(ringName))
    return PlayaRoute(cityWaypoints(egoM, destM, esplanadeR, ringR, radialPts, arcPts), entranceName)
}

/**
 * Walk the real street polylines from the ego's approach to the address: out
 * the entrance radial's vertices from the Esplanade to the ring corner, then
 * along the ring arc's vertices to the point nearest the address. All corners
 * are snapped to actual street vertices, so the result lies on the streets.
 */
private fun cityWaypoints(
    egoM: PlayaPoint,
    destM: PlayaPoint,
    esplanadeR: Double,
    ringR: Double,
    radialPts: List<PlayaPoint>,
    arcPts: List<PlayaPoint>,
): List<PlayaPoint> {
    // Where on the destination ring the address sits (snapped to a real vertex).
    val destOnArc = arcPts.minByOrNull { hypot(it.eastM - destM.eastM, it.northM - destM.northM) } ?: arcPts.first()
    val destBearing = bearingFromOriginTo(destOnArc)

    val waypoints = mutableListOf<PlayaPoint>()
    val entryBearing: Double
    if (distanceFromOrigin(egoM) < esplanadeR) {
        // Inside the open centre: cross to the destination's entrance radial
        // and run its real vertices out from the Esplanade to the ring.
        val corner = radialPts.minByOrNull { abs(distanceFromOrigin(it) - ringR) } ?: radialPts.first()
        entryBearing = bearingFromOriginTo(corner)
        waypoints +=
            radialPts
                .sortedBy(::distanceFromOrigin)
                .filter { distanceFromOrigin(it) <= distanceFromOrigin(corner) + RADIAL_SLACK_M }
    } else {
        // Already out in the grid (or beyond it): come onto the destination ring
        // at OUR OWN bearing — a radial move — then follow the ring around. Never
        // cut a straight chord across the built city to a far-side entrance (the
        // old "bird flies straight there" bug when navigating back into town).
        val ringEntry =
            arcPts.minByOrNull { angularDistanceDeg(bearingFromOriginTo(it), bearingFromOriginTo(egoM)) }
                ?: arcPts.first()
        entryBearing = bearingFromOriginTo(ringEntry)
        waypoints += ringEntry
    }
    // Follow the ring's real vertices from the entry bearing around to the
    // address, ordered so the polyline runs entry → address.
    val lo = minOf(entryBearing, destBearing)
    val hi = maxOf(entryBearing, destBearing)
    val arcLeg = arcPts.filter { bearingFromOriginTo(it) in lo..hi }.sortedBy(::bearingFromOriginTo)
    waypoints += if (entryBearing <= destBearing) arcLeg else arcLeg.reversed()

    return waypoints
}

/**
 * The point the driver should currently steer toward: the far end of the route
 * segment the ego is nearest to. Stateless (so it survives route recomputes) —
 * as the ego advances along a leg, guidance rolls to the next corner. Before the
 * ego has reached the first corner (its projection clamps to the start of the
 * first segment) the first corner is returned. Null when the route is empty.
 */
fun nextWaypoint(
    waypointsM: List<PlayaPoint>,
    egoM: PlayaPoint,
): PlayaPoint? {
    if (waypointsM.isEmpty()) return null
    if (waypointsM.size == 1) return waypointsM[0]
    var bestK = 0
    var bestT = 0.0
    var bestDist = Double.MAX_VALUE
    for (k in 0 until waypointsM.size - 1) {
        val a = waypointsM[k]
        val b = waypointsM[k + 1]
        val abx = b.eastM - a.eastM
        val aby = b.northM - a.northM
        val abLenSq = abx * abx + aby * aby
        val t =
            if (abLenSq < SEGMENT_EPSILON) {
                0.0
            } else {
                (((egoM.eastM - a.eastM) * abx + (egoM.northM - a.northM) * aby) / abLenSq).coerceIn(0.0, 1.0)
            }
        val d = hypot(egoM.eastM - (a.eastM + t * abx), egoM.northM - (a.northM + t * aby))
        if (d < bestDist) {
            bestDist = d
            bestK = k
            bestT = t
        }
    }
    // Nearest point clamped to the very start of the first segment → the ego
    // hasn't reached the first corner yet, so steer to it; otherwise steer to
    // the far end of the leg the ego is currently on.
    return if (bestK == 0 && bestT <= SEGMENT_EPSILON) waypointsM[0] else waypointsM[bestK + 1]
}

private fun radialBearing(street: PlayaStreet): Double =
    bearingFromOriginTo(street.pointsM.maxByOrNull(::distanceFromOrigin) ?: street.pointsM.first())

/** Signed smallest angle from [b] to [a], in (−180, 180]. */
private fun signedDeltaDeg(
    a: Double,
    b: Double,
): Double {
    val raw = (a - b) % FULL_CIRCLE
    return when {
        raw > HALF_CIRCLE -> raw - FULL_CIRCLE
        raw <= -HALF_CIRCLE -> raw + FULL_CIRCLE
        else -> raw
    }
}

private fun angularDistanceDeg(
    a: Double,
    b: Double,
): Double = abs(signedDeltaDeg(a, b))

private const val ORIGIN_EPSILON_M = 1.0
private const val MINUTES_PER_HOUR = 60.0
private const val CITY_MIN_CLOCK = 2.0
private const val CITY_MAX_CLOCK = 10.0
private const val RING_MARGIN_M = 40.0
private const val OUTER_MARGIN_M = 150.0
private const val RADIAL_SLACK_M = 30.0
private const val FULL_CIRCLE = 360.0
private const val HALF_CIRCLE = 180.0
private const val SEGMENT_EPSILON = 1e-6
