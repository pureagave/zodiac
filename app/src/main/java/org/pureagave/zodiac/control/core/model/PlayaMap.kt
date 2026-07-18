package org.pureagave.zodiac.control.core.model

import org.pureagave.zodiac.control.core.geo.LatLon

enum class StreetKind { Radial, Arc }

data class StreetLine(
    val name: String?,
    val kind: StreetKind?,
    val widthFeet: Int?,
    val points: List<LatLon>,
) {
    /**
     * `[lon0, lat0, lon1, lat1, ...]` — same vertex sequence as [points],
     * eagerly flattened into a primitive array so the renderer's per-frame
     * projection walks contiguous Doubles instead of N separate heap-
     * resident [LatLon] objects. Cache-friendly and pairs with the
     * primitive-arg projection helpers in `PlayaProjection`.
     */
    val pointsFlat: DoubleArray = points.flatLonLat()
}

data class PolygonRing(
    val name: String?,
    val ring: List<LatLon>,
) {
    val centroid: LatLon? = ring.computeCentroid()

    /** Flattened `[lon0, lat0, ...]` mirror of [ring] — see [StreetLine.pointsFlat]. */
    val ringFlat: DoubleArray = ring.flatLonLat()
}

private fun List<LatLon>.flatLonLat(): DoubleArray {
    val out = DoubleArray(size * 2)
    for (i in indices) {
        out[i * 2] = this[i].lon
        out[i * 2 + 1] = this[i].lat
    }
    return out
}

private fun List<LatLon>.computeCentroid(): LatLon? {
    if (isEmpty()) return null
    var sx = 0.0
    var sy = 0.0
    for (p in this) {
        sx += p.lon
        sy += p.lat
    }
    return LatLon(lon = sx / size, lat = sy / size)
}

data class PointFeature(
    val name: String?,
    val kind: String?,
    val location: LatLon,
)

/**
 * A label site frozen at map-load time: the text plus the world-space
 * anchor (LatLon, not screen coords). The renderer projects [location]
 * through the live viewport per cache miss; everything else — name
 * lookups, major/minor classification, picking the representative point
 * for an arc — already happened once during [PlayaMap] init.
 */
data class StaticLabel(
    val text: String,
    val location: LatLon,
    val major: Boolean = false,
)

/**
 * BRC art programs that render as the larger "major art" marker style.
 * Tracked alongside [PlayaMap] so the partitioning happens once at map
 * load instead of on every projection cache miss.
 */
private val MajorArtPrograms = setOf("Honorarium", "ManPavGrant")

/**
 * Static map of Black Rock City for a given year, parsed once from the
 * bundled Innovate GeoJSON. All coordinates are WGS84 lon/lat; project to
 * playa-local meters with [org.pureagave.zodiac.control.core.geo.PlayaProjection].
 *
 * The derived `*Seeds` and `majorArt`/`minorArt` properties are computed
 * eagerly during init so the renderer's per-frame projection only does
 * the work that actually depends on the live camera state — `partition`,
 * `groupBy`, name-emptiness checks, and representative-point selection
 * are all done here, once.
 *
 * @property year the BRC year these features were sourced from (e.g. "2025")
 * @property toilets currently published as polygons (banks of porta-potties);
 *  the renderer draws each as a single dot at [PolygonRing.centroid]
 */
data class PlayaMap(
    val year: String,
    val trashFence: List<PolygonRing>,
    val streetLines: List<StreetLine>,
    val streetOutlines: List<PolygonRing>,
    val cityBlocks: List<PolygonRing>,
    val plazas: List<PolygonRing>,
    val toilets: List<PolygonRing>,
    val cpns: List<PointFeature>,
    val art: List<PointFeature>,
) {
    val majorArt: List<PointFeature> = art.filter { it.kind in MajorArtPrograms }
    val minorArt: List<PointFeature> = art.filter { it.kind !in MajorArtPrograms }

    val plazaLabelSeeds: List<StaticLabel> =
        plazas.mapNotNull { p ->
            val name = p.name ?: return@mapNotNull null
            val centroid = p.centroid ?: return@mapNotNull null
            StaticLabel(text = name, location = centroid)
        }

    val artLabelSeeds: List<StaticLabel> =
        art.mapNotNull { a ->
            val name = a.name?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            StaticLabel(
                text = name,
                location = a.location,
                major = a.kind in MajorArtPrograms,
            )
        }

    val streetLabelSeeds: List<StaticLabel> =
        streetLines
            .filter { !it.name.isNullOrEmpty() && it.points.isNotEmpty() }
            .groupBy { it.name!! }
            .mapNotNull { (name, segments) ->
                val pool = segments.flatMap { it.points }
                representativePoint(pool)?.let { StaticLabel(text = name, location = it) }
            }

    val cpnLabelSeeds: List<StaticLabel> =
        cpns.mapNotNull { c ->
            val name = c.name ?: return@mapNotNull null
            StaticLabel(text = name, location = c.location)
        }
}

/**
 * Pick the source point closest to the centroid of [pool] — used to anchor
 * a street label on the actual geometry rather than at the geometric centre
 * of an arc (which would land inside the bowl of the curve).
 */
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
