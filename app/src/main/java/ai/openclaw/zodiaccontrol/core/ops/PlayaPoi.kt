package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.navigation.BRC_AXIS_BEARING_DEG_2025
import ai.openclaw.zodiaccontrol.core.navigation.ClockTime
import ai.openclaw.zodiaccontrol.core.navigation.clockToBearing
import kotlin.math.cos
import kotlin.math.sin

/** A place on the playa surfaced from the Burning Man API — art or a theme camp. */
enum class PoiKind { ART, CAMP }

/**
 * A discovery point of interest, projected into the shared playa-metre frame so
 * it can be drawn as a RADAR contact / MAP marker or used as a drive-to target.
 * [point] is null when the source location can't be placed (missing/odd fields).
 */
data class PlayaPoi(
    val uid: String,
    val name: String,
    val kind: PoiKind,
    val point: PlayaPoint?,
    val subtitle: String,
)

/**
 * BRC lettered-street ring radii (metres from the Man), measured from the 2025
 * Innovate GIS street data. Names change yearly but the ring positions are
 * stable, so this is a good year-agnostic placement for camp `intersection`
 * letters. Provisional — refine per year if needed (same caveat as [Camp]).
 */
val StreetRingRadiiM: Map<String, Double> =
    mapOf(
        "ESPLANADE" to 752.0, "A" to 894.0, "B" to 979.0, "C" to 1065.0,
        "D" to 1150.0, "E" to 1237.0, "F" to 1385.0, "G" to 1470.0,
        "H" to 1555.0, "I" to 1641.0, "J" to 1695.0, "K" to 1753.0,
    )

/** Art carries `gps_latitude`/`gps_longitude` — project directly. */
fun artPoint(
    lat: Double,
    lon: Double,
    projection: PlayaProjection,
): PlayaPoint = projection.project(LatLon(lon = lon, lat = lat))

/**
 * Camps carry `frontage` (clock, e.g. "2:00") + `intersection` (street letter,
 * e.g. "E"). Project via the clock bearing and the letter's ring radius. Returns
 * null when either is missing/unrecognised (e.g. a plaza/portal address).
 */
fun campPoint(
    frontage: String?,
    street: String?,
    axisBearingDeg: Double = BRC_AXIS_BEARING_DEG_2025,
): PlayaPoint? {
    val clock = frontage?.let(::parseClock) ?: return null
    val radius = street?.trim()?.uppercase()?.let { StreetRingRadiiM[it] } ?: return null
    val bearingRad = Math.toRadians(clockToBearing(clock, axisBearingDeg))
    return PlayaPoint(eastM = radius * sin(bearingRad), northM = radius * cos(bearingRad))
}

/** Parse a "H:MM" (or "H") clock string; 0 maps to 12. Null if out of range. */
private fun parseClock(raw: String): ClockTime? {
    val parts = raw.trim().split(":")
    val hourRaw = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    val hour = if (hourRaw == 0) 12 else hourRaw
    if (hour !in 1..12 || minute !in 0..59) return null
    return ClockTime(hour, minute)
}
