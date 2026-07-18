package org.pureagave.zodiac.control.core.geo

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
    val cosLat0: Double = cos(lat0Rad)
    val originLonRad: Double = Math.toRadians(origin.lon)
    val originLatRad: Double = Math.toRadians(origin.lat)

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

/**
 * Inline primitive-arg projection: takes raw `(lon, lat)` Doubles and
 * yields raw `(eastM, northM)` Doubles to a callback. Skips the
 * intermediate [LatLon] / [PlayaPoint] allocations the regular [project]
 * + [PlayaViewport.toScreen] chain would force per vertex. Use this in
 * the renderer's polyline walk where the per-frame allocation count
 * actually matters.
 */
inline fun PlayaProjection.projectInline(
    lon: Double,
    lat: Double,
    out: (eastM: Double, northM: Double) -> Unit,
) {
    val dLonRad = Math.toRadians(lon - origin.lon)
    val dLatRad = Math.toRadians(lat - origin.lat)
    out(
        PlayaProjection.EARTH_RADIUS_M * cosLat0 * dLonRad,
        PlayaProjection.EARTH_RADIUS_M * dLatRad,
    )
}
