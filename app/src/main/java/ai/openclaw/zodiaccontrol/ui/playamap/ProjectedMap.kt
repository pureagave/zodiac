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
        trashFence.firstOrNull()?.ring?.let { ring ->
            buildSubpathBundle(listOf(ring.map(toScreen)), close = true)
        } ?: Path()

    val streetOutlinePath =
        buildSubpathBundle(
            polylines = streetOutlines.map { it.ring.map(toScreen) },
            close = false,
        )

    val streetPath =
        buildSubpathBundle(
            polylines = streetLines.map { it.points.map(toScreen) },
            close = false,
        )

    val plazaPath =
        buildSubpathBundle(
            polylines = plazas.map { it.ring.map(toScreen) },
            close = true,
        )

    return ProjectedMap(
        trashFencePath = fencePath,
        streetOutlinePath = streetOutlinePath,
        streetPath = streetPath,
        plazaPath = plazaPath,
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
 */
private fun buildSubpathBundle(
    polylines: List<List<Offset>>,
    close: Boolean,
): Path =
    Path().apply {
        for (poly in polylines) {
            if (poly.size < 2) continue
            moveTo(poly[0].x, poly[0].y)
            for (i in 1 until poly.size) lineTo(poly[i].x, poly[i].y)
            if (close) close()
        }
    }
