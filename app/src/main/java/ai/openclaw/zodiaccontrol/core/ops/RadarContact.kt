package ai.openclaw.zodiaccontrol.core.ops

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import kotlin.math.hypot

/** A placeable [PlayaPoi] paired with its metric distance from the scope centre (the ego). */
data class RadarContact(
    val poi: PlayaPoi,
    val distanceM: Double,
)

/**
 * Select the POIs to plot on the RADAR scope: those with a resolved location
 * within [rangeM] of [center], nearest first, capped at [max]. Pure geometry
 * over the shared playa-metre frame — no Compose — so the flagship contact
 * plot unit-tests without a device. POIs whose [PlayaPoi.point] is null (an
 * unplaceable address) are dropped.
 */
fun contactsWithinRange(
    pois: List<PlayaPoi>,
    center: PlayaPoint,
    rangeM: Double,
    max: Int,
): List<RadarContact> =
    pois.asSequence()
        .mapNotNull { poi ->
            poi.point?.let { RadarContact(poi, hypot(it.eastM - center.eastM, it.northM - center.northM)) }
        }
        .filter { it.distanceM <= rangeM }
        .sortedBy { it.distanceM }
        .take(max)
        .toList()

/**
 * M41A "ping" brightness for a blip at [blipAngleDeg] given the sweep arm at
 * [sweepDeg] — both degrees clockwise from straight up. Full brightness the
 * instant the arm passes the blip, then a linear fade over [fadeSpanDeg] of
 * trailing sweep down to [floor], so a contact stays faintly visible between
 * pings instead of blinking out. Result is clamped to [floor]..1.
 */
fun contactPulse(
    sweepDeg: Float,
    blipAngleDeg: Float,
    floor: Float = DEFAULT_CONTACT_FLOOR,
    fadeSpanDeg: Float = DEFAULT_FADE_SPAN_DEG,
): Float {
    val delta = (((sweepDeg - blipAngleDeg) % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
    if (delta >= fadeSpanDeg) return floor
    val lit = 1f - delta / fadeSpanDeg
    return floor + (1f - floor) * lit
}

const val DEFAULT_CONTACT_FLOOR: Float = 0.28f
const val DEFAULT_FADE_SPAN_DEG: Float = 200f
private const val FULL_CIRCLE: Float = 360f
