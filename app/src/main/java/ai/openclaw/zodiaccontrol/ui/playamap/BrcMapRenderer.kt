package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
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
private val GridGreen = Color(0xFF1F6E37)

/**
 * Per-concept skin for the playa map. The renderer reads exclusively from the
 * supplied palette so each concept (CRT vector / perspective / motion tracker
 * / instrument bay) can draw the same BRC features in its own colour set.
 *
 * [pointStyle] selects how individual POIs (toilets, CPNs, art) render —
 * round dots for the canonical look, BLOCK for concept D's chunky framed-tile
 * aesthetic. Streets, fence, and plazas are always lines/polygons regardless.
 */
enum class MapPointStyle { DOT, BLOCK }

data class MapPalette(
    val fence: Color,
    val street: Color,
    val streetOutline: Color,
    val plaza: Color,
    val toilet: Color,
    val cpn: Color,
    val artMajor: Color,
    val artMinor: Color,
    val grid: Color,
    val pointStyle: MapPointStyle = MapPointStyle.DOT,
    /**
     * When true, [drawPlayaMap] draws labels for art, streets, plazas, and
     * CPNs (zoom-gated). Off by default to preserve concept A's clean look.
     */
    val labelsEnabled: Boolean = false,
    /** Color used for label glyphs when [labelsEnabled]. */
    val labelPrimary: Color = Color.White,
) {
    companion object {
        /** Concept A canonical palette — green/amber/blue/pink, round POIs. */
        val Default =
            MapPalette(
                fence = Fence,
                street = Street,
                streetOutline = Outline,
                plaza = Plaza,
                toilet = Toilet,
                cpn = Cpn,
                artMajor = ArtMajor,
                artMinor = ArtMinor,
                grid = GridGreen,
                pointStyle = MapPointStyle.DOT,
            )
    }
}

private const val FENCE_STROKE = 2f
private const val STREET_STROKE = 1f
private const val OUTLINE_STROKE = 1f
private const val PLAZA_STROKE = 1f
private const val TOILET_RADIUS = 3f
private const val CPN_RADIUS = 2f
private const val ART_MAJOR_RADIUS = 5f
private const val ART_MINOR_RADIUS = 1.5f
private const val ART_MAJOR_STROKE = 1.5f
private const val GRID_SPACING_M = 200.0
private const val GRID_HALF_RANGE_M = 5_000.0
private const val GRID_STROKE_PX = 1.2f

private val MajorArtPrograms = setOf("Honorarium", "ManPavGrant")

/**
 * Bundle of the rendering transforms used at every draw call so individual
 * helpers stay under the param-count limits.
 */
private data class RenderCtx(
    val projection: PlayaProjection,
    val viewport: PlayaViewport,
    val palette: MapPalette,
)

/**
 * Render a [PlayaMap] for the given [viewport]. Draws back-to-front:
 * street outlines, street centerlines, trash fence, plazas, toilet markers,
 * CPN markers, and finally the ego triangle.
 *
 * Pass a [palette] to re-skin the map per concept; defaulting to
 * [MapPalette.Default] preserves the original concept-A colour set.
 */
fun DrawScope.drawPlayaMap(
    map: PlayaMap,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    palette: MapPalette = MapPalette.Default,
) {
    val ctx = RenderCtx(projection, viewport, palette)
    map.streetOutlines.forEach { drawPolygon(it, ctx, palette.streetOutline, OUTLINE_STROKE) }
    map.streetLines.forEach { drawStreet(it, ctx) }
    map.trashFence.forEach { drawPolygon(it, ctx, palette.fence, FENCE_STROKE, closed = true) }
    map.plazas.forEach { drawPolygon(it, ctx, palette.plaza, PLAZA_STROKE, closed = true) }
    map.toilets.forEach { drawCentroidPoi(it, ctx, palette.toilet, TOILET_RADIUS) }
    map.cpns.forEach { drawPoi(it, ctx, palette.cpn, CPN_RADIUS) }
    map.art.forEach { drawArtMarker(it, ctx) }
    if (palette.labelsEnabled) drawMapLabels(map = map, projection = projection, viewport = viewport, palette = palette)
}

/**
 * Retro-future ground-plane grid drawn in **playa meters** through the same
 * [viewport] used for the map. Lines spaced every [GRID_SPACING_M] meters,
 * extending [GRID_HALF_RANGE_M] in each direction from the camera. Because the
 * grid shares the projection with the map, their vanishing points coincide —
 * the city's geometry sits naturally on top of the grid in TILT mode rather
 * than floating above an unrelated 2D pattern.
 */
