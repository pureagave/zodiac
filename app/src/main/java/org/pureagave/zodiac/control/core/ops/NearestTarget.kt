package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaProjection

/**
 * Build a [DriveTarget] pointing at whichever of [candidates] is closest to
 * [ego] (straight-line in the shared playa-metre frame), labelled [label].
 * Used for the dynamic "BATH" destination — the nearest toilet bank, which
 * re-resolves as the ego moves. Returns null when there's no fix or no
 * candidates. Pure → unit-testable without a device.
 */
fun nearestDriveTarget(
    label: String,
    ego: LatLon?,
    candidates: List<LatLon>,
    projection: PlayaProjection,
): DriveTarget? {
    if (ego == null || candidates.isEmpty()) return null
    val e = projection.project(ego)
    var best: LatLon? = null
    var bestSq = Double.MAX_VALUE
    for (c in candidates) {
        val p = projection.project(c)
        val dx = p.eastM - e.eastM
        val dy = p.northM - e.northM
        val sq = dx * dx + dy * dy
        if (sq < bestSq) {
            bestSq = sq
            best = c
        }
    }
    return best?.let { DriveTarget(label = label, location = it) }
}

/**
 * Signed heading error to steer onto a bearing: [targetBearingDeg] − [headingDeg],
 * wrapped to (−180, 180]. Positive = turn right (clockwise), negative = turn
 * left. Drives the heading-guidance chevron's offset and steer direction.
 */
fun relativeBearingDeg(
    targetBearingDeg: Double,
    headingDeg: Double,
): Double {
    val raw = (targetBearingDeg - headingDeg) % FULL_CIRCLE
    return when {
        raw > HALF_CIRCLE -> raw - FULL_CIRCLE
        raw <= -HALF_CIRCLE -> raw + FULL_CIRCLE
        else -> raw
    }
}

private const val FULL_CIRCLE = 360.0
private const val HALF_CIRCLE = 180.0
