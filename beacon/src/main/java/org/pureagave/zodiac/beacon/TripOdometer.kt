package org.pureagave.zodiac.beacon

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Accumulates travelled distance from successive GNSS fixes via the haversine
 * formula. A [jitterFloorM] guards against a parked phone clocking miles from GPS
 * wander: a step shorter than the floor is ignored and the anchor is held, so
 * only real movement counts (and slow creep still accumulates once it clears the
 * floor). Tracks [tripMeters] (this session) and [totalMeters] (seeded from the
 * persisted lifetime value). Pure + stateful.
 */
class TripOdometer(
    totalSeedM: Double = 0.0,
    private val jitterFloorM: Double = DEFAULT_JITTER_FLOOR_M,
) {
    var tripMeters: Double = 0.0
        private set
    var totalMeters: Double = totalSeedM
        private set

    private var lastLat: Double? = null
    private var lastLon: Double? = null

    /** Add a fix; returns the metres added (0 on the first fix or a sub-floor step). */
    fun add(
        lat: Double,
        lon: Double,
    ): Double {
        val pLat = lastLat
        val pLon = lastLon
        if (pLat == null || pLon == null) {
            lastLat = lat
            lastLon = lon
            return 0.0
        }
        val d = haversineMeters(pLat, pLon, lat, lon)
        if (d < jitterFloorM) return 0.0 // hold the anchor; wait for real movement
        lastLat = lat
        lastLon = lon
        tripMeters += d
        totalMeters += d
        return d
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val DEFAULT_JITTER_FLOOR_M = 5.0

        fun haversineMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a =
                sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
