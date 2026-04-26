package ai.openclaw.zodiaccontrol.core.model

import ai.openclaw.zodiaccontrol.core.geo.LatLon

enum class StreetKind { Radial, Arc }

data class StreetLine(
    val name: String?,
    val kind: StreetKind?,
    val widthFeet: Int?,
    val points: List<LatLon>,
)

data class PolygonRing(
    val name: String?,
    val ring: List<LatLon>,
)

data class PointFeature(
    val name: String?,
    val kind: String?,
    val location: LatLon,
)

/**
 * Static map of Black Rock City for a given year, parsed once from the
 * bundled Innovate GeoJSON. All coordinates are WGS84 lon/lat; project to
 * playa-local meters with [ai.openclaw.zodiaccontrol.core.geo.PlayaProjection].
 *
 * @property year the BRC year these features were sourced from (e.g. "2025")
 * @property toilets currently published as polygons (banks of porta-potties);
 *  centroid for icon rendering is left to the renderer
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
)
