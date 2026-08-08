package org.pureagave.zodiac.control.core.passenger

import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The piece the vehicle is currently coming up on, with where it sits relative
 * to the nose.
 *
 * @property relBearingDeg −180..180, 0 dead ahead, positive to the right.
 */
data class ArtApproach(
    val poi: PlayaPoi,
    val distanceM: Double,
    val relBearingDeg: Double,
) {
    /** Roughly off to one side — you're alongside it now, not heading for it. */
    val abeam: Boolean get() = abs(relBearingDeg) > ABEAM_DEG

    private companion object {
        const val ABEAM_DEG = 55.0
    }
}

/**
 * Which art piece to put on screen while driving.
 *
 * "Nearest" is the wrong answer in a moving vehicle: the nearest piece is
 * frequently one you have already gone past, and a card that describes what is
 * behind you is worse than no card. So this takes only what lies **ahead**
 * ([arcDeg] either side of the nose) and picks by a cost that trades distance
 * against how far off the nose it sits — a piece dead ahead at 200 m is the one
 * you are about to meet; one at 100 m but 80° off is already sliding past.
 *
 * Deliberately independent of speed. Approaching at walking pace and
 * approaching at 20 kph want the same card; what changes is how long it stays,
 * and that is the caller's business (see the interrupt in `passengerScreen`).
 */
fun approachingArt(
    pois: List<PlayaPoi>,
    ego: PlayaPoint?,
    headingDeg: Double,
    radiusM: Double = APPROACH_RADIUS_M,
    arcDeg: Double = APPROACH_ARC_DEG,
): ArtApproach? {
    if (ego == null) return null
    return pois
        .asSequence()
        .filter { it.kind == PoiKind.ART }
        .mapNotNull { poi -> poi.point?.let { poi to it } }
        .mapNotNull { (poi, point) ->
            val east = point.eastM - ego.eastM
            val north = point.northM - ego.northM
            val distance = hypot(east, north)
            if (distance > radiusM) return@mapNotNull null
            val bearing = Math.toDegrees(atan2(east, north))
            val relative = wrapSigned(bearing - headingDeg)
            if (abs(relative) > arcDeg) return@mapNotNull null
            ArtApproach(poi, distance, relative)
        }
        // Cost, not raw distance — and the off-nose penalty is *superlinear*
        // on purpose. Bearing is a proxy for how long until the piece is behind
        // you: something 100 m away at 75 deg is being passed right now and
        // will be gone before anyone finishes reading, while 200 m dead ahead
        // is what people will be looking at in fifteen seconds. Squaring the
        // term is what makes the card arrive *before* the piece rather than
        // chasing whatever is momentarily closest.
        .minByOrNull {
            val offNose = abs(it.relBearingDeg) / arcDeg
            it.distanceM * (1.0 + OFF_NOSE_PENALTY * offNose * offNose)
        }
}

/** Normalise to −180..180. */
internal fun wrapSigned(deg: Double): Double {
    val wrapped = ((deg % FULL_TURN) + FULL_TURN) % FULL_TURN
    return if (wrapped > HALF_TURN) wrapped - FULL_TURN else wrapped
}

/**
 * How far ahead a piece registers. Wide enough that the card arrives before the
 * piece does — at 20 kph, 400 m is a little over a minute of warning, which is
 * time to read the name and look up.
 */
const val APPROACH_RADIUS_M: Double = 400.0

/**
 * Half-angle of the arc counted as "ahead". Beyond this you are passing it, not
 * approaching it, and the moment for the card has gone.
 */
const val APPROACH_ARC_DEG: Double = 80.0

/** How hard being off the nose counts against a piece. Tuned so 100 m at 75 deg
 *  loses to 200 m dead ahead — see the cost comment above. */
private const val OFF_NOSE_PENALTY = 3.0

private const val FULL_TURN = 360.0
private const val HALF_TURN = 180.0
