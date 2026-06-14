package ai.openclaw.zodiaccontrol.ui.playamap

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.geo.projectInline
import ai.openclaw.zodiaccontrol.core.geo.toScreenInline
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
    /**
     * Screen-space first/last vertex of every street + outline polyline.
     * The CRT-beam aesthetic renders these as bright endpoint dots —
     * each represents a place where the simulated electron beam stopped
     * and over-exposed the phosphor. Empty for non-CRT palettes (the
     * data is computed unconditionally; only the renderer gates on
     * [MapPalette.crtBeam]).
     */
    val streetEndpoints: List<Offset>,
    /** Every vertex of the plaza polygons — corners glow brighter under CRT beam. */
    val plazaCorners: List<Offset>,
    /** Every vertex of the trash-fence polygon. */
    val fenceCorners: List<Offset>,
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

    val fenceRing = trashFence.firstOrNull()
    val fencePath =
        fenceRing?.let { ring ->
            Path().apply { appendSubpath(ring.ringFlat, projection, viewport, close = true) }
        } ?: Path()

    val streetEndpoints = ArrayList<Offset>(streetLines.size * 2 + streetOutlines.size * 2)
    collectPolylineEndpoints(streetLines.map { it.pointsFlat }, projection, viewport, streetEndpoints)
    collectPolylineEndpoints(streetOutlines.map { it.ringFlat }, projection, viewport, streetEndpoints)

    return ProjectedMap(
        trashFencePath = fencePath,
        streetOutlinePath = buildSubpathBundle(streetOutlines, projection, viewport, close = false) { it.ringFlat },
        streetPath = buildSubpathBundle(streetLines, projection, viewport, close = false) { it.pointsFlat },
        plazaPath = buildSubpathBundle(plazas, projection, viewport, close = true) { it.ringFlat },
        toiletPositions = toilets.mapNotNull { it.centroid?.let(toScreen) },
        cpnPositions = cpns.map { toScreen(it.location) },
        artMajorPositions = majorArt.map { toScreen(it.location) },
        artMinorPositions = minorArt.map { toScreen(it.location) },
        plazaLabels = plazaLabelSeeds.map(toProjectedLabel),
        artLabels = artLabelSeeds.map(toProjectedLabel),
        streetLabels = streetLabelSeeds.map(toProjectedLabel),
        cpnLabels = cpnLabelSeeds.map(toProjectedLabel),
        streetEndpoints = streetEndpoints,
        plazaCorners = collectAllVertices(plazas.map { it.ringFlat }, projection, viewport),
        fenceCorners =
            fenceRing
                ?.let { collectAllVertices(listOf(it.ringFlat), projection, viewport) }
                ?: emptyList(),
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
 * Polylines arrive as flat `[lon0, lat0, lon1, lat1, ...]` [DoubleArray]s
 * (precomputed eagerly on [PlayaMap] load) so the per-vertex walk hits
 * contiguous primitives instead of N separate heap-resident [LatLon]
 * objects. Combined with [projectInline] / [toScreenInline] this means
 * the inner loop allocates nothing — no [LatLon] boxing, no
 * intermediate [PlayaPoint] / [androidx.compose.ui.graphics.Path]-cursor
 * objects, no per-point [Offset]. Just doubles in, floats into the
 * Path's geometry buffer.
 */
private inline fun <T> buildSubpathBundle(
    items: List<T>,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    close: Boolean,
    ringOf: (T) -> DoubleArray,
): Path =
    Path().apply {
        for (item in items) appendSubpath(ringOf(item), projection, viewport, close)
    }

/**
 * Append a single polyline as a subpath, projecting each `(lon, lat)`
 * pair directly into screen-space and writing scalars into the Path
 * cursor. Zero allocations in the inner loop.
 */
private fun Path.appendSubpath(
    flat: DoubleArray,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    close: Boolean,
) {
    if (flat.size < MIN_POLYLINE_DOUBLES) return
    projection.projectInline(flat[0], flat[1]) { e, n ->
        viewport.toScreenInline(e, n) { sx, sy ->
            moveTo(sx.toFloat(), sy.toFloat())
        }
    }
    var i = 2
    while (i < flat.size) {
        projection.projectInline(flat[i], flat[i + 1]) { e, n ->
            viewport.toScreenInline(e, n) { sx, sy ->
                lineTo(sx.toFloat(), sy.toFloat())
            }
        }
        i += 2
    }
    if (close) close()
}

/** Two `(lon, lat)` pairs = 4 Doubles, the smallest renderable polyline. */
private const val MIN_POLYLINE_DOUBLES = 4

/**
 * Append the projected first and last vertex of every polyline in [flats]
 * into [out]. Used to surface the points where a simulated electron beam
 * would have decelerated — the renderer paints those as the bright
 * "vector endpoint" dots in CRT-beam mode.
 */
private fun collectPolylineEndpoints(
    flats: List<DoubleArray>,
    projection: PlayaProjection,
    viewport: PlayaViewport,
    out: MutableList<Offset>,
) {
    for (flat in flats) {
        if (flat.size < MIN_POLYLINE_DOUBLES) continue
        projection.projectInline(flat[0], flat[1]) { e, n ->
            viewport.toScreenInline(e, n) { sx, sy ->
                out.add(Offset(sx.toFloat(), sy.toFloat()))
            }
        }
        val tail = flat.size
        projection.projectInline(flat[tail - 2], flat[tail - 1]) { e, n ->
            viewport.toScreenInline(e, n) { sx, sy ->
                out.add(Offset(sx.toFloat(), sy.toFloat()))
            }
        }
    }
}

/**
 * Project every vertex of every polyline in [flats]. For closed polygons
 * (plazas, fence) the result lists every corner — the CRT-beam aesthetic
 * renders all of them as endpoint dots.
 */
private fun collectAllVertices(
    flats: List<DoubleArray>,
    projection: PlayaProjection,
    viewport: PlayaViewport,
): List<Offset> {
    val out = ArrayList<Offset>()
    for (flat in flats) {
        var i = 0
        while (i + 1 < flat.size) {
            projection.projectInline(flat[i], flat[i + 1]) { e, n ->
                viewport.toScreenInline(e, n) { sx, sy ->
                    out.add(Offset(sx.toFloat(), sy.toFloat()))
                }
            }
            i += 2
        }
    }
    return out
}
