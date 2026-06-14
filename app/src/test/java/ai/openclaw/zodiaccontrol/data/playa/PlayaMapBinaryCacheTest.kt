package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.model.PointFeature
import ai.openclaw.zodiaccontrol.core.model.PolygonRing
import ai.openclaw.zodiaccontrol.core.model.StreetKind
import ai.openclaw.zodiaccontrol.core.model.StreetLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class PlayaMapBinaryCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun read_returns_null_when_file_missing() {
        val cache = PlayaMapBinaryCache(tempFolder.root)
        assertNull(cache.read("2025"))
    }

    @Test
    fun round_trip_preserves_every_layer_and_field() {
        val cache = PlayaMapBinaryCache(tempFolder.root)
        val map = sampleMap()

        cache.write("2025", map)
        val restored = cache.read("2025")!!

        assertEquals(map.year, restored.year)
        assertPolygonRingsEqual(map.trashFence, restored.trashFence)
        assertStreetsEqual(map.streetLines, restored.streetLines)
        assertPolygonRingsEqual(map.streetOutlines, restored.streetOutlines)
        assertPolygonRingsEqual(map.cityBlocks, restored.cityBlocks)
        assertPolygonRingsEqual(map.plazas, restored.plazas)
        assertPolygonRingsEqual(map.toilets, restored.toilets)
        assertPointsEqual(map.cpns, restored.cpns)
        assertPointsEqual(map.art, restored.art)
    }

    @Test
    fun round_trip_preserves_derived_flat_arrays() {
        // The DoubleArray mirrors are eagerly recomputed by [PolygonRing] /
        // [StreetLine] init from the deserialised LatLon list, so they
        // should match bit-for-bit.
        val cache = PlayaMapBinaryCache(tempFolder.root)
        val map = sampleMap()

        cache.write("2025", map)
        val restored = cache.read("2025")!!

        assertArrayEquals(map.streetLines[0].pointsFlat, restored.streetLines[0].pointsFlat, 0.0)
        assertArrayEquals(map.plazas[0].ringFlat, restored.plazas[0].ringFlat, 0.0)
    }

    @Test
    fun read_returns_null_for_year_mismatch() {
        val cache = PlayaMapBinaryCache(tempFolder.root)
        cache.write("2025", sampleMap())

        // Different year → different filename → no file → null. Even if
        // the caller forced a stale path, the in-header year check would
        // also reject. Here we just assert the standard miss path.
        assertNull(cache.read("2024"))
    }

    @Test
    fun read_returns_null_when_header_corrupt() {
        // A truncated file (under the magic+version header) must not throw
        // — the cache is best-effort, callers expect null on any failure.
        val cache = PlayaMapBinaryCache(tempFolder.root)
        val file = File(tempFolder.root, "playa_map_2025_v1.bin")
        FileOutputStream(file).use { it.write(byteArrayOf(0x00)) }
        assertNull(cache.read("2025"))
    }

    @Test
    fun read_returns_null_when_first_count_negative() {
        // Header is valid (magic + version + matching year) but the first
        // layer's count is negative — readCount must reject it as corruption
        // and read() must surface a clean miss, not a NegativeArraySizeException.
        writeCorruptCache("2025", firstCount = -1)
        assertNull(PlayaMapBinaryCache(tempFolder.root).read("2025"))
    }

    @Test
    fun read_returns_null_when_first_count_huge() {
        // A huge count would otherwise allocate a giant ArrayList (OOM);
        // readCount rejects anything past MAX_CACHE_COUNT, so read() misses.
        writeCorruptCache("2025", firstCount = Int.MAX_VALUE)
        assertNull(PlayaMapBinaryCache(tempFolder.root).read("2025"))
    }

    // Hand-writes a cache whose header matches what read() expects (MAGIC,
    // SCHEMA_VERSION, year) but whose first layer (trashFence) count is the
    // supplied corrupt value. MAGIC/SCHEMA_VERSION mirror the private consts
    // in PlayaMapBinaryCache: 0x504C4159 ('PLAY') and 1.
    private fun writeCorruptCache(
        year: String,
        firstCount: Int,
    ) {
        val file = File(tempFolder.root, "playa_map_${year}_v$CACHE_SCHEMA_VERSION.bin")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(CACHE_MAGIC)
            out.writeInt(CACHE_SCHEMA_VERSION)
            out.writeUTF(year)
            out.writeInt(firstCount)
        }
    }

    private fun sampleMap(): PlayaMap =
        PlayaMap(
            year = "2025",
            trashFence =
                listOf(
                    PolygonRing(
                        name = "fence",
                        ring =
                            listOf(
                                LatLon(lon = -119.21, lat = 40.79),
                                LatLon(lon = -119.20, lat = 40.79),
                                LatLon(lon = -119.20, lat = 40.80),
                                LatLon(lon = -119.21, lat = 40.80),
                            ),
                    ),
                ),
            streetLines =
                listOf(
                    StreetLine(
                        name = "Esplanade",
                        kind = StreetKind.Arc,
                        widthFeet = 30,
                        points = listOf(LatLon(lon = -119.205, lat = 40.795), LatLon(lon = -119.204, lat = 40.795)),
                    ),
                    StreetLine(name = null, kind = null, widthFeet = null, points = listOf(LatLon(lon = 0.0, lat = 0.0))),
                ),
            streetOutlines = emptyList(),
            cityBlocks = emptyList(),
            plazas =
                listOf(
                    PolygonRing(
                        name = "Center Camp",
                        ring =
                            listOf(
                                LatLon(lon = -119.2, lat = 40.79),
                                LatLon(lon = -119.19, lat = 40.79),
                                LatLon(lon = -119.19, lat = 40.80),
                            ),
                    ),
                ),
            toilets = listOf(PolygonRing(name = null, ring = emptyList())),
            cpns = listOf(PointFeature(name = "CPN1", kind = "Civic", location = LatLon(lon = -119.2, lat = 40.79))),
            art =
                listOf(
                    PointFeature(name = "Art Piece", kind = "Honorarium", location = LatLon(lon = -119.21, lat = 40.795)),
                    PointFeature(name = null, kind = null, location = LatLon(lon = 0.0, lat = 0.0)),
                ),
        )

    private fun assertPolygonRingsEqual(
        expected: List<PolygonRing>,
        actual: List<PolygonRing>,
    ) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].name, actual[i].name)
            assertEquals(expected[i].ring, actual[i].ring)
        }
    }

    private fun assertStreetsEqual(
        expected: List<StreetLine>,
        actual: List<StreetLine>,
    ) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].name, actual[i].name)
            assertEquals(expected[i].kind, actual[i].kind)
            assertEquals(expected[i].widthFeet, actual[i].widthFeet)
            assertEquals(expected[i].points, actual[i].points)
        }
    }

    private fun assertPointsEqual(
        expected: List<PointFeature>,
        actual: List<PointFeature>,
    ) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i].name, actual[i].name)
            assertEquals(expected[i].kind, actual[i].kind)
            assertEquals(expected[i].location, actual[i].location)
        }
    }

    private companion object {
        // Mirror the private consts in PlayaMapBinaryCache so the hand-written
        // corrupt-cache header matches what read() expects.
        const val CACHE_MAGIC = 0x504C4159 // 'PLAY'
        const val CACHE_SCHEMA_VERSION = 1
    }
}
