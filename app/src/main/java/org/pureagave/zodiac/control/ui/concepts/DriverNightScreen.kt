package org.pureagave.zodiac.control.ui.concepts

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pureagave.zodiac.control.R
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.ops.campGuidance
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.SurroundRing
import org.pureagave.zodiac.control.core.vision.VisionFeed
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Night-driver palette: deliberately dim (this display must preserve the
// driver's dark adaptation), and restricted to green / red / purple — no white,
// no yellow. Danger red is the one thing allowed to be bright.
private val NightGreen = Color(0xFF009E4A)
private val NightGrid = Color(0xFF00421E)
private val NightPurple = Color(0xFFB874E0)
private val NightRed = Color(0xFFFF4848)

// Deep red for the locked-target brackets — lower-luminance than the alarm red,
// so the four brackets read as "locked" without flaring the driver's night eyes.
private val DeepRed = Color(0xFF9E1224)

private const val KPH_TO_MPH = 0.621371
private const val ARCH_HALF_SPAN_DEG = 29f
private const val THERMAL_HALF_FOV_DEG = 28f

// Level-of-detail swap for the contact figure: distant contacts (small) draw a
// compact head+shoulders "bust" that stays legible at a few pixels; once close
// (and there's detail to carry it) they switch to a full striding "walking"
// figure that reads unmistakably as a person.
private const val NEAR_SHAPE_THRESHOLD = 0.5f

// -- surround ring geometry (design/surround-driver-hud.md, Phase 2) ----------

// Ring radius as a fraction of screen height. 0.30 (not the perspective grid's
// own proportions) keeps the rim clear of both the destination name (top) and
// the MPH/status line (bottom ~0.90h) on the A54's ~2340x1080 landscape frame.
private const val SURROUND_RING_RADIUS_FRACTION = 0.30f

// Angular spacing of the rim ticks, and how many of the 12 resulting ticks are
// "cardinal" (0/90/180/270 off the nose) and drawn longer.
private const val TICK_SPACING_DEG = 30f
private const val TICKS_PER_CARDINAL = 3
private const val CARDINAL_TICK_FRACTION = 0.10f
private const val MINOR_TICK_FRACTION = 0.05f

// Uncovered-rim dashes are sized relative to one tick-spacing arc's length,
// per the design doc's "~40% of the tick spacing".
private const val DOTTED_DASH_FRACTION = 0.40f

// Just inside where the forward figure's THERMAL_HALF_FOV_DEG mapping runs off
// canvas — see drawThreat. Marks the arc the perspective view duplicates as
// figures, so the ring's wedge doesn't promise a figure that isn't drawn.
private const val PERSPECTIVE_ARC_DEG = 30f

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

/**
 * The "DRIVER" cockpit surface: a dim, hollow-vector night HUD (1983-arcade
 * lineage) for the person actually driving the vehicle in the dark. Thermal
 * contacts are drawn as hollow wireframe figures on a perspective grid; a
 * heading arch across the top shows the bearing to the active drive-to target
 * (on the open playa it reads the city entrance; in the city it reads the
 * destination). Nav data and the thermal contacts are live from [CockpitUiState]
 * — the contacts come from the routed threat source (a fake moving demo until
 * the FLIR edge box broadcasts real detections).
 */
@Composable
fun driverNightScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val typeface = remember { ResourcesCompat.getFont(context, R.font.orbitron) }
    val projection = remember { PlayaProjection(GoldenSpike.ACTIVE) }
    // The edge box fuses a ring of cameras and reports full-circle bearings.
    // The perspective figures below place a contact by az/THERMAL_HALF_FOV_DEG,
    // which only means something inside the arc the perspective mapping still
    // covers on canvas — PERSPECTIVE_ARC_DEG, matching the ring's forward wedge
    // (drawForwardWedge) so the wedge never promises a figure that isn't drawn.
    // Everything outside that arc, including the full rear half, is still on
    // the surround ring below — see ringBlips.
    val threats = state.threats.filter { abs(SurroundRing.wrapBearing(it.relAzDeg)) <= PERSPECTIVE_ARC_DEG }

    // The surround ring carries every contact, full-circle — the whole reason
    // it exists is to show the driver what the forward-only figures above
    // can't. BlipTracker is remembered so the MAX_BLIPS cap's hysteresis has
    // frame-to-frame state to stabilize against, per SurroundRing's doc.
    val ringBlipTracker = remember { SurroundRing.BlipTracker() }
    val ringBlips = remember(state.threats) { ringBlipTracker.blips(state.threats) }

    // Relative bearing to the active target, clamped onto the heading arch.
    val relDeg =
        state.egoFix?.let { ego ->
            state.activeDriveTarget?.let { target ->
                val b = campGuidance(ego.location, target.location, projection).bearingDeg
                normalizeSigned(b - state.headingDeg)
            }
        } ?: 0.0
    val mph = (state.speedKph * KPH_TO_MPH).roundToInt()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDriverHud(state, threats, ringBlips, relDeg.toFloat(), typeface)
        }
        // Speed (purple = live data) bottom-right, and the status line
        // bottom-left — kept as real Compose text for crispness. The status
        // is evaluated over ALL threats (not the forward-filtered figure
        // list) so the count and the alert both stay honest about the whole
        // picture; SurroundRing.hudStatus decides which of the five states
        // applies, this only maps that decision to text and colour.
        val hudStatus = SurroundRing.hudStatus(state.threats, state.speedKph.toFloat(), state.visionFeed)
        Text(
            text = "$mph MPH",
            color = NightPurple,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
        Text(
            text = statusLineText(hudStatus, state.threats.size),
            color = if (hudStatus == SurroundRing.HudStatus.DEMO || hudStatus == SurroundRing.HudStatus.CLEAR) NightGreen else NightRed,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
        )
        // Destination NAME (where we're going) top-centre; the arch marker below
        // carries the entrance clock / address, and rotates to show its bearing.
        Text(
            text = state.activeDriveTarget?.label ?: "--",
            color = NightGreen,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
        )
        // Cycle back to the other concepts (dim so it doesn't spill light).
        Text(
            text = "DRIVER ▸",
            color = NightGreen,
            fontSize = 16.sp,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .border(1.dp, NightGrid)
                    .clickable(onClick = onCycleConcept)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Text for each [SurroundRing.HudStatus] row of the design doc's status-line table; colour is chosen by the caller. */
private fun statusLineText(
    status: SurroundRing.HudStatus,
    contactCount: Int,
): String =
    when (status) {
        SurroundRing.HudStatus.NO_VISION -> "NO VISION"
        SurroundRing.HudStatus.BRAKE -> "! BRAKE !   $contactCount CONTACTS"
        SurroundRing.HudStatus.CHECK_REAR -> "! CHECK REAR !   $contactCount CONTACTS"
        SurroundRing.HudStatus.DEMO -> "$contactCount CONTACTS   DEMO"
        SurroundRing.HudStatus.CLEAR -> "$contactCount CONTACTS   CLEAR"
    }

private fun DrawScope.drawDriverHud(
    state: CockpitUiState,
    threats: List<DriverThreat>,
    ringBlips: List<SurroundRing.Blip>,
    relDeg: Float,
    tf: Typeface?,
) {
    val w = size.width
    val h = size.height
    val ringCenter = Offset(w * 0.5f, h * 0.58f)
    val ringRadius = h * SURROUND_RING_RADIUS_FRACTION

    // Z-order (design/surround-driver-hud.md 2b): grid, then ring furniture,
    // then non-collision blips, then the forward figures (which sit on top of
    // their own ring blip — the figure IS the detail view for that contact),
    // then collision blips, which are the only thing allowed to punch through
    // a figure, then the reticle/text on top of everything.
    drawPerspectiveGrid(w, h)
    drawHeadingArch(state, relDeg, w, h, tf)
    drawRingFurniture(state.visionFeed, ringCenter, ringRadius)
    val (collisionBlips, plainBlips) = ringBlips.partition { it.collision }
    plainBlips.forEach { drawRingBlip(it, ringCenter, ringRadius) }
    threats.forEach { drawThreat(it, w, h, tf) }
    collisionBlips.forEach { drawRingBlip(it, ringCenter, ringRadius) }
    reticle(ringCenter, NightGreen)

    // Top-left context: open playa vs. in the city grid.
    hudText(if (state.entranceRadial != null) "OPEN PLAYA" else "IN CITY", Offset(w * 0.03f, h * 0.09f), NightGreen, h * 0.033f, tf)
    // The centre banner is the primary, preattentive alert channel, so it
    // keys off the same brakeAdvised decision as the status line and the
    // status-line table (3b/3c) — not "any collision at all". A rear-only
    // collision gets CHECK REAR on the status line and no centre flash: a
    // centre-screen alert for something astern is the exact mistrust-training
    // problem brakeAdvised's own doc warns about.
    if (SurroundRing.brakeAdvised(state.threats, state.speedKph.toFloat())) {
        hudText("! COLLISION COURSE !", Offset(w * 0.5f, h * 0.15f), NightRed, h * 0.042f, tf, Paint.Align.CENTER)
    }
}

/** Static ring chrome: rim (solid over covered arcs, dotted otherwise), ticks, forward wedge, ego hull. */
private fun DrawScope.drawRingFurniture(
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
            uncoveredArcs(SurroundRing.COVERED_ARCS).forEach { arc ->
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

/** The gaps between [covered] arcs, wrapping once around the circle — what [drawRingRim] dots. */
private fun uncoveredArcs(covered: List<ClosedFloatingPointRange<Float>>): List<ClosedFloatingPointRange<Float>> {
    if (covered.isEmpty()) return listOf(-HALF_TURN_DEG..HALF_TURN_DEG)
    val sorted = covered.sortedBy { it.start }
    val gaps = mutableListOf<ClosedFloatingPointRange<Float>>()
    for (i in sorted.indices) {
        val cur = sorted[i]
        val nextStart = if (i + 1 < sorted.size) sorted[i + 1].start else sorted.first().start + FULL_CIRCLE_DEG
        if (nextStart > cur.endInclusive) gaps += cur.endInclusive..nextStart
    }
    return gaps
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

private fun DrawScope.drawRingBlip(
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

private fun unitVector(screenAngleDeg: Float): Pair<Float, Float> {
    val a = Math.toRadians(screenAngleDeg.toDouble())
    return cos(a).toFloat() to sin(a).toFloat()
}

private fun DrawScope.drawPerspectiveGrid(
    w: Float,
    h: Float,
) {
    val vp = Offset(w * 0.5f, h * 0.44f)
    for (i in 1..5) {
        val t = i / 6f
        val y = vp.y + (h - vp.y) * (t * t)
        drawLine(NightGrid, Offset(0f, y), Offset(w, y), 1f)
    }
    for (gx in listOf(-4, -2, 2, 4)) {
        drawLine(NightGrid, vp, Offset(vp.x + gx * w * 0.13f, h), 1f)
    }
}

private fun DrawScope.drawHeadingArch(
    state: CockpitUiState,
    relDeg: Float,
    w: Float,
    h: Float,
    tf: Typeface?,
) {
    val c = Offset(w * 0.5f, h * 1.07f)
    val r = h * 0.85f
    drawArc(
        color = NightGreen,
        startAngle = 241f,
        sweepAngle = 58f,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(2 * r, 2 * r),
        style = Stroke(2f),
    )
    var deg = 241
    while (deg <= 299) {
        drawLine(archPt(c, r, deg.toFloat()), archPt(c, r - h * 0.02f, deg.toFloat()), NightGreen, 2f)
        deg += 4
    }
    val markerDeg = 270f + relDeg.coerceIn(-ARCH_HALF_SPAN_DEG, ARCH_HALF_SPAN_DEG)
    val mp = archPt(c, r, markerDeg)
    val tri =
        Path().apply {
            moveTo(mp.x - 12f, mp.y - h * 0.024f)
            lineTo(mp.x + 12f, mp.y - h * 0.024f)
            lineTo(mp.x, mp.y + 3f)
            close()
        }
    drawPath(tri, NightPurple)
    // Only the entrance clock/address rides the arch (in purple, a data value);
    // the destination name lives at top-centre. Presets with no city entrance
    // (MAN / TEMPLE, free-drive) just show the rotating marker, no box.
    state.entranceRadial?.let { entrance ->
        boxedLabel(mp.x, h * 0.20f, entrance, NightPurple, h * 0.030f, tf)
    }
}

private fun DrawScope.drawThreat(
    t: DriverThreat,
    w: Float,
    h: Float,
    tf: Typeface?,
) {
    val x = w * 0.5f + (t.relAzDeg / THERMAL_HALF_FOV_DEG) * (w * 0.40f)
    val figH = lerp(h * 0.10f, h * 0.30f, t.size)
    val feetY = lerp(h * 0.52f, h * 0.90f, t.size)
    val color = if (t.collision) NightRed else NightGreen
    val bracketColor = if (t.collision) DeepRed else NightGreen
    val stroke = if (t.collision) 3f else 2f
    if (t.size < NEAR_SHAPE_THRESHOLD) {
        figureBust(x, feetY, figH, color, stroke)
    } else {
        figureWalking(x, feetY, figH, color, stroke)
    }
    if (t.collision || t.size > 0.4f) {
        val halfW = figH * 0.42f
        bracket(x - halfW, feetY - figH * 1.02f, x + halfW, feetY + figH * 0.04f, bracketColor, if (t.collision) 3f else 2f)
        hudText(if (t.collision) "COLLISION" else "TRACK", Offset(x, feetY + figH * 0.16f), color, h * 0.028f, tf, Paint.Align.CENTER)
    }
}

/** Distant contact: compact head + shoulders, legible when only a few pixels. */
private fun DrawScope.figureBust(
    cx: Float,
    feetY: Float,
    figH: Float,
    color: Color,
    stroke: Float,
) {
    val hr = figH / 5.2f
    val top = feetY - figH
    drawCircle(color, hr, Offset(cx, top + hr), style = Stroke(stroke))
    val bodyTop = top + 2 * hr
    val bw = figH / 2.2f
    val shoulders =
        Path().apply {
            moveTo(cx - bw * 0.4f, bodyTop)
            lineTo(cx + bw * 0.4f, bodyTop)
            lineTo(cx + bw * 0.75f, feetY)
            lineTo(cx - bw * 0.75f, feetY)
            close()
        }
    drawPath(shoulders, color, style = Stroke(stroke))
}

/** Close contact: full striding figure that reads unmistakably as a person. */
private fun DrawScope.figureWalking(
    cx: Float,
    feetY: Float,
    figH: Float,
    color: Color,
    stroke: Float,
) {
    val hr = figH / 9f
    val top = feetY - figH
    drawCircle(color, hr, Offset(cx, top + hr), style = Stroke(stroke))
    val neck = top + 2 * hr
    val hip = feetY - figH * 0.40f
    drawLine(Offset(cx, neck), Offset(cx - figH * 0.05f, hip), color, stroke)
    drawLine(Offset(cx, neck + figH * 0.04f), Offset(cx - figH * 0.26f, neck + figH * 0.18f), color, stroke)
    drawLine(Offset(cx, neck + figH * 0.04f), Offset(cx + figH * 0.24f, neck + figH * 0.30f), color, stroke)
    drawLine(Offset(cx - figH * 0.05f, hip), Offset(cx - figH * 0.24f, feetY), color, stroke)
    drawLine(Offset(cx - figH * 0.05f, hip), Offset(cx + figH * 0.20f, feetY), color, stroke)
}

private fun DrawScope.bracket(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    color: Color,
    stroke: Float,
) {
    val len = minOf(x1 - x0, y1 - y0) * 0.30f
    for (corner in listOf(Triple(x0, y0, 1), Triple(x1, y0, -1), Triple(x0, y1, 1), Triple(x1, y1, -1))) {
        val (cx, cy, sx) = corner
        val sy = if (cy == y0) 1 else -1
        drawLine(Offset(cx, cy), Offset(cx + sx * len, cy), color, stroke)
        drawLine(Offset(cx, cy), Offset(cx, cy + sy * len), color, stroke)
    }
}

private fun DrawScope.reticle(
    c: Offset,
    color: Color,
) {
    val g = size.height * 0.015f
    val l = size.height * 0.03f
    drawLine(Offset(c.x - g - l, c.y), Offset(c.x - g, c.y), color, 2f)
    drawLine(Offset(c.x + g, c.y), Offset(c.x + g + l, c.y), color, 2f)
    drawLine(Offset(c.x, c.y - g - l), Offset(c.x, c.y - g), color, 2f)
    drawLine(Offset(c.x, c.y + g), Offset(c.x, c.y + g + l), color, 2f)
}

private fun DrawScope.drawLine(
    a: Offset,
    b: Offset,
    color: Color,
    stroke: Float,
) = drawLine(color, a, b, stroke)

private fun DrawScope.boxedLabel(
    cx: Float,
    baselineY: Float,
    text: String,
    color: Color,
    sizePx: Float,
    tf: Typeface?,
) {
    val paint = textPaint(color, sizePx, tf, Paint.Align.CENTER)
    val tw = paint.measureText(text)
    drawRect(
        color = color,
        topLeft = Offset(cx - tw / 2f - 10f, baselineY - sizePx - 6f),
        size = Size(tw + 20f, sizePx + 14f),
        style = Stroke(2f),
    )
    drawIntoCanvasText(text, cx, baselineY, paint)
}

private fun DrawScope.hudText(
    text: String,
    pos: Offset,
    color: Color,
    sizePx: Float,
    tf: Typeface?,
    align: Paint.Align = Paint.Align.LEFT,
) = drawIntoCanvasText(text, pos.x, pos.y, textPaint(color, sizePx, tf, align))

private fun DrawScope.drawIntoCanvasText(
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun textPaint(
    color: Color,
    sizePx: Float,
    tf: Typeface?,
    align: Paint.Align,
): Paint =
    Paint().apply {
        this.color = color.toArgb()
        textSize = sizePx
        typeface = tf
        isAntiAlias = true
        textAlign = align
    }

private fun archPt(
    c: Offset,
    r: Float,
    deg: Float,
): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
}

private fun lerp(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

/** Fold a degree delta into (−180, 180]. */
private fun normalizeSigned(deg: Double): Double {
    var d = deg % 360.0
    if (d > 180.0) d -= 360.0
    if (d <= -180.0) d += 360.0
    return d
}
