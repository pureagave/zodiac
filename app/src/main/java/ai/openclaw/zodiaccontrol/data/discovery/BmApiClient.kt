package ai.openclaw.zodiaccontrol.data.discovery

import ai.openclaw.zodiaccontrol.BuildConfig
import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.ops.PlayaPoi
import ai.openclaw.zodiaccontrol.core.ops.PoiKind
import ai.openclaw.zodiaccontrol.core.ops.artPoint
import ai.openclaw.zodiaccontrol.core.ops.campPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches playa points of interest for a year. Throws on failure so the repo can keep its cache. */
interface DiscoverySource {
    suspend fun fetch(year: Int): List<PlayaPoi>
}

/**
 * Burning Man API client (`api.burningman.org`, `X-API-Key` header). Reads art +
 * theme-camp records and projects each into a [PlayaPoi]. Plain HttpURLConnection
 * + org.json (no extra deps); runs on IO. Art carries GPS; camps carry a
 * clock/street address (see `core/ops/campPoint`).
 */
class BmApiClient(
    private val apiKey: String = BuildConfig.BM_API_KEY,
    private val baseUrl: String = "https://api.burningman.org",
    private val projection: PlayaProjection = PlayaProjection(GoldenSpike.Y2025),
) : DiscoverySource {
    override suspend fun fetch(year: Int): List<PlayaPoi> =
        withContext(Dispatchers.IO) {
            val art = getArray("/api/art?year=$year").mapObjects(::parseArt)
            val camps = getArray("/api/camp?year=$year").mapObjects(::parseCamp)
            art + camps
        }

    private fun parseArt(o: JSONObject): PlayaPoi? {
        val name = o.optString("name").ifBlank { return null }
        val loc = o.optJSONObject("location")
        val lat = loc?.optDouble("gps_latitude", Double.NaN) ?: Double.NaN
        val lon = loc?.optDouble("gps_longitude", Double.NaN) ?: Double.NaN
        val point = if (!lat.isNaN() && !lon.isNaN()) artPoint(lat, lon, projection) else null
        return PlayaPoi(
            uid = o.optString("uid"),
            name = name,
            kind = PoiKind.ART,
            point = point,
            subtitle = o.optString("artist").ifBlank { loc?.optString("location_string").orEmpty() },
        )
    }

    private fun parseCamp(o: JSONObject): PlayaPoi? {
        val name = o.optString("name").ifBlank { return null }
        val loc = o.optJSONObject("location")
        val point = campPoint(loc?.optStringOrNull("frontage"), loc?.optStringOrNull("intersection"))
        return PlayaPoi(
            uid = o.optString("uid"),
            name = name,
            kind = PoiKind.CAMP,
            point = point,
            subtitle = o.optStringOrNull("location_string").orEmpty(),
        )
    }

    private fun getArray(path: String): JSONArray {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-API-Key", apiKey)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("BM API $path -> HTTP ${conn.responseCode}")
            }
            return JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

private inline fun JSONArray.mapObjects(transform: (JSONObject) -> PlayaPoi?): List<PlayaPoi> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
