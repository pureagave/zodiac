package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

/**
 * Render a [PlayaMap] for the given [viewport]. Convenience wrapper that
 * projects the map every call — fine for one-shot renders, but the cockpit
 * draws the same map ~60× per second, so the Composable layer should
 * [PlayaMap.project] once per camera-state change and call [drawProjectedMap]
 * with the cached result instead. Concept C in particular renders the map
 * twice per frame (dim base + lit-clipped sweep), making the cache
 * difference between "smooth" and "stuttering" on Fire-class devices.
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
    val projected = map.project(projection, viewport)
    drawProjectedMap(projected, palette, viewport.pixelsPerMeter)
}

/**
 * Render a [ProjectedMap] in the supplied [palette]. Pure raster pass —
 * no per-vertex projection, and every same-style layer collapsed into a
 * single Skia call (one `drawPath` per stroke style, one `drawPoints`
 * per marker style). On Fire-class GPUs this drops per-frame cost from
 * thousands of calls to under a dozen. Pass [pixelsPerMeter] from the
 * same viewport that produced [projected] so labels gate on the live
 * zoom level.
 */
fun DrawScope.drawProjectedMap(
    projected: ProjectedMap,
    palette: MapPalette,
    pixelsPerMeter: Double,
) {
    drawPath(
        path = projected.streetOutlinePath,
        color = palette.streetOutline,
        style = Stroke(width = OUTLINE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = projected.streetPath,
        color = palette.street,
        style = Stroke(width = STREET_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = projected.trashFencePath,
        color = palette.fence,
        style = Stroke(width = FENCE_STROKE),
    )
    drawPath(
        path = projected.plazaPath,
        color = palette.plaza,
        style = Stroke(width = PLAZA_STROKE),
    )
    drawPointBatch(projected.toiletPositions, palette.pointStyle, palette.toilet, TOILET_RADIUS)
    drawPointBatch(projected.cpnPositions, palette.pointStyle, palette.cpn, CPN_RADIUS)
    drawPointBatch(projected.artMinorPositions, palette.pointStyle, palette.artMinor, ART_MINOR_RADIUS)
    drawMajorArt(projected.artMajorPositions, palette)
    if (palette.labelsEnabled) drawProjectedLabels(projected, palette, pixelsPerMeter)
}

/**
 * Filled-marker batch — one Skia call for the whole list. `drawPoints`
 * with [PointMode.Points] + round cap renders each [Offset] as a filled
 * circle of `strokeWidth` pixels diameter. Used for toilets, CPNs, and
 * minor self-funded art. Concept D's chunky tile aesthetic falls out of
 * the batch (squares can't go through `drawPoints`) and walks the list
 * with per-item `drawRect` instead — D doesn't double-render so the
 * per-call overhead there isn't on the hot path.
 */
private fun DrawScope.drawPointBatch(
    points: List<Offset>,
    style: MapPointStyle,
    color: Color,
    radius: Float,
) {
    if (points.isEmpty()) return
    when (style) {
        MapPointStyle.DOT ->
            drawPoints(
                points = points,
                pointMode = PointMode.Points,
                color = color,
                strokeWidth = radius * 2f,
                cap = StrokeCap.Round,
            )
        MapPointStyle.BLOCK ->
            points.forEach { drawBlockMarker(center = it, color = color, radius = radius, hollow = false) }
    }
}

/**
 * Honorarium / ManPavGrant art renders as a hollow stroked circle and
 * therefore can't go through the filled-point batch. ~50 entries → ~50
 * `drawCircle` calls per pass; acceptable next to the win from batching
 * the 330-entry minor-art layer.
 */
private fun DrawScope.drawMajorArt(
    points: List<Offset>,
    palette: MapPalette,
) {
    when (palette.pointStyle) {
        MapPointStyle.DOT ->
            points.forEach { pos ->
                drawCircle(
                    color = palette.artMajor,
                    radius = ART_MAJOR_RADIUS,
                    center = pos,
                    style = Stroke(width = ART_MAJOR_STROKE),
                )
            }
        MapPointStyle.BLOCK ->
            points.forEach { pos ->
                drawBlockMarker(center = pos, color = palette.artMajor, radius = ART_MAJOR_RADIUS, hollow = true)
            }
    }
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
