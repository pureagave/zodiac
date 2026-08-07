package org.pureagave.zodiac.control.ui.concepts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.pureagave.zodiac.control.core.vision.SurroundRing
import org.pureagave.zodiac.control.core.vision.VisionFeed
import kotlin.math.cos
import kotlin.math.sin

// The surround ring's own drawing surface, split out of DriverNightScreen so
// neither file carries two jobs: that one owns the forward perspective view and
// the screen's text furniture, this one owns the plan-view ring. The split is
// what keeps both back under detekt's TooManyFunctions threshold rather than
// raising it — the ring is a stack of small single-purpose DrawScope helpers by
// design, so no one function nests multiple decisions.
//
// Every *decision* still lives in SurroundRing, tested; this file only turns
// those decisions into pixels. See design/surround-driver-hud.md Phase 2.

// -- surround ring geometry (design/surround-driver-hud.md, Phase 2) ----------

// Ring radius as a fraction of screen height. 0.30 (not the perspective grid's
// own proportions) keeps the rim clear of both the destination name (top) and
// the MPH/status line (bottom ~0.90h) on the A54's ~2340x1080 landscape frame.
internal const val SURROUND_RING_RADIUS_FRACTION = 0.30f

// Angular spacing of the rim ticks, and how many of the 12 resulting ticks are
// "cardinal" (0/90/180/270 off the nose) and drawn longer.
private const val TICK_SPACING_DEG = 30f
private const val TICKS_PER_CARDINAL = 3
private const val CARDINAL_TICK_FRACTION = 0.10f
private const val MINOR_TICK_FRACTION = 0.05f

// Uncovered-rim dashes are sized relative to one tick-spacing arc's length,
// per the design doc's "~40% of the tick spacing".
private const val DOTTED_DASH_FRACTION = 0.40f

// Ego hull: a rounded rectangle in the vehicle's footprint proportions
// (longer than wide, nose up) rather than a point or triangle, so the near
// band has a "clear of my side" meaning instead of "clear of my centroid".
private const val HULL_WIDTH_FRACTION = 0.12f
private const val HULL_LENGTH_TO_WIDTH = 1.8f
private const val HULL_ALPHA = 0.35f
private const val HULL_CORNER_FRACTION = 0.3f

// The forward wedge radials are "faint" — dimmer than the already-dim NightGrid.
private const val WEDGE_ALPHA = 0.5f

// Ring blip geometry, and the extra weight a merged (memberCount > 1) blip
// draws with instead of a printed count — text is head-down reading.
private const val BLIP_RADIUS_FRACTION = 0.012f
private const val BLIP_HEAVY_SCALE = 1.4f
private const val BLIP_STROKE = 2f
private const val BLIP_HEAVY_STROKE = 3f

// Collision blips get an outward radial spoke, toward the rim — i.e. pointing
// back along the bearing the threat is coming FROM. That is deliberately the
// opposite of a velocity vector: it makes a closing contact findable by shape
// alone, since this display is dim by design and red-on-black at low
// brightness isn't a reliable sole channel.
private const val COLLISION_SPOKE_FRACTION = 0.08f

private const val FULL_CIRCLE_DEG = 360f
private const val HALF_TURN_DEG = 180f

/** Static ring chrome: rim (solid over covered arcs, dotted otherwise), ticks, forward wedge, ego hull. */
internal fun DrawScope.drawRingFurniture(
    visionFeed: VisionFeed,
    center: Offset,
    radius: Float,
) {
    drawRingRim(visionFeed, center, radius)
    drawRingTicks(center, radius)
    drawForwardWedge(center, radius)
    drawHull(center, radius)
}

/**
 * The rim is the only place sensor health lives on this HUD (2e): solid over
 * a bearing means "watched", dotted means "blind or demo — do not trust an
 * empty ring here". [VisionFeed.ABSENT] dots the whole rim in [NightRed]
 * since nothing is being watched at all; [VisionFeed.DEMO] dots it in
 * [NightGrid] since the coverage is fabricated, not real; [VisionFeed.LIVE]
 * draws [SurroundRing.COVERED_ARCS] solid and everything else dotted.
 */
private fun DrawScope.drawRingRim(
    visionFeed: VisionFeed,
    center: Offset,
    radius: Float,
) {
    when (visionFeed) {
        VisionFeed.ABSENT -> drawDottedRingArc(center, radius, -HALF_TURN_DEG, HALF_TURN_DEG, NightRed)
        VisionFeed.DEMO -> drawDottedRingArc(center, radius, -HALF_TURN_DEG, HALF_TURN_DEG, NightGrid)
        VisionFeed.LIVE -> {
            SurroundRing.COVERED_ARCS.forEach { arc -> drawSolidRingArc(center, radius, arc.start, arc.endInclusive, NightGrid) }
            SurroundRing.uncoveredArcs().forEach { arc ->
                drawDottedRingArc(center, radius, arc.start, arc.endInclusive, NightGrid)
            }
        }
    }
}

