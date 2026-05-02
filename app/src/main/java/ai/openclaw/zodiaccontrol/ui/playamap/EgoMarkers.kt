package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

private val EgoDefault = Color(0xFFFFD166)

// Bumped from 14 → 28 for debugging visibility. Triangle is now filled
// with a thick white outline + a halo ring, so the ego reads at any zoom
// level. Small enough to still feel like a vehicle (not a logo).
private const val EGO_SIZE = 28f
private const val EGO_OUTLINE_STROKE = 3f
private const val EGO_HALO_RADIUS_MULT = 1.7f
private const val EGO_HALO_STROKE = 2f

/** Anchor (Y as a fraction of viewport height) where the ego marker draws. */
const val EGO_ANCHOR_CENTER: Float = 0.5f

/**
 * Viewport-anchored triangular ego marker. Used by concept A, where the
 * ego always sits at the viewport's anchor point (centre in TOP, lower
 * third in TILT) regardless of pan.
 */
fun DrawScope.drawEgoMarker(
    viewport: PlayaViewport,
    anchorYFrac: Float = EGO_ANCHOR_CENTER,
    color: Color = EgoDefault,
) = drawEgoMarkerAt(
    cx = viewport.widthPx / 2f,
    cy = viewport.heightPx * anchorYFrac,
    color = color,
)

/**
 * Triangular ego marker drawn at explicit screen coordinates. [rotationDeg]
 * is the marker's heading relative to the *display* — 0° points up, 90°
 * points right, etc. In TRACK_UP this is always 0; in FREE after a
 * two-finger rotate, it equals (egoHeading − viewRotation) so the marker
 * still points in the ego's real direction of motion on the rotated
 * display. Filled body + white outline + halo ring keep it visible.
 */
fun DrawScope.drawEgoMarkerAt(
    cx: Float,
    cy: Float,
    color: Color = EgoDefault,
    rotationDeg: Float = 0f,
) {
    rotate(rotationDeg, pivot = Offset(cx, cy)) {
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
        drawPath(path = path, color = color)
        drawPath(path = path, color = Color.White, style = Stroke(width = EGO_OUTLINE_STROKE))
    }
    // Halo is rotation-invariant — drawn outside the rotate{} so it doesn't
    // appear to wobble as the heading sweeps past cardinal directions.
    drawCircle(
        color = color,
        radius = EGO_SIZE * EGO_HALO_RADIUS_MULT,
        center = Offset(cx, cy),
        style = Stroke(width = EGO_HALO_STROKE),
    )
}

/** Viewport-anchored hex ego marker — concept B's faceted-polyhedron silhouette. */
fun DrawScope.drawHexEgoMarker(
    viewport: PlayaViewport,
    anchorYFrac: Float = EGO_ANCHOR_CENTER,
    color: Color = EgoDefault,
) = drawHexEgoMarkerAt(
    cx = viewport.widthPx / 2f,
    cy = viewport.heightPx * anchorYFrac,
    color = color,
)

/**
 * Hex ego marker at explicit screen coordinates with optional display
 * rotation — same semantics as [drawEgoMarkerAt]: 0° points up, marker
 * spins with `rotationDeg` so the forward tick keeps pointing in the
 * ego's real direction of motion on the rotated display.
 */
fun DrawScope.drawHexEgoMarkerAt(
    cx: Float,
    cy: Float,
    color: Color = EgoDefault,
    rotationDeg: Float = 0f,
) {
    val r = EGO_SIZE * 0.95f
    rotate(rotationDeg, pivot = Offset(cx, cy)) {
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
        drawPath(path = path, color = color)
        drawPath(path = path, color = Color.White, style = Stroke(width = EGO_OUTLINE_STROKE))
        drawLine(
            color = Color.White,
            start = Offset(cx, cy),
            end = Offset(cx, cy - r * 1.5f),
            strokeWidth = EGO_OUTLINE_STROKE,
        )
    }
    drawCircle(
        color = color,
        radius = r * EGO_HALO_RADIUS_MULT,
        center = Offset(cx, cy),
        style = Stroke(width = EGO_HALO_STROKE),
    )
}
