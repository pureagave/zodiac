package org.pureagave.zodiac.control.data.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import java.io.File

/**
 * Offline-first playa-discovery store. On start it serves any disk cache
 * immediately, then refreshes from the [source] in the background and re-caches;
 * if the fetch fails (no Starlink / API down) the cache stands. Exposes a single
 * [pois] `StateFlow` for the cockpit to render as RADAR contacts / MAP markers /
 * drive-to targets.
 */
class DiscoveryRepository(
    private val source: DiscoverySource,
    private val scope: CoroutineScope,
    cacheDir: File,
    private val year: Int,
) {
    private val _pois = MutableStateFlow<List<PlayaPoi>>(emptyList())
    val pois: StateFlow<List<PlayaPoi>> = _pois.asStateFlow()

    private val cacheFile = File(cacheDir, "discovery_$year.json")

    init {
        scope.launch {
            // Serve cache instantly, then refresh on launch and roughly nightly
            // while the process lives. A failed refresh (offline) is a no-op, so
            // the last good full dataset keeps serving through connectivity gaps.
            loadCache()?.let { if (it.isNotEmpty()) _pois.value = it }
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /** Fetch + re-cache. Swallows failures so the cached list is preserved. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any network/IO/parse failure intentionally keeps the cache
    suspend fun refresh() {
        try {
            val fresh = source.fetch(year)
            if (fresh.isNotEmpty()) {
                _pois.value = fresh
                saveCache(fresh)
            }
        } catch (e: Exception) {
            // Offline or API error: keep whatever we already have.
        }
    }

    private fun saveCache(pois: List<PlayaPoi>) {
        val arr = JSONArray()
        pois.forEach { p ->
            val o = JSONObject()
            o.put("uid", p.uid)
            o.put("name", p.name)
            o.put("kind", p.kind.name)
            o.put("subtitle", p.subtitle)
            p.point?.let {
                o.put("eastM", it.eastM)
                o.put("northM", it.northM)
            }
            arr.put(o)
        }
        runCatching { cacheFile.writeText(arr.toString()) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // corrupt/absent cache just yields null → refetch
    private fun loadCache(): List<PlayaPoi>? {
        if (!cacheFile.exists()) return null
        return try {
            val arr = JSONArray(cacheFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val point = if (o.has("eastM")) PlayaPoint(o.getDouble("eastM"), o.getDouble("northM")) else null
                PlayaPoi(
                    uid = o.optString("uid"),
                    name = o.optString("name"),
                    kind = runCatching { PoiKind.valueOf(o.optString("kind")) }.getOrDefault(PoiKind.ART),
                    point = point,
                    subtitle = o.optString("subtitle"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000 // ~daily
    }
}