private fun DrawScope.drawSolidRingArc(
    center: Offset,
    radius: Float,
    fromBearingDeg: Float,
    toBearingDeg: Float,
    color: Color,
) {
    drawArc(
        color = color,
        startAngle = SurroundRing.screenAngleDeg(fromBearingDeg),
        sweepAngle = toBearingDeg - fromBearingDeg,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(2 * radius, 2 * radius),
        style = Stroke(1f),
    )
}

private fun DrawScope.drawDottedRingArc(
    center: Offset,
    radius: Float,
    fromBearingDeg: Float,
    toBearingDeg: Float,
    color: Color,
) {
    val tickArcLenPx = radius * Math.toRadians(TICK_SPACING_DEG.toDouble()).toFloat()
    val dashPx = tickArcLenPx * DOTTED_DASH_FRACTION
    drawArc(
        color = color,
        startAngle = SurroundRing.screenAngleDeg(fromBearingDeg),
        sweepAngle = toBearingDeg - fromBearingDeg,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(2 * radius, 2 * radius),
        style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx))),
    )
}

/** Every 30°, inward from the rim; longer at the four cardinal (0/90/180/270) bearings. */
private fun DrawScope.drawRingTicks(
    center: Offset,
    radius: Float,
) {
    val tickCount = (FULL_CIRCLE_DEG / TICK_SPACING_DEG).toInt()
    for (i in 0 until tickCount) {
        val bearing = -HALF_TURN_DEG + i * TICK_SPACING_DEG
        val cardinal = i % TICKS_PER_CARDINAL == 0
        val tickLen = radius * (if (cardinal) CARDINAL_TICK_FRACTION else MINOR_TICK_FRACTION)
        val (dirX, dirY) = unitVector(SurroundRing.screenAngleDeg(bearing))
        val outer = Offset(center.x + radius * dirX, center.y + radius * dirY)
        val inner = Offset(center.x + (radius - tickLen) * dirX, center.y + (radius - tickLen) * dirY)
        drawLine(outer, inner, NightGrid, 1f)
    }
}

/**
 * Two faint radials at ±PERSPECTIVE_ARC_DEG marking the arc the perspective
 * figures below also draw — so the ring is honest about which contacts are
 * "duplicated" as a figure and which are ring-only.
 */
private fun DrawScope.drawForwardWedge(
    center: Offset,
    radius: Float,
) {
    val faintGrid = NightGrid.copy(alpha = WEDGE_ALPHA)
    for (bearing in listOf(PERSPECTIVE_ARC_DEG, -PERSPECTIVE_ARC_DEG)) {
        val (dirX, dirY) = unitVector(SurroundRing.screenAngleDeg(bearing))
        drawLine(center, Offset(center.x + radius * dirX, center.y + radius * dirY), faintGrid, 1f)
    }
}

/** Dim, filled, nose-up rounded rectangle standing in for the vehicle's own footprint. */
private fun DrawScope.drawHull(
    center: Offset,
    radius: Float,
) {
    val width = radius * HULL_WIDTH_FRACTION
    val length = width * HULL_LENGTH_TO_WIDTH
    drawRoundRect(
        color = NightGreen.copy(alpha = HULL_ALPHA),
        topLeft = Offset(center.x - width / 2f, center.y - length / 2f),
        size = Size(width, length),
        cornerRadius = CornerRadius(width * HULL_CORNER_FRACTION),
    )
}

internal fun DrawScope.drawRingBlip(
    blip: SurroundRing.Blip,
    center: Offset,
    radius: Float,
) {
    val (dirX, dirY) = unitVector(blip.screenAngleDeg)
    val dist = radius * blip.radiusFraction
    val pos = Offset(center.x + dist * dirX, center.y + dist * dirY)
    val heavy = blip.memberCount > 1
    val blipRadius = size.height * BLIP_RADIUS_FRACTION * (if (heavy) BLIP_HEAVY_SCALE else 1f)
    val stroke = if (heavy) BLIP_HEAVY_STROKE else BLIP_STROKE
    val color = if (blip.collision) NightRed else NightGreen
    drawCircle(color = color, radius = blipRadius, center = pos, style = Stroke(stroke))
    if (blip.collision) {
        // Spoke points outward, toward the rim — i.e. back along the bearing
        // the threat is coming FROM. Deliberately the opposite of a velocity
        // vector; see COLLISION_SPOKE_FRACTION's doc for why.
        val spokeLen = radius * COLLISION_SPOKE_FRACTION
        drawLine(pos, Offset(pos.x + spokeLen * dirX, pos.y + spokeLen * dirY), color, stroke)
    }
}

internal fun unitVector(screenAngleDeg: Float): Pair<Float, Float> {
    val a = Math.toRadians(screenAngleDeg.toDouble())
    return cos(a).toFloat() to sin(a).toFloat()
}
