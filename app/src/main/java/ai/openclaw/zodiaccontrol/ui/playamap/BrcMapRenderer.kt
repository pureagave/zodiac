package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    /**
     * Enables the Atari-vector "CRT beam" aesthetic: a soft halo behind
     * every stroke (same path re-drawn wider with low alpha — fakes
     * phosphor bloom) plus bright endpoint dots wherever the simulated
     * electron beam would have decelerated (street polyline endpoints,
     * polygon corners). Off by default; concept A opts in.
     */
    val crtBeam: Boolean = false,
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
 * Geometry only — labels are a separate pass via [drawProjectedLabels],
 * which the cockpit's panels call after `drawProjectedMap` with their
 * own pre-laid-out [LabelLayouts] cache.
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
    drawProjectedMap(projected, palette)
}

/**
 * Render a [ProjectedMap] in the supplied [palette]. Pure raster pass —
 * no per-vertex projection, and every same-style layer collapsed into a
 * single Skia call (one `drawPath` per stroke style, one `drawPoints`
 * per marker style). On Fire-class GPUs this drops per-frame cost from
 * thousands of calls to under a dozen.
 *
 * Pre-laid-out labels are drawn in the same pass when [palette.labelsEnabled]
 * is true and a non-empty [labelLayouts] is supplied. Pass
 * [pixelsPerMeter] from the same viewport that produced [projected] so
 * the per-layer zoom gates fire on the live zoom level. Concept C's
 * lit-wedge re-blit calls this with [LabelLayouts.Empty] so labels
 * don't render twice (and don't get clipped by the wedge).
 */
fun DrawScope.drawProjectedMap(
    projected: ProjectedMap,
    palette: MapPalette,
    labelLayouts: LabelLayouts = LabelLayouts.Empty,
    pixelsPerMeter: Double = 0.0,
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
    if (palette.labelsEnabled) {
        drawProjectedLabels(projected, labelLayouts, palette.labelPrimary, pixelsPerMeter)
    }
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
 * Project the meter-space grid through [viewport] into a single Path of
 * subpaths — one per east/west tick — ready for the renderer to stroke in
 * one Skia call. Build this once per camera-state change at composable
 * scope and pass the cached result to [drawRetroGrid]; the previous
 * per-frame implementation projected 51×2 endpoints and emitted ~102
 * `drawLine` calls every frame, even though the grid is fully determined
 * by the viewport (and the camera centre cancels in screen space).
 *
 * Lines are spaced every [GRID_SPACING_M] meters and extend
 * [GRID_HALF_RANGE_M] in each direction from the camera so they always
 * cover the visible canvas at any zoom level. Vanishing points coincide
 * with the map's because both share the same viewport projection.
 */
fun projectRetroGrid(viewport: PlayaViewport): Path {
    val path = Path()
    val centerEast = viewport.center.eastM
    val centerNorth = viewport.center.northM
    var east = -GRID_HALF_RANGE_M
    while (east <= GRID_HALF_RANGE_M) {
        val a = viewport.toScreen(PlayaPoint(centerEast + east, centerNorth - GRID_HALF_RANGE_M))
        val b = viewport.toScreen(PlayaPoint(centerEast + east, centerNorth + GRID_HALF_RANGE_M))
        path.moveTo(a.x.toFloat(), a.y.toFloat())
        path.lineTo(b.x.toFloat(), b.y.toFloat())
        east += GRID_SPACING_M
    }
    var north = -GRID_HALF_RANGE_M
    while (north <= GRID_HALF_RANGE_M) {
        val a = viewport.toScreen(PlayaPoint(centerEast - GRID_HALF_RANGE_M, centerNorth + north))
        val b = viewport.toScreen(PlayaPoint(centerEast + GRID_HALF_RANGE_M, centerNorth + north))
        path.moveTo(a.x.toFloat(), a.y.toFloat())
        path.lineTo(b.x.toFloat(), b.y.toFloat())
        north += GRID_SPACING_M
    }
    return path
}

/**
 * Stroke a pre-projected retro grid Path. Pure raster pass — one Skia
 * call regardless of grid density.
 */
fun DrawScope.drawRetroGrid(
    path: Path,
    color: Color = GridGreen,
) {
    drawPath(path = path, color = color, style = Stroke(width = GRID_STROKE_PX))
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
