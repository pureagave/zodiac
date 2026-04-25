package ai.openclaw.zodiaccontrol.core.geo

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Equirectangular projection anchored on a fixed origin (the Golden Spike).
 * Distortion stays sub-meter within the 3 km city radius at BRC's latitude,
 * which is more than adequate for cockpit overlay rendering — no map library
 * required.
 */
class PlayaProjection(val origin: LatLon) {
    private val lat0Rad = Math.toRadians(origin.lat)
    private val cosLat0 = cos(lat0Rad)

    fun project(point: LatLon): PlayaPoint {
        val dLonRad = Math.toRadians(point.lon - origin.lon)
        val dLatRad = Math.toRadians(point.lat - origin.lat)
        return PlayaPoint(
            eastM = EARTH_RADIUS_M * cosLat0 * dLonRad,
            northM = EARTH_RADIUS_M * dLatRad,
        )
    }

    fun unproject(point: PlayaPoint): LatLon {
        val dLonRad = point.eastM / (EARTH_RADIUS_M * cosLat0)
        val dLatRad = point.northM / EARTH_RADIUS_M
        return LatLon(
            lon = origin.lon + Math.toDegrees(dLonRad),
            lat = origin.lat + Math.toDegrees(dLatRad),
        )
    }

    fun distanceMeters(
        a: LatLon,
        b: LatLon,
    ): Double {
        val pa = project(a)
        val pb = project(b)
        val dx = pa.eastM - pb.eastM
        val dy = pa.northM - pb.northM
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        const val EARTH_RADIUS_M: Double = 6_371_000.0
    }
}
