package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

interface PlayaMapRepository {
    val map: Flow<PlayaMap>

    suspend fun load()
}

/**
 * Reads the bundled Innovate GeoJSON for one BRC year out of the APK assets.
 * One-shot load on first call to [load]; subsequent calls are no-ops.
 *
 * Asset layout (relative to assetManager root):
 *  brc/<year>/{trash_fence,street_lines,street_outlines,city_blocks,plazas,
 *              cpns,toilets}.geojson
 */
class AssetsPlayaMapRepository(
    private val assets: AssetManager,
    private val year: String = "2025",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlayaMapRepository {
    private val state = MutableStateFlow<PlayaMap?>(null)

    override val map: Flow<PlayaMap> = state.asStateFlow().filterNotNull()

    override suspend fun load() {
        if (state.value != null) return
        state.value = withContext(ioDispatcher) { parseAll() }
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
        )

    private fun read(name: String): String = assets.open("brc/$year/$name.geojson").bufferedReader().use { it.readText() }
}
