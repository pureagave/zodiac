package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.model.MapLoadResult
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

interface PlayaMapRepository {
    val loadResult: StateFlow<MapLoadResult>
    val map: Flow<PlayaMap>

    suspend fun load()
}

/**
 * Reads one BRC year's GeoJSON layers and returns their text contents.
 * Lives on the constructor of [AssetsPlayaMapRepository] so the JVM tests
 * can substitute a fake without Robolectric.
 */
interface PlayaAssetReader {
    fun read(
        year: String,
        name: String,
    ): String
}

private class AndroidPlayaAssetReader(
    private val assets: AssetManager,
) : PlayaAssetReader {
    override fun read(
        year: String,
        name: String,
    ): String = assets.open("brc/$year/$name.geojson").bufferedReader().use { it.readText() }
}

/**
 * Reads the bundled Innovate GeoJSON for one BRC year out of the APK assets.
 * One-shot load on first call to [load]; subsequent calls are no-ops once
 * the map is in [MapLoadResult.Loaded]. If asset I/O or JSON parsing fails,
 * the result transitions to [MapLoadResult.Failed] with a human-readable
 * message — the cockpit then renders without the map but stays alive.
 *
 * Asset layout (relative to assetManager root):
 *  brc/<year>/{trash_fence,street_lines,street_outlines,city_blocks,plazas,
 *              cpns,toilets}.geojson
 */
class AssetsPlayaMapRepository(
    private val reader: PlayaAssetReader,
    private val year: String = "2025",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlayaMapRepository {
    constructor(assets: AssetManager, year: String = "2025") : this(AndroidPlayaAssetReader(assets), year)

    private val _loadResult = MutableStateFlow<MapLoadResult>(MapLoadResult.Loading)
    override val loadResult: StateFlow<MapLoadResult> = _loadResult.asStateFlow()

    override val map: Flow<PlayaMap> =
        _loadResult.mapNotNull { (it as? MapLoadResult.Loaded)?.map }

    override suspend fun load() {
        if (_loadResult.value is MapLoadResult.Loaded) return
        _loadResult.value = runLoadAttempt()
    }

    private suspend fun runLoadAttempt(): MapLoadResult =
        try {
            MapLoadResult.Loaded(withContext(ioDispatcher) { parseAll() })
        } catch (e: IOException) {
            MapLoadResult.Failed(e.message ?: "I/O error reading map")
        } catch (e: JSONException) {
            MapLoadResult.Failed(e.message ?: "Malformed map data")
        }

    private fun parseAll(): PlayaMap =
        PlayaMap(
            year = year,
            trashFence = GeoJsonParser.parsePolygons(read("trash_fence")),
            streetLines = GeoJsonParser.parseStreetLines(read("street_lines")),
            streetOutlines = GeoJsonParser.parsePolygons(read("street_outlines")),
            cityBlocks = GeoJsonParser.parsePolygons(read("city_blocks")),
            plazas = GeoJsonParser.parsePolygons(read("plazas"), nameKey = "Name"),
            toilets = GeoJsonParser.parsePolygons(read("toilets"), nameKey = "ref"),
            cpns = GeoJsonParser.parsePoints(read("cpns"), nameKey = "NAME", kindKey = "TYPE"),
            art = GeoJsonParser.parsePoints(read("art"), nameKey = "name", kindKey = "program"),
        )

    private fun read(name: String): String = reader.read(year, name)
}
