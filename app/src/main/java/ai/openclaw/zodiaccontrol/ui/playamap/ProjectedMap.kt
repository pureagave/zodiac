package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.model.StaticLabel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/** A label site already projected to screen coordinates. */
data class ProjectedLabel(
    val text: String,
    val position: Offset,
    val major: Boolean = false,
)

/**
 * BRC playa map projected once into screen-space geometry, ready for the
 * renderer to blit. Built by [PlayaMap.project] for a given
 * `(projection, viewport)` and reused frame-to-frame as long as the
 * camera state (centre / heading / zoom / size / anchor) is unchanged.
 *
 * Beyond skipping per-vertex projection on cache hits, every layer is
 * **consolidated into a single draw operation** so the per-frame cost on
 * Fire-class GPUs collapses from thousands of `drawLine` / `drawCircle`
 * calls to a handful of `drawPath` / `drawPoints` calls — same pixels,
 * orders of magnitude fewer Skia calls.
 *
 * - Closed polygons → one [Path] each, with subpaths per ring for fence,
 *   plazas, street outlines.
 * - Streets → one [Path] with a subpath per logical street; the renderer
 *   strokes it with round caps + round joins, producing the same visual
 *   look as the original "drawLine per segment with round caps."
 * - Markers (toilets / CPNs / minor art) → batched as [Offset] lists so
 *   the renderer can do a single `drawPoints` per layer. Major art stays
 *   as a separate list because its hollow-stroke style can't be batched
 *   through `drawPoints`.
 *
 * Labels are pre-positioned but still zoom-gated at draw time so the
 * cache survives a zoom change without rebuilding.
 */
data class ProjectedMap(
    val trashFencePath: Path,
    val streetOutlinePath: Path,
    val streetPath: Path,
    val plazaPath: Path,
    val toiletPositions: List<Offset>,
    val cpnPositions: List<Offset>,
    val artMajorPositions: List<Offset>,
    val artMinorPositions: List<Offset>,
    val plazaLabels: List<ProjectedLabel>,
    val artLabels: List<ProjectedLabel>,
    val streetLabels: List<ProjectedLabel>,
    val cpnLabels: List<ProjectedLabel>,
)

/**
 * Project every drawable feature in [this] map through [projection] and
 * [viewport] into screen-space geometry. O(N) over total vertices; do
 * this once per camera-state change, not per frame.
 *
 * Layer partitioning, label-anchor selection, and name-emptiness checks
 * are precomputed eagerly when [PlayaMap] loads (see its `*Seeds` /
 * `majorArt` / `minorArt` properties); this projection step only does
 * the work that actually depends on the live camera.
 */
fun PlayaMap.project(
    projection: PlayaProjection,
    viewport: PlayaViewport,
): ProjectedMap {
    val toScreen: (LatLon) -> Offset = { ll ->
        val s = viewport.toScreen(projection.project(ll))
        Offset(s.x.toFloat(), s.y.toFloat())
    }
    val toProjectedLabel: (StaticLabel) -> ProjectedLabel = { seed ->
        ProjectedLabel(text = seed.text, position = toScreen(seed.location), major = seed.major)
    }

    val fencePath =
        trashFence.firstOrNull()?.let { ring ->
            Path().apply { appendSubpath(ring.ring, projection, viewport, close = true) }
        } ?: Path()

    return ProjectedMap(
        trashFencePath = fencePath,
        streetOutlinePath = buildSubpathBundle(streetOutlines, projection, viewport, close = false) { it.ring },
        streetPath = buildSubpathBundle(streetLines, projection, viewport, close = false) { it.points },
        plazaPath = buildSubpathBundle(plazas, projection, viewport, close = true) { it.ring },
        toiletPositions = toilets.mapNotNull { it.centroid?.let(toScreen) },
        cpnPositions = cpns.map { toScreen(it.location) },
        artMajorPositions = majorArt.map { toScreen(it.location) },
        artMinorPositions = minorArt.map { toScreen(it.location) },
        plazaLabels = plazaLabelSeeds.map(toProjectedLabel),
        artLabels = artLabelSeeds.map(toProjectedLabel),
        streetLabels = streetLabelSeeds.map(toProjectedLabel),
        cpnLabels = cpnLabelSeeds.map(toProjectedLabel),
    )
}

/**
 * Combine many polylines into a single [Path] of subpaths so the renderer
 * can stroke them all in one Skia call. Polylines with fewer than two
 * points are skipped silently. When [close] is true each subpath is
 * closed (used for plazas / trash fence); for unclosed strokes (streets,
 * outlines), round joins make the corners look identical to a stream of
 * round-cap drawLine segments.
 *
 * Vertex projection is fused into the path build — each LatLon flows
 * straight into [Path.moveTo] / [Path.lineTo] without an intermediate
 * `List<Offset>` per polyline, eliminating ~600 inner ArrayList
 * allocations (and the per-point Offset boxing those force) per cache
 * miss.
 */
private inline fun <T> buildSubpathBundle(
    items: List<T>,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    close: Boolean,
    ringOf: (T) -> List<LatLon>,
): Path =
    Path().apply {
        for (item in items) appendSubpath(ringOf(item), projection, viewport, close)
    }

/**
 * Append a single polyline as a subpath, projecting each LatLon directly
 * into screen-space at the cursor. No intermediate [Offset] list — saves
 * the per-point boxing that `List.map(toScreen)` would force.
 */
private fun Path.appendSubpath(
    ring: List<LatLon>,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    close: Boolean,
) {
    if (ring.size < 2) return
    val first = viewport.toScreen(projection.project(ring[0]))
    moveTo(first.x.toFloat(), first.y.toFloat())
    for (i in 1 until ring.size) {
        val s = viewport.toScreen(projection.project(ring[i]))
        lineTo(s.x.toFloat(), s.y.toFloat())
    }
    if (close) close()
}
