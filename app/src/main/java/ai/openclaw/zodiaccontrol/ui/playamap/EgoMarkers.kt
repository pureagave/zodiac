package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

private val EgoDefault = Color(0xFFFFD166)
private const val EGO_SIZE = 14f

/** Anchor (Y as a fraction of viewport height) where the ego marker draws. */
const val EGO_ANCHOR_CENTER: Float = 0.5f

/**
 * Triangular ego marker pointing up (forward in track-up terms). Drawn on a
 * non-tilted overlay above the map so it stays upright in TILT mode. The
 * caller picks [anchorYFrac] — 0.5f = viewport center (TOP), ~0.78f =
 * lower-third (TILT, arcade-HUD anchor).
 */
fun DrawScope.drawEgoMarker(
    viewport: PlayaViewport,
    anchorYFrac: Float = EGO_ANCHOR_CENTER,
    color: Color = EgoDefault,
) {
    val cx = viewport.widthPx / 2f
    val cy = viewport.heightPx * anchorYFrac
    val tip = Offset(cx, cy - EGO_SIZE)
    val left = Offset(cx - EGO_SIZE * 0.6f, cy + EGO_SIZE * 0.7f)
    val right = Offset(cx + EGO_SIZE * 0.6f, cy + EGO_SIZE * 0.7f)
    val path =
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(right.x, right.y)
            lineTo(left.x, left.y)
            close()
        }
    drawPath(path = path, color = color, style = Stroke(width = 2f))
}

/**
 * Concept-B alternate ego: a small flat hex (faceted-polyhedron silhouette)
 * carrying over from the perspective-grid mockup. Draws at the same anchor
 * point as [drawEgoMarker], with a forward direction tick so heading is
 * unambiguous even when the chassis is stationary.
 */
fun DrawScope.drawHexEgoMarker(
    viewport: PlayaViewport,
    anchorYFrac: Float = EGO_ANCHOR_CENTER,
    color: Color = EgoDefault,
) {
    val cx = viewport.widthPx / 2f
    val cy = viewport.heightPx * anchorYFrac
    val r = EGO_SIZE * 0.85f
    val pts =
        (0..5).map { i ->
            val a = Math.toRadians(60.0 * i - 30.0)
            Offset(
                (cx + r * kotlin.math.cos(a)).toFloat(),
                (cy + r * kotlin.math.sin(a)).toFloat(),
            )
        }
    val path =
        Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
    drawPath(path = path, color = color, style = Stroke(width = 2f))
    drawLine(
        color = color,
        start = Offset(cx, cy),
        end = Offset(cx, cy - r * 1.4f),
        strokeWidth = 2f,
    )
}
