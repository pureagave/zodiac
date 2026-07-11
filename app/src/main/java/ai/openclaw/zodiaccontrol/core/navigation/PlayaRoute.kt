package ai.openclaw.zodiaccontrol.core.navigation

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.model.StreetKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A drivable route across the BRC polar grid. [waypointsM] are the ordered
 * corners from the first approach point to the destination (the arc leg is
 * sampled into several points so it curves along the ring). [entranceRadial]
 * is the clock street we enter the city on (e.g. "2:30"), or null when the
 * destination is out on the open playa and a straight line is legal.
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
 * Pure geometry over the projected [city] model — unit-testable, no Compose.
 * When the destination is on the open playa (or there are no arcs to route
 * against) the route is a single straight leg. Being deep in the grid already
 * is handled approximately (the entrance leg is skipped once you're past the
 * Esplanade) — see [nextWaypoint] for how guidance advances along the legs.
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
    val entrance =
        city.streetsM
            .filter { it.kind == StreetKind.Radial }
            .minByOrNull { angularDistanceDeg(radialBearing(it), destBearing) }
    if (!inCity || entrance == null) return straight

    val entBearing = radialBearing(entrance)
    val ringR = city.arcRadiiM.minByOrNull { abs(it.value - destR) }?.value ?: destR

    val waypoints = mutableListOf<PlayaPoint>()
    // Skip the Esplanade entrance once you're already out in the grid.
    if (distanceFromOrigin(egoM) < esplanadeR) waypoints += polarPoint(esplanadeR, entBearing)
    waypoints += polarPoint(ringR, entBearing) // ring ∩ entrance radial
    // Curve along the ring from the entrance radial to the address.
    val span = signedDeltaDeg(destBearing, entBearing)
    val segments = (abs(span) / ARC_STEP_DEG).roundToInt().coerceAtLeast(1)
    for (i in 1 until segments) waypoints += polarPoint(ringR, entBearing + span * i / segments)
    waypoints += destM

    return PlayaRoute(waypoints, entrance.name)
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

private fun polarPoint(
    radiusM: Double,
    bearingDeg: Double,
): PlayaPoint {
    val rad = Math.toRadians(bearingDeg)
    return PlayaPoint(eastM = radiusM * sin(rad), northM = radiusM * cos(rad))
}

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
private const val ARC_STEP_DEG = 6.0
private const val FULL_CIRCLE = 360.0
private const val HALF_CIRCLE = 180.0
private const val SEGMENT_EPSILON = 1e-6
