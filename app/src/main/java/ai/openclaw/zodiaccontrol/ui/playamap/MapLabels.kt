package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.geo.ScreenXY
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint as NativePaint

// Zoom thresholds (px/m) above which a layer's labels start drawing. Tuned
// against the default city-fits-tablet zoom (~0.18) so an overview stays
// clean and labels appear progressively as the user pinches in.
private const val PLAZA_LABEL_ZOOM = 0.20
private const val ART_MAJOR_LABEL_ZOOM = 0.30
private const val STREET_LABEL_ZOOM = 0.45
private const val CPN_LABEL_ZOOM = 0.65
private const val ART_MINOR_LABEL_ZOOM = 1.10

private const val PLAZA_TEXT_SP = 11f
private const val STREET_TEXT_SP = 9f
private const val CPN_TEXT_SP = 9f
private const val ART_MAJOR_TEXT_SP = 12f
private const val ART_MINOR_TEXT_SP = 8f
private const val LABEL_OFFSET_PX = 6f

private val MajorArt = setOf("Honorarium", "ManPavGrant")

/**
 * One Paint per process, mutated per call. Labels render on the Compose
 * frame thread (single-threaded), so a shared instance is safe and skips a
 * per-marker allocation.
 */
private val labelPaint =
    NativePaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textAlign = NativePaint.Align.CENTER
    }

/**
 * Zoom-gated label pass over a [PlayaMap]. Plaza names always show once the
 * city is zoomed past overview; arc/radial street names appear at medium
 * zoom; CPNs at higher zoom; art names tier by program (Honorarium /
 * ManPavGrant come in earlier than self-funded). Toilets are intentionally
 * unlabeled — the source data has no per-bank name and the marker colour
 * carries the meaning.
 */
fun DrawScope.drawMapLabels(
    map: PlayaMap,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    palette: MapPalette,
) {
    val argb = palette.labelPrimary.toArgb()
    val ppm = viewport.pixelsPerMeter
    if (ppm >= PLAZA_LABEL_ZOOM) drawPlazaLabels(map, projection, viewport, argb)
    drawArtLabels(map, projection, viewport, argb, ppm)
    if (ppm >= STREET_LABEL_ZOOM) drawStreetLabels(map, projection, viewport, argb)
    if (ppm >= CPN_LABEL_ZOOM) drawCpnLabels(map, projection, viewport, argb)
}

private fun DrawScope.drawPlazaLabels(
    map: PlayaMap,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    argb: Int,
) {
    map.plazas
        .mapNotNull { p -> p.name?.let { name -> p.centroid?.let { it to name } } }
        .forEach { (centroid, name) ->
            label(viewport.toScreen(projection.project(centroid)), name, PLAZA_TEXT_SP, argb)
        }
}

private fun DrawScope.drawArtLabels(
    map: PlayaMap,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    argb: Int,
    ppm: Double,
) {
    map.art
        .filter { a ->
            val name = a.name ?: return@filter false
            name.isNotEmpty() && ppm >= artGateFor(a.kind)
        }
        .forEach { a ->
            val major = a.kind in MajorArt
            val size = if (major) ART_MAJOR_TEXT_SP else ART_MINOR_TEXT_SP
            label(viewport.toScreen(projection.project(a.location)), a.name!!, size, argb)
        }
}

private fun artGateFor(kind: String?): Double = if (kind in MajorArt) ART_MAJOR_LABEL_ZOOM else ART_MINOR_LABEL_ZOOM

/**
 * BRC's source data stores each block of a logical street ("4:30", "Kilgore",
 * etc.) as a separate `LineString` feature, so a naive midpoint-per-segment
 * pass stamps the same name at every intersection. Group by name first and
 * draw one label per logical street, anchored to the source point closest
 * to the group's centroid — for radials that lands roughly on the middle
 * block, for arcs it sits near the top of the curve.
 */
private fun DrawScope.drawStreetLabels(
    map: PlayaMap,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    argb: Int,
) {
    map.streetLines
        .filter { !it.name.isNullOrEmpty() && it.points.isNotEmpty() }
        .groupBy { it.name!! }
        .mapNotNull { (name, segments) ->
            val pool = segments.flatMap { it.points }
            representativePoint(pool)?.let { name to it }
        }
        .forEach { (name, pos) ->
            label(viewport.toScreen(projection.project(pos)), name, STREET_TEXT_SP, argb)
        }
}

private fun representativePoint(pool: List<LatLon>): LatLon? {
    if (pool.isEmpty()) return null
    var sumLat = 0.0
    var sumLon = 0.0
    for (p in pool) {
        sumLat += p.lat
        sumLon += p.lon
    }
    val targetLat = sumLat / pool.size
    val targetLon = sumLon / pool.size
    var best = pool[0]
    var bestSq = Double.MAX_VALUE
    for (p in pool) {
        val dLat = p.lat - targetLat
        val dLon = p.lon - targetLon
        val sq = dLat * dLat + dLon * dLon
        if (sq < bestSq) {
            bestSq = sq
            best = p
        }
    }
    return best
}

private fun DrawScope.drawCpnLabels(
    map: PlayaMap,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    argb: Int,
) {
    map.cpns
        .mapNotNull { c -> c.name?.let { it to c.location } }
        .forEach { (name, loc) ->
            label(viewport.toScreen(projection.project(loc)), name, CPN_TEXT_SP, argb)
        }
}

private fun DrawScope.label(
    pos: ScreenXY,
    text: String,
    sizeSp: Float,
    argb: Int,
) {
    drawIntoCanvas { canvas ->
        labelPaint.color = argb
        labelPaint.textSize = sizeSp * density
        canvas.nativeCanvas.drawText(
            text,
            pos.x.toFloat(),
            pos.y.toFloat() - LABEL_OFFSET_PX,
            labelPaint,
        )
    }
}
