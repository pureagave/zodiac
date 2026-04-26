package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.geo.ScreenXY
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.model.PointFeature
import ai.openclaw.zodiaccontrol.core.model.PolygonRing
import ai.openclaw.zodiaccontrol.core.model.StreetLine
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

private val Fence = Color(0xFF00FF66)
private val Street = Color(0xFF1F8F46)
private val Outline = Color(0xFF0F5C2D)
private val Plaza = Color(0xFFFFD166)
private val Toilet = Color(0xFF00BFFF)
private val Cpn = Color(0xFF00FF66)
private val ArtMajor = Color(0xFFFF66CC)
private val ArtMinor = Color(0xFF80366A)
private val Ego = Color(0xFFFFD166)
private val GridGreen = Color(0xFF0A3D1D)

private const val FENCE_STROKE = 2f
private const val STREET_STROKE = 1f
private const val OUTLINE_STROKE = 1f
private const val PLAZA_STROKE = 1f
private const val TOILET_RADIUS = 3f
private const val CPN_RADIUS = 2f
private const val ART_MAJOR_RADIUS = 5f
private const val ART_MINOR_RADIUS = 1.5f
private const val ART_MAJOR_STROKE = 1.5f
private const val EGO_SIZE = 14f
private const val GRID_HORIZON_FRAC = 0.62f
private const val GRID_RADIAL_HALF = 12
private const val GRID_RECEDE_DIVS = 9

private val MajorArtPrograms = setOf("Honorarium", "ManPavGrant")

/**
 * Bundle of the rendering transforms used at every draw call so individual
 * helpers stay under the param-count limits.
 */
private data class RenderCtx(
    val projection: PlayaProjection,
    val viewport: PlayaViewport,
)

/**
 * Render a [PlayaMap] for the given [viewport]. Draws back-to-front:
 * street outlines, street centerlines, trash fence, plazas, toilet markers,
 * CPN markers, and finally the ego triangle.
 */
fun DrawScope.drawPlayaMap(
    map: PlayaMap,
    projection: PlayaProjection,
    viewport: PlayaViewport,
) {
    val ctx = RenderCtx(projection, viewport)
    map.streetOutlines.forEach { drawPolygon(it, ctx, Outline, OUTLINE_STROKE) }
    map.streetLines.forEach { drawStreet(it, ctx) }
    map.trashFence.forEach { drawPolygon(it, ctx, Fence, FENCE_STROKE, closed = true) }
    map.plazas.forEach { drawPolygon(it, ctx, Plaza, PLAZA_STROKE, closed = true) }
    map.toilets.forEach { drawCentroidMarker(it, ctx, Toilet, TOILET_RADIUS) }
    map.cpns.forEach { drawPointMarker(it, ctx, Cpn, CPN_RADIUS) }
    map.art.forEach { drawArtMarker(it, ctx) }
}

/**
 * Retro-future converging perspective grid: 25 radial lines from the horizon
 * to the bottom edge plus 9 horizontal recede lines. Color matches the
 * original Phase-1 grid (#0A3D1D). Drawn under the playa map in TILT mode so
 * it shares the camera's `graphicsLayer` rotation and recedes toward the
 * vanishing point with the rest of the scene.
 */
fun DrawScope.drawRetroGrid() {
    val horizon = size.height * GRID_HORIZON_FRAC
    val midX = size.width / 2f
    val radialSpacing = size.width / (GRID_RADIAL_HALF * 2f + 2f)
    for (i in -GRID_RADIAL_HALF..GRID_RADIAL_HALF) {
        val x = midX + i * radialSpacing
        drawLine(GridGreen, Offset(midX, horizon), Offset(x, size.height), strokeWidth = 1f)
    }
    val recedeStep = (size.height - horizon) / GRID_RECEDE_DIVS.toFloat()
    for (j in 0..GRID_RECEDE_DIVS) {
        val y = horizon + j * recedeStep
        drawLine(GridGreen, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawArtMarker(
    point: PointFeature,
    ctx: RenderCtx,
) {
    val major = point.kind in MajorArtPrograms
    val s = ctx.viewport.toScreen(ctx.projection.project(point.location))
    val center = Offset(s.x.toFloat(), s.y.toFloat())
    if (major) {
        drawCircle(
            color = ArtMajor,
            radius = ART_MAJOR_RADIUS,
            center = center,
            style = Stroke(width = ART_MAJOR_STROKE),
        )
    } else {
        drawCircle(color = ArtMinor, radius = ART_MINOR_RADIUS, center = center)
    }
}

private fun DrawScope.drawStreet(
    street: StreetLine,
    ctx: RenderCtx,
) {
    val pts = street.points.toScreen(ctx)
    if (pts.size < 2) return
    for (i in 0 until pts.size - 1) {
        drawLine(
            color = Street,
            start = pts[i],
            end = pts[i + 1],
            strokeWidth = STREET_STROKE,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawPolygon(
    polygon: PolygonRing,
    ctx: RenderCtx,
    color: Color,
    stroke: Float,
    closed: Boolean = false,
) {
    val pts = polygon.ring.toScreen(ctx)
    if (pts.size < 2) return
    val path =
        Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            if (closed) close()
        }
    drawPath(path = path, color = color, style = Stroke(width = stroke))
}

private fun DrawScope.drawCentroidMarker(
    polygon: PolygonRing,
    ctx: RenderCtx,
    color: Color,
    radius: Float,
) {
    val centroid = polygon.ring.centroid() ?: return
    val s = ctx.viewport.toScreen(ctx.projection.project(centroid))
    drawCircle(color = color, radius = radius, center = Offset(s.x.toFloat(), s.y.toFloat()))
}

private fun DrawScope.drawPointMarker(
    point: PointFeature,
    ctx: RenderCtx,
    color: Color,
    radius: Float,
) {
    val s = ctx.viewport.toScreen(ctx.projection.project(point.location))
    drawCircle(color = color, radius = radius, center = Offset(s.x.toFloat(), s.y.toFloat()))
}

/**
 * Triangular ego marker pointing up (forward in track-up terms). Drawn on a
 * non-tilted overlay above the map so it stays upright in TILT mode. The
 * caller picks [anchorYFrac] — 0.5f = viewport center (TOP), ~0.78f =
 * lower-third (TILT, arcade-HUD anchor).
 */
fun DrawScope.drawEgoMarker(
    viewport: PlayaViewport,
    anchorYFrac: Float = EGO_ANCHOR_CENTER,
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
    drawPath(path = path, color = Ego, style = Stroke(width = 2f))
}

const val EGO_ANCHOR_CENTER: Float = 0.5f

private fun List<LatLon>.toScreen(ctx: RenderCtx): List<Offset> =
    map { ll ->
        val s: ScreenXY = ctx.viewport.toScreen(ctx.projection.project(ll))
        Offset(s.x.toFloat(), s.y.toFloat())
    }

private fun List<LatLon>.centroid(): LatLon? {
    if (isEmpty()) return null
    var sx = 0.0
    var sy = 0.0
    for (p in this) {
        sx += p.lon
        sy += p.lat
    }
    return LatLon(lon = sx / size, lat = sy / size)
}
