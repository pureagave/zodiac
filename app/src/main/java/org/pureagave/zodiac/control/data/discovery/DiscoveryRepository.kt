package org.pureagave.zodiac.control.data.discovery

import kotlinx.coroutines.CancellationException
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
import java.io.IOException

/**
 * Offline-first playa-discovery store. On start it serves any disk cache
 * immediately, then refreshes from the [source] in the background and re-caches;
 * if the fetch fails (no Starlink / API down) the cache stands. Exposes a single
 * [pois] `StateFlow` for the cockpit to render as RADAR contacts / MAP markers /
 * drive-to targets.
 *
 * [storageDir] must be non-purgeable storage (`filesDir`, not `cacheDir`): this
 * cache is the *only* offline copy of art/camp data for up to 14 unattended days,
 * and there is no bundled fallback the way the base playa map has one.
 */
class DiscoveryRepository(
    private val source: DiscoverySource,
    private val scope: CoroutineScope,
    storageDir: File,
    private val year: Int,
    // Test seam for the atomic-write path: lets a test inject a write that dies
    // partway through (simulating a power cut mid-write) without needing to race
    // a real thread. Default is the real "write the tmp file" step.
    private val cacheWriter: CacheWriter = CacheWriter { target, text -> target.writeText(text) },
) {
    private val _pois = MutableStateFlow<List<PlayaPoi>>(emptyList())
    val pois: StateFlow<List<PlayaPoi>> = _pois.asStateFlow()

    private val cacheFile = File(storageDir, "discovery_$year.json")

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

    /**
     * Fetch + re-cache. Swallows network/IO/parse failures so the cached list is
     * preserved; coroutine cancellation is re-thrown so the scope can unwind.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "RethrowCaughtException")
    suspend fun refresh() {
        try {
            val fresh = source.fetch(year)
            if (fresh.isNotEmpty()) {
                _pois.value = fresh
                saveCache(fresh)
            }
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation — let the scope unwind
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
            p.hometown?.let { o.put("hometown", it) }
            p.description?.let { o.put("description", it) }
            p.address?.let { o.put("address", it) }
            p.category?.let { o.put("category", it) }
            p.program?.let { o.put("program", it) }
            if (p.guidedTours) o.put("guided_tours", true)
            if (p.selfGuidedTour) o.put("self_guided_tour_map", true)
            if (p.needsVolunteers) o.put("needs_volunteers", true)
            p.point?.let {
                o.put("eastM", it.eastM)
                o.put("northM", it.northM)
            }
            arr.put(o)
        }
        writeAtomically(arr.toString())
    }

    /**
     * Write-to-temp-then-rename, mirroring [org.pureagave.zodiac.control.data.playa.PlayaMapBinaryCache.write].
     * A crash (power cut) during [cacheWriter] leaves only a half-written `.tmp`
     * file; [cacheFile] — the one [loadCache] reads — is untouched until the
     * rename, which is atomic on the same filesystem. Failure at any step is
     * best-effort: the cache is allowed to stay stale, never to go missing or
     * corrupt.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun writeAtomically(text: String) {
        val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            cacheFile.parentFile?.mkdirs()
            cacheWriter.write(tmp, text)
            if (!tmp.renameTo(cacheFile)) throw IOException("rename failed: $tmp -> $cacheFile")
        } catch (e: Exception) {
            // Next refresh retries; previous on-disk cache (if any) is untouched.
            tmp.delete()
        }
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
                    hometown = o.optStringOrNull("hometown"),
                    description = o.optStringOrNull("description"),
                    address = o.optStringOrNull("address"),
                    category = o.optStringOrNull("category"),
                    program = o.optStringOrNull("program"),
                    guidedTours = o.optBoolean("guided_tours", false),
                    selfGuidedTour = o.optBoolean("self_guided_tour_map", false),
                    needsVolunteers = o.optBoolean("needs_volunteers", false),
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

/** Test seam for [DiscoveryRepository]'s atomic cache write — see [DiscoveryRepository.writeAtomically]. */
fun interface CacheWriter {
    fun write(
        target: File,
        text: String,
    )
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
