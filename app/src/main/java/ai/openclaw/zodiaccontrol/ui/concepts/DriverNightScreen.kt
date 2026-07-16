package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.R
import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.ops.campGuidance
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

private val PLACEHOLDER_THREATS =
    listOf(
        DriverThreat(relAzDeg = -16f, size = 0.20f),
        DriverThreat(relAzDeg = 14f, size = 0.22f),
        DriverThreat(relAzDeg = -3f, size = 0.82f, collision = true),
    )

/**
 * The "DRIVER" cockpit surface: a dim, hollow-vector night HUD (1983-arcade
 * lineage) for the person actually driving the vehicle in the dark. Thermal
 * contacts are drawn as hollow wireframe figures on a perspective grid; a
 * heading arch across the top shows the bearing to the active drive-to target
 * (on the open playa it reads the city entrance; in the city it reads the
 * destination). Nav data is live from [CockpitUiState]; the thermal contacts are
 * placeholder until the FLIR feed lands.
 */
@Composable
fun driverNightScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val typeface = remember { ResourcesCompat.getFont(context, R.font.orbitron) }
    val projection = remember { PlayaProjection(GoldenSpike.Y2025) }
    val threats = PLACEHOLDER_THREATS

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
            drawDriverHud(state, threats, relDeg.toFloat(), typeface)
        }
        // Speed (purple = live data) bottom-right, and the collision / all-clear
        // status bottom-left — kept as real Compose text for crispness.
        val collisionAny = threats.any { it.collision }
        Text(
            text = "$mph MPH",
            color = NightPurple,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
        Text(
            text = if (collisionAny) "! BRAKE !" else "${threats.size} CONTACTS   CLEAR",
            color = if (collisionAny) NightRed else NightGreen,
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

private fun DrawScope.drawDriverHud(
    state: CockpitUiState,
    threats: List<DriverThreat>,
    relDeg: Float,
    tf: Typeface?,
) {
    val w = size.width
    val h = size.height
    drawPerspectiveGrid(w, h)
    drawHeadingArch(state, relDeg, w, h, tf)
    threats.forEach { drawThreat(it, w, h, tf) }
    reticle(Offset(w * 0.5f, h * 0.58f), NightGreen)

    // Top-left context: open playa vs. in the city grid.
    hudText(if (state.entranceRadial != null) "OPEN PLAYA" else "IN CITY", Offset(w * 0.03f, h * 0.09f), NightGreen, h * 0.033f, tf)
    if (threats.any { it.collision }) {
        hudText("! COLLISION COURSE !", Offset(w * 0.5f, h * 0.15f), NightRed, h * 0.042f, tf, Paint.Align.CENTER)
    }
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