fun DrawScope.drawRetroGrid(
    viewport: PlayaViewport,
    color: Color = GridGreen,
) {
    val centerEast = viewport.center.eastM
    val centerNorth = viewport.center.northM
    var east = -GRID_HALF_RANGE_M
    while (east <= GRID_HALF_RANGE_M) {
        val a = viewport.toScreen(PlayaPoint(centerEast + east, centerNorth - GRID_HALF_RANGE_M))
        val b = viewport.toScreen(PlayaPoint(centerEast + east, centerNorth + GRID_HALF_RANGE_M))
        drawLine(color, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), GRID_STROKE_PX)
        east += GRID_SPACING_M
    }
    var north = -GRID_HALF_RANGE_M
    while (north <= GRID_HALF_RANGE_M) {
        val a = viewport.toScreen(PlayaPoint(centerEast - GRID_HALF_RANGE_M, centerNorth + north))
        val b = viewport.toScreen(PlayaPoint(centerEast + GRID_HALF_RANGE_M, centerNorth + north))
        drawLine(color, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), GRID_STROKE_PX)
        north += GRID_SPACING_M
    }
}

private fun DrawScope.drawArtMarker(
    point: PointFeature,
    ctx: RenderCtx,
) {
    val major = point.kind in MajorArtPrograms
    val s = ctx.viewport.toScreen(ctx.projection.project(point.location))
    val center = Offset(s.x.toFloat(), s.y.toFloat())
    val color = if (major) ctx.palette.artMajor else ctx.palette.artMinor
    val radius = if (major) ART_MAJOR_RADIUS else ART_MINOR_RADIUS
    when (ctx.palette.pointStyle) {
        MapPointStyle.DOT ->
            if (major) {
                drawCircle(color = color, radius = radius, center = center, style = Stroke(width = ART_MAJOR_STROKE))
            } else {
                drawCircle(color = color, radius = radius, center = center)
            }
        MapPointStyle.BLOCK -> drawBlockMarker(center = center, color = color, radius = radius, hollow = major)
    }
}

/**
 * Concept-D POI: chunky orange-on-black framed rect that sits naturally beside
 * the bay's other instrument tiles. [hollow] = stroked frame for emphasis
 * (major art); filled square otherwise (toilets, CPNs, minor art).
 */
private fun DrawScope.drawBlockMarker(
    center: Offset,
    color: Color,
    radius: Float,
    hollow: Boolean,
) {
    val side = radius * 2f
    val topLeft = Offset(center.x - radius, center.y - radius)
    if (hollow) {
        drawRect(color = color, topLeft = topLeft, size = androidx.compose.ui.geometry.Size(side, side), style = Stroke(width = 1.5f))
    } else {
        drawRect(color = color, topLeft = topLeft, size = androidx.compose.ui.geometry.Size(side, side))
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
            color = ctx.palette.street,
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

private fun DrawScope.drawCentroidPoi(
    polygon: PolygonRing,
    ctx: RenderCtx,
    color: Color,
    radius: Float,
) {
    val centroid = polygon.centroid ?: return
    val s = ctx.viewport.toScreen(ctx.projection.project(centroid))
    drawPoiAt(Offset(s.x.toFloat(), s.y.toFloat()), ctx.palette.pointStyle, color, radius)
}

private fun DrawScope.drawPoi(
    point: PointFeature,
    ctx: RenderCtx,
    color: Color,
    radius: Float,
) {
    val s = ctx.viewport.toScreen(ctx.projection.project(point.location))
    drawPoiAt(Offset(s.x.toFloat(), s.y.toFloat()), ctx.palette.pointStyle, color, radius)
}

private fun DrawScope.drawPoiAt(
    center: Offset,
    style: MapPointStyle,
    color: Color,
    radius: Float,
) {
    when (style) {
        MapPointStyle.DOT -> drawCircle(color = color, radius = radius, center = center)
        MapPointStyle.BLOCK -> drawBlockMarker(center = center, color = color, radius = radius, hollow = false)
    }
}

private fun List<LatLon>.toScreen(ctx: RenderCtx): List<Offset> =
    map { ll ->
        val s: ScreenXY = ctx.viewport.toScreen(ctx.projection.project(ll))
        Offset(s.x.toFloat(), s.y.toFloat())
    }
