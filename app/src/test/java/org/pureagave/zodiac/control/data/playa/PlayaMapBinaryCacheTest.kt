package org.pureagave.zodiac.control.data.playa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.model.PointFeature
import org.pureagave.zodiac.control.core.model.PolygonRing
import org.pureagave.zodiac.control.core.model.StreetKind
import org.pureagave.zodiac.control.core.model.StreetLine
import java.io.File
import java.nio.ByteBuffer

/**
 * Corruption tests here never hand-assemble a header from constants mirrored
 * out of [PlayaMapBinaryCache] — that mirror is how the previous version of
 * this suite went dead: it wrote `playa_map_2025_v1.bin` while production had
 * moved to `_v2`, so every "corrupt header" assertion was really asserting the
 * missing-file path. Instead each test asks production to [PlayaMapBinaryCache.write]
 * a real cache, then mutates the bytes it actually produced. Filename, header
 * layout and schema version therefore always come from the code under test, and
 * every corruption test carries a control showing the *unmutated* bytes still
 * read back — so a null can only mean the mutation was rejected.
 */
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

    // --- header: the on-disk contract --------------------------------------

    @Test
    fun header_is_the_documented_PLAY_magic_and_the_filename_schema_version() {
        // The class doc promises a hex dump starts with ASCII 'PLAY' and that
        // the schema version is "encoded in the filename and checked again in
        // the header". Assert both against real bytes — and that the two
        // versions agree, so a bump can never leave the filename behind.
        val file = writtenCacheFile("2025")
        val bytes = file.readBytes()

        assertEquals("PLAY", String(bytes, 0, 4, Charsets.US_ASCII))
        val headerVersion = ByteBuffer.wrap(bytes, 4, 4).int
        assertTrue("schema version in header must be positive: $headerVersion", headerVersion > 0)
        assertEquals("filename must encode the header's schema version", "playa_map_2025_v$headerVersion.bin", file.name)
        assertEquals("year is a UTF field right after the header ints", "2025", headerYear(bytes))
    }

    @Test
    fun read_returns_null_when_the_magic_is_wrong() {
        val file = writtenCacheFile("2025")
        val bytes = file.readBytes()
        assertNotNull("control: the pristine bytes must read back", read("2025"))

        file.writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })

        assertNull("one flipped magic byte must be rejected", read("2025"))
    }

    @Test
    fun read_returns_null_when_the_schema_version_does_not_match() {
        // Simulates an older/newer cache surviving a schema bump: same
        // filename, same magic, version off by one. It must be discarded,
        // not decoded with the wrong layout.
        val file = writtenCacheFile("2025")
        val bytes = file.readBytes()
        assertNotNull("control: the pristine bytes must read back", read("2025"))

        file.writeBytes(bytes.copyOf().also { it[VERSION_LAST_BYTE] = (it[VERSION_LAST_BYTE] + 1).toByte() })

        assertNull("a bumped schema version must invalidate the cache", read("2025"))
    }

    @Test
    fun read_returns_null_when_the_header_year_is_not_the_requested_year() {
        // The old test only checked "different year -> different filename ->
        // no file", which never reaches the in-header year check. Here the
        // file IS at the requested path; only its header year is stale.
        val real = writtenCacheFile("2025")
        val bytes = real.readBytes()
        val stalePath = File(tempFolder.root, real.name.replace("2025", "2024"))
        assertTrue("cache filename must encode the year", stalePath.name != real.name)
        assertTrue(real.delete())

        stalePath.writeBytes(bytes)
        assertNull("header says 2025, caller asked for 2024", read("2024"))

        // Control: identical path and length, only the header's year field
        // rewritten — now it reads. So the null above is the year check
        // firing, not a missing file.
        stalePath.writeBytes(bytes.copyOf().also { patchHeaderYear(it, "2024") })
        assertNotNull("same file with a matching header year must read back", read("2024"))
    }

    // --- truncation / corruption -------------------------------------------

    @Test
    fun read_returns_null_for_every_truncation_of_a_real_cache() {
        // Torn write, short read, half-flushed file: every strict prefix of a
        // valid cache must degrade to a clean miss and must never throw
        // (EOFException, UTFDataFormatException, NegativeArraySize...).
        val file = writtenCacheFile("2025")
        val bytes = file.readBytes()
        assertTrue("sample cache should be non-trivial: ${bytes.size} bytes", bytes.size > MIN_SAMPLE_BYTES)

        for (length in 0 until bytes.size) {
            file.writeBytes(bytes.copyOf(length))
            assertNull("truncated to $length of ${bytes.size} bytes", read("2025"))
        }

        file.writeBytes(bytes)
        assertNotNull("control: the whole file still reads", read("2025"))
    }

    @Test
    fun read_returns_null_when_the_first_layer_count_is_negative() {
        // A whole, otherwise-valid file whose first layer count went negative:
        // readCount must reject it and read() must surface a clean miss, not a
        // NegativeArraySizeException out of ArrayList(count).
        val file = writtenCacheFile("2025")
        val bytes = file.readBytes()
        assertNotNull("control: unpatched count reads back", read("2025"))

        file.writeBytes(bytes.copyOf().also { patchFirstCount(it, -1) })

        assertNull(read("2025"))
    }

    @Test
    fun read_returns_null_when_the_first_layer_count_is_huge() {
        // Int.MAX_VALUE elements is a ~17 GB backing array: without readCount's
        // range check this is an OutOfMemoryError, which is an Error and slips
        // straight past read()'s IOException/RuntimeException catches.
        val file = writtenCacheFile("2025")
        val bytes = file.readBytes()
        assertNotNull("control: unpatched count reads back", read("2025"))

        file.writeBytes(bytes.copyOf().also { patchFirstCount(it, Int.MAX_VALUE) })

        assertNull(read("2025"))
    }

    @Test
    fun read_returns_null_when_the_first_layer_count_is_one_too_many() {
        // In-range but wrong: the parser walks one record past the end of the
        // file. Must miss cleanly rather than throw or return a short map.
        val file = writtenCacheFile("2025")
        val bytes = file.readBytes()
        val realCount = firstCount(bytes)
        assertEquals("sample map has one trash-fence ring", sampleMap().trashFence.size, realCount)

        file.writeBytes(bytes.copyOf().also { patchFirstCount(it, realCount + 1) })

        assertNull(read("2025"))
    }

    // --- helpers ------------------------------------------------------------

    private fun read(year: String): PlayaMap? = PlayaMapBinaryCache(tempFolder.root).read(year)

    /**
     * Has production write a real cache and hands back the file it chose, so
     * the tests never guess the filename or the schema version.
     */
    private fun writtenCacheFile(year: String): File {
        PlayaMapBinaryCache(tempFolder.root).write(year, sampleMap())
        val files = tempFolder.root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") }
        assertEquals("exactly one cache file expected, got ${files.map { it.name }}", 1, files.size)
        return files.single()
    }

    /** magic int + schema int + writeUTF(year) = 2-byte length + UTF year. */
    private fun bodyOffset(bytes: ByteArray): Int =
        INT_BYTES + INT_BYTES + UTF_LEN_BYTES + ByteBuffer.wrap(bytes, INT_BYTES + INT_BYTES, UTF_LEN_BYTES).short

    /** The first layer (trashFence) count, the int immediately after the header. */
    private fun firstCount(bytes: ByteArray): Int = ByteBuffer.wrap(bytes, bodyOffset(bytes), INT_BYTES).int

    private fun patchFirstCount(
        bytes: ByteArray,
        value: Int,
    ) {
        ByteBuffer.wrap(bytes, bodyOffset(bytes), INT_BYTES).putInt(value)
    }

    private fun headerYear(bytes: ByteArray): String {
        val length = ByteBuffer.wrap(bytes, INT_BYTES + INT_BYTES, UTF_LEN_BYTES).short.toInt()
        return String(bytes, INT_BYTES + INT_BYTES + UTF_LEN_BYTES, length, Charsets.UTF_8)
    }

    private fun patchHeaderYear(
        bytes: ByteArray,
        year: String,
    ) {
        val offset = INT_BYTES + INT_BYTES + UTF_LEN_BYTES
        val length = ByteBuffer.wrap(bytes, INT_BYTES + INT_BYTES, UTF_LEN_BYTES).short.toInt()
        require(length == year.length) { "year field is $length bytes, cannot patch to '$year'" }
        year.toByteArray(Charsets.UTF_8).copyInto(bytes, offset)
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
        const val INT_BYTES = 4

        /** DataOutputStream.writeUTF prefixes the bytes with an unsigned short. */
        const val UTF_LEN_BYTES = 2

        /** Last byte of the big-endian schema-version int (offset 4..7). */
        const val VERSION_LAST_BYTE = 7

        /** The sample map serialises to hundreds of bytes; guards a hollow truncation sweep. */
        const val MIN_SAMPLE_BYTES = 100
    }
}
