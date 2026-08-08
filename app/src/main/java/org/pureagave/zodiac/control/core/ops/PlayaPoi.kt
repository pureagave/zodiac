package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.navigation.BRC_AXIS_BEARING_DEG_2025
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.clockToBearing
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
    /**
     * Where the artist is from, e.g. "Long Beach, CA, United States". Present
     * on 100% of the 2026 art feed.
     */
    val hometown: String? = null,
    /**
     * The artist's own description of the piece. This is the answer to the
     * question people actually ask from a moving art car — "what *is* that?" —
     * and the feed carries it for every piece, so there is no reason to make
     * anyone look it up later.
     */
    val description: String? = null,
    /** Placement category, e.g. "Open Playa" / "Deep Playa". 98% of the 2026 feed. */
    val category: String? = null,
    /** Funding programme, e.g. "Honorarium" / "Self-Funded". 100% of the feed. */
    val program: String? = null,
    /** The artist offers guided tours of the piece. */
    val guidedTours: Boolean = false,
    /** The piece is on the self-guided tour map. */
    val selfGuidedTour: Boolean = false,
    /** The artist is asking for hands — worth surfacing to a car full of people. */
    val needsVolunteers: Boolean = false,
)

/**
 * BRC lettered-street ring radii (metres from the Man), being the mean radius
 * of each ring's centreline in the Innovate GIS street data. Names change
 * yearly but the ring positions do not — 2025 and 2026 agree to within 0.3 m
 * on every ring — so this stays year-agnostic and places camp `intersection`
 * letters and keypad addresses alike.
 *
 * `BundledGisTest` re-measures these against the shipped assets, so a city
 * that does move gets caught rather than quietly mis-placing every address.
 * (That test is how the Esplanade's long-standing 752 m — 9.5 m short of both
 * years' data — was found.)
 */
val StreetRingRadiiM: Map<String, Double> =
    mapOf(
        "ESPLANADE" to 761.5, "A" to 894.0, "B" to 979.0, "C" to 1065.0,
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
