package org.pureagave.zodiac.control.data.playa

import org.json.JSONArray
import org.json.JSONObject
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.model.PointFeature
import org.pureagave.zodiac.control.core.model.PolygonRing
import org.pureagave.zodiac.control.core.model.StreetKind
import org.pureagave.zodiac.control.core.model.StreetLine

/**
 * Minimal GeoJSON reader for the BRC Innovate dataset shape.
 * Handles Point, LineString, and Polygon (single outer ring); other types are
 * skipped silently. Property lookups are case-sensitive.
 *
 * **The street schema changes between years and BM promises nothing.** 2025
 * tagged each line `type: radial|arc` with a string `width`; 2026 renamed those
 * to `source: radial|annular|center_camp` and a numeric `width_ft`. Both are
 * read here, because an unrecognised tag is not a cosmetic loss: [StreetLine]s
 * without a kind are dropped from `PlayaCityModel`, which silently takes street
 * cues and address routing with them. `BundledGisTest` guards this.
 */
object GeoJsonParser {
    fun parseStreetLines(raw: String): List<StreetLine> =
        featuresOf(raw).mapNotNull { feature ->
            val coords = lineStringCoords(feature) ?: return@mapNotNull null
            val props = feature.optJSONObject("properties")
            StreetLine(
                name = props?.optString("name").nullIfEmpty(),
                kind = props?.streetKind(),
                widthFeet = props?.widthFeet(),
                points = coords,
            )
        }

    /**
     * [nameKeys] are tried in order, first non-empty wins — the same property
     * gets recased or renamed between years (2025 `Name` → 2026 `name`), and a
     * missed key means an unlabelled layer rather than a loud failure.
     */
    fun parsePolygons(
        raw: String,
        vararg nameKeys: String,
    ): List<PolygonRing> =
        featuresOf(raw).mapNotNull { feature ->
            val ring = polygonOuterRing(feature) ?: return@mapNotNull null
            val props = feature.optJSONObject("properties")
            val name = nameKeys.firstNotNullOfOrNull { props?.optString(it).nullIfEmpty() }
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

    private fun readLatLon(arr: JSONArray?): LatLon? {
        val coords = arr?.takeIf { it.length() >= 2 } ?: return null
        // optDouble (not getDouble) so a non-numeric/null coordinate yields
        // NaN instead of throwing JSONException, which would abort the whole
        // layer parse for one bad feature. NaN is dropped to null here, so
        // the existing per-feature null-handling skips just that feature.
        val lon = coords.optDouble(0, Double.NaN)
        val lat = coords.optDouble(1, Double.NaN)
        if (!lon.isFinite() || !lat.isFinite()) return null
        return LatLon(lon = lon, lat = lat)
    }
}

private fun String?.nullIfEmpty(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * `type` is the 2025 key, `source` the 2026 one. Center-camp streets carry
 * neither `radial` nor `annular` and stay untagged — they sit inside the
 * Esplanade, where routing and arc-crossing cues don't apply anyway.
 */
private fun JSONObject.streetKind(): StreetKind? =
    when (optString("type").nullIfEmpty() ?: optString("source").nullIfEmpty()) {
        "radial" -> StreetKind.Radial
        "arc", "annular" -> StreetKind.Arc
        else -> null
    }

/** 2025 quotes the width as a string; 2026 renamed it and made it numeric. */
private fun JSONObject.widthFeet(): Int? =
    optString("width").nullIfEmpty()?.toIntOrNull()
        ?: optInt("width_ft", 0).takeIf { it > 0 }
