package ai.openclaw.zodiaccontrol.core.navigation

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val DENOM_EPSILON = 1e-9

/**
 * Result of casting a ray against the trash fence: the exit point and the
 * scalar distance from origin along the ray.
 */
data class RayHit(val point: PlayaPoint, val distanceM: Double)

/**
 * Convert a true-north heading (degrees clockwise from N) into a unit vector
 * in playa metres — east is +X, north is +Y. The navigator uses this to
 * cast rays and to dot-product against the (Man → ego) vector for inbound /
 * outbound classification.
 */
fun headingUnitVector(headingDeg: Double): PlayaPoint {
    val rad = Math.toRadians(headingDeg)
    return PlayaPoint(eastM = sin(rad), northM = cos(rad))
}

/**
 * Bearing (degrees clockwise from true north) from origin to [p]. The Man
 * is at the playa origin (0, 0), so passing the ego point in directly gives
 * "bearing from the Man to ego."
 */
fun bearingFromOriginTo(p: PlayaPoint): Double {
    val raw = Math.toDegrees(atan2(p.eastM, p.northM))
    return ((raw % 360.0) + 360.0) % 360.0
}

/**
 * Shortest perpendicular distance from [p] to the polyline [pts]. Used by
 * "what street am I on" — we iterate every segment and keep the minimum so
 * the threshold check works whether the ego is mid-block or near a corner.
 */
fun pointToPolylineDistance(
    p: PlayaPoint,
    pts: List<PlayaPoint>,
): Double =
    when {
        pts.isEmpty() -> Double.MAX_VALUE
        pts.size == 1 -> hypot(p.eastM - pts[0].eastM, p.northM - pts[0].northM)
        else ->
            (0 until pts.size - 1)
                .minOf { i -> pointToSegmentDistance(p, pts[i], pts[i + 1]) }
    }

fun pointToSegmentDistance(
    p: PlayaPoint,
    a: PlayaPoint,
    b: PlayaPoint,
): Double {
    val abx = b.eastM - a.eastM
    val aby = b.northM - a.northM
    val abLenSq = abx * abx + aby * aby
    if (abLenSq < DENOM_EPSILON) return hypot(p.eastM - a.eastM, p.northM - a.northM)
    val t = (((p.eastM - a.eastM) * abx + (p.northM - a.northM) * aby) / abLenSq).coerceIn(0.0, 1.0)
    val cx = a.eastM + t * abx
    val cy = a.northM + t * aby
    return hypot(p.eastM - cx, p.northM - cy)
}

/**
 * Cast a forward ray from [origin] in direction [dirUnit] (a unit vector)
 * against a closed polygon ring. Returns the *first* intersection in front
 * of the origin, or null if the ray misses every edge. The trash fence is
 * effectively convex, so for an ego inside the fence this always returns a
 * hit — the exit point.
 */
fun rayPolygonForwardHit(
    origin: PlayaPoint,
    dirUnit: PlayaPoint,
    polygon: List<PlayaPoint>,
): RayHit? {
    if (polygon.size < 2) return null
    var bestT = Double.POSITIVE_INFINITY
    var bestPoint: PlayaPoint? = null
    val n = polygon.size
    for (i in 0 until n) {
        val a = polygon[i]
        val b = polygon[(i + 1) % n]
        val t = raySegmentT(origin, dirUnit, a, b)
        if (t != null && t in 0.0..bestT) {
            bestT = t
            bestPoint =
                PlayaPoint(
                    eastM = origin.eastM + t * dirUnit.eastM,
                    northM = origin.northM + t * dirUnit.northM,
                )
        }
    }
    return bestPoint?.let { RayHit(point = it, distanceM = bestT) }
}

/**
 * Solve `origin + t·dir = a + s·(b - a)` for `t`. Returns t (>=0) when the
 * ray crosses the segment (`s` clamped to [0, 1]); null when parallel,
 * behind the ray, or outside the segment.
 */
private fun raySegmentT(
    origin: PlayaPoint,
    dir: PlayaPoint,
    a: PlayaPoint,
    b: PlayaPoint,
): Double? {
    val rx = b.eastM - a.eastM
    val ry = b.northM - a.northM
    val denom = dir.eastM * ry - dir.northM * rx
    if (abs(denom) < DENOM_EPSILON) return null
    val ox = a.eastM - origin.eastM
    val oy = a.northM - origin.northM
    val t = (ox * ry - oy * rx) / denom
    val s = (ox * dir.northM - oy * dir.eastM) / denom
    return if (t >= 0.0 && s in 0.0..1.0) t else null
}

/**
 * Even-odd point-in-polygon test. Used to verify that an ego fix sits inside
 * the trash fence before we even bother computing a cue — guards against
 * weird states (sim spins way off the playa) emitting nonsense cues.
 */
fun pointInPolygon(
    p: PlayaPoint,
    polygon: List<PlayaPoint>,
): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        val crosses = (pi.northM > p.northM) != (pj.northM > p.northM)
        if (crosses) {
            val xCross = pj.eastM + (p.northM - pj.northM) * (pi.eastM - pj.eastM) / (pi.northM - pj.northM)
            if (p.eastM < xCross) inside = !inside
        }
        j = i
    }
    return inside
}

/** Dot product of two playa vectors (treated as 2D vectors). */
fun dot(
    a: PlayaPoint,
    b: PlayaPoint,
): Double = a.eastM * b.eastM + a.northM * b.northM

/** Subtract two points as if they were vectors. */
fun minus(
    a: PlayaPoint,
    b: PlayaPoint,
): PlayaPoint = PlayaPoint(eastM = a.eastM - b.eastM, northM = a.northM - b.northM)

/** Distance from a point to the playa origin (the Man). */
fun distanceFromOrigin(p: PlayaPoint): Double = hypot(p.eastM, p.northM)
