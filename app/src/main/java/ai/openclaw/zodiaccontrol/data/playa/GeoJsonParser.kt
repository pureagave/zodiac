package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.model.PointFeature
import ai.openclaw.zodiaccontrol.core.model.PolygonRing
import ai.openclaw.zodiaccontrol.core.model.StreetKind
import ai.openclaw.zodiaccontrol.core.model.StreetLine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal GeoJSON reader for the BRC Innovate dataset shape.
 * Handles Point, LineString, and Polygon (single outer ring); other types are
 * skipped silently. Property lookups are case-sensitive.
 */
object GeoJsonParser {
    fun parseStreetLines(raw: String): List<StreetLine> =
        featuresOf(raw).mapNotNull { feature ->
            val coords = lineStringCoords(feature) ?: return@mapNotNull null
            val props = feature.optJSONObject("properties")
            StreetLine(
                name = props?.optString("name").nullIfEmpty(),
                kind = props?.optString("type").toStreetKind(),
                widthFeet = props?.optString("width")?.toIntOrNull(),
                points = coords,
            )
        }

    fun parsePolygons(
        raw: String,
        nameKey: String? = null,
    ): List<PolygonRing> =
        featuresOf(raw).mapNotNull { feature ->
            val ring = polygonOuterRing(feature) ?: return@mapNotNull null
            val name = nameKey?.let { feature.optJSONObject("properties")?.optString(it).nullIfEmpty() }
            PolygonRing(name = name, ring = ring)
        }

    fun parsePoints(
        raw: String,
        nameKey: String,
        kindKey: String? = null,
    ): List<PointFeature> =
        featuresOf(raw).mapNotNull { feature ->
            val location = pointCoord(feature) ?: return@mapNotNull null
            val props = feature.optJSONObject("properties")
            PointFeature(
                name = props?.optString(nameKey).nullIfEmpty(),
                kind = kindKey?.let { props?.optString(it).nullIfEmpty() },
                location = location,
            )
        }

    private fun featuresOf(raw: String): List<JSONObject> {
        val features = JSONObject(raw).optJSONArray("features") ?: return emptyList()
        return List(features.length()) { features.getJSONObject(it) }
    }

    private fun lineStringCoords(feature: JSONObject): List<LatLon>? =
        feature.optJSONObject("geometry")
            ?.takeIf { it.optString("type") == "LineString" }
            ?.optJSONArray("coordinates")
            ?.let(::readPath)

    private fun polygonOuterRing(feature: JSONObject): List<LatLon>? =
        feature.optJSONObject("geometry")
            ?.takeIf { it.optString("type") == "Polygon" }
            ?.optJSONArray("coordinates")
            ?.takeIf { it.length() > 0 }
            ?.optJSONArray(0)
            ?.let(::readPath)

    private fun pointCoord(feature: JSONObject): LatLon? =
        feature.optJSONObject("geometry")
            ?.takeIf { it.optString("type") == "Point" }
            ?.optJSONArray("coordinates")
            ?.let(::readLatLon)

    private fun readPath(arr: JSONArray): List<LatLon>? {
        val out = ArrayList<LatLon>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(readLatLon(arr.optJSONArray(i)) ?: return null)
        }
        return out
    }

    private fun readLatLon(arr: JSONArray?): LatLon? =
        arr?.takeIf { it.length() >= 2 }
            ?.let { LatLon(lon = it.getDouble(0), lat = it.getDouble(1)) }
}

private fun String?.nullIfEmpty(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.toStreetKind(): StreetKind? =
    when (this) {
        "radial" -> StreetKind.Radial
        "arc" -> StreetKind.Arc
        else -> null
    }
