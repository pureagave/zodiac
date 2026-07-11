package ai.openclaw.zodiaccontrol.ui.ops

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.ops.DriveTarget
import ai.openclaw.zodiaccontrol.core.ops.campGuidance
import ai.openclaw.zodiaccontrol.core.ops.relativeBearingDeg
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.ui.concepts.ConceptTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Within this many degrees of the target bearing, we call it "on course". */
private const val ON_COURSE_DEG = 4.0
private const val METERS_PER_KM = 1_000.0
private const val KM_CUTOVER_M = 950.0

/**
 * Big glance-and-steer heading guide. A thick chevron rides a horizontal track:
 * its horizontal position is the signed heading error to [target] mapped
 * `0.5 + Δ/360` (dead-ahead → centre; 90° right → 75%; ±180° → hard against an
 * edge), and it points the way to turn — **►** right, **◄** left, or **▲** up
 * (and recolours to status-blue) when you're on course. Above it: the target
 * label + distance and the exact degrees-off. Computes its own guidance from
 * the live fix, like [opsReadout].
 */
@Composable
fun headingGuidanceBar(
    theme: ConceptTheme,
    egoFix: GpsFix?,
    headingDeg: Int,
    target: DriveTarget?,
    aim: LatLon? = null,
    modifier: Modifier = Modifier,
) {
    val projection = remember { PlayaProjection(GoldenSpike.Y2025) }
    // Label + distance refer to the final destination; the chevron steers toward
    // [aim] — the next route corner (e.g. the 2:30 entrance) — falling back to the
    // destination itself when there's no street route.
    val aimLoc = aim ?: target?.location
    val guidance =
        remember(egoFix?.location, target) {
            if (egoFix != null && target != null) campGuidance(egoFix.location, target.location, projection) else null
        }
    val rel =
        remember(egoFix?.location, aimLoc, headingDeg) {
            if (egoFix != null && aimLoc != null) {
                relativeBearingDeg(campGuidance(egoFix.location, aimLoc, projection).bearingDeg, headingDeg.toDouble())
            } else {
                null
            }
        }
    val onCourse = rel != null && abs(rel) <= ON_COURSE_DEG
    val chevronColor = if (onCourse) theme.secondary else theme.accent

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "▸ ${target?.label ?: "--"}   ${guidance?.distanceM?.let(::formatDist) ?: "--"}",
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Text(
                text = degreesLabel(rel, onCourse),
                color = chevronColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Text(
                "L",
                color = theme.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                "R",
                color = theme.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawTrack(theme)
                if (rel != null) drawChevron(rel, chevronColor)
            }
        }
    }
}

private fun DrawScope.drawTrack(theme: ConceptTheme) {
    val cy = size.height * 0.5f
    val pad = size.width * 0.04f
    // Baseline across the card.
    drawLine(theme.dim, Offset(pad, cy), Offset(size.width - pad, cy), strokeWidth = 2f)
    // Quarter + centre reference ticks; the centre is the "dead-ahead" mark.
    listOf(0.25f, 0.5f, 0.75f).forEach { f ->
        val x = size.width * f
        val h = if (f == 0.5f) size.height * 0.34f else size.height * 0.18f
        val c = if (f == 0.5f) theme.primary else theme.dim
        drawLine(c, Offset(x, cy - h), Offset(x, cy + h), strokeWidth = if (f == 0.5f) 3f else 1.5f)
    }
}

/**
 * Draw the steer chevron. [rel] is the signed error in degrees (positive =
 * turn right); the tip points in the turn direction (or up when on course).
 */
private fun DrawScope.drawChevron(
    rel: Double,
    color: Color,
) {
    val cy = size.height * 0.5f
    val fraction = (0.5 + rel / DEGREES_PER_TRACK).coerceIn(EDGE_MARGIN, 1.0 - EDGE_MARGIN)
    val cx = (size.width * fraction).toFloat()
    val depth = size.height * 0.22f
    val arm = size.height * 0.30f
    val stroke = (size.height * 0.14f).coerceAtLeast(6f)

    val path =
        Path().apply {
            when {
                rel > ON_COURSE_DEG -> {
                    // ► turn right
                    moveTo(cx - depth, cy - arm)
                    lineTo(cx + depth, cy)
                    lineTo(cx - depth, cy + arm)
                }
                rel < -ON_COURSE_DEG -> {
                    // ◄ turn left
                    moveTo(cx + depth, cy - arm)
                    lineTo(cx - depth, cy)
                    lineTo(cx + depth, cy + arm)
                }
                else -> {
                    // ▲ on course
                    moveTo(cx - depth, cy + arm * 0.6f)
                    lineTo(cx, cy - arm * 0.7f)
                    lineTo(cx + depth, cy + arm * 0.6f)
                }
            }
        }
    // Phosphor halo behind the crisp stroke.
    drawPath(path, color.copy(alpha = 0.25f), style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun degreesLabel(
    rel: Double?,
    onCourse: Boolean,
): String =
    when {
        rel == null -> "NO FIX"
        onCourse -> "ON COURSE"
        else -> "${abs(rel).roundToInt()}°${if (rel >= 0) "R" else "L"}"
    }

private fun formatDist(meters: Double): String =
    if (meters < KM_CUTOVER_M) "${meters.roundToInt()}m" else "%.1fkm".format(meters / METERS_PER_KM)

private const val DEGREES_PER_TRACK = 360.0
private const val EDGE_MARGIN = 0.05
