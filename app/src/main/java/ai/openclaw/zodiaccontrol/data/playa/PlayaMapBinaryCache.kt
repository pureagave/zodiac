package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.model.PointFeature
import ai.openclaw.zodiaccontrol.core.model.PolygonRing
import ai.openclaw.zodiaccontrol.core.model.StreetKind
import ai.openclaw.zodiaccontrol.core.model.StreetLine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Disk-backed cache for the parsed [PlayaMap]. First cold start does the
 * full GeoJSON parse and writes a flat binary into [cacheDir]; every
 * subsequent cold start reads the binary in a few hundred ms instead of
 * re-parsing ~1 MB of JSON.
 *
 * Cache file is `playa_map_<year>_v<schema>.bin`. Schema bumps invalidate
 * older caches automatically (the version is encoded in the filename and
 * checked again in the header). Read failures fall through to the JSON
 * path; write failures are swallowed — the cache is best-effort and the
 * app stays alive without it.
 *
 * Format (all big-endian, [DataOutputStream]):
 *
 * ```
 *   int  magic      0x504C4159  ('PLAY')
 *   int  schema     SCHEMA_VERSION
 *   utf  year
 *   layer<PolygonRing>   trashFence
 *   layer<StreetLine>    streetLines
 *   layer<PolygonRing>   streetOutlines
 *   layer<PolygonRing>   cityBlocks
 *   layer<PolygonRing>   plazas
 *   layer<PolygonRing>   toilets
 *   layer<PointFeature>  cpns
 *   layer<PointFeature>  art
 * ```
 *
 * `layer<T>` is `int count` then `count` records. Records use a single-
 * byte field-presence flag for nullable strings / kind / width and a
 * `[lon, lat] * N` Double payload for polylines.
 */
class PlayaMapBinaryCache(private val cacheDir: File) {
    // Broad catch + swallow are deliberate: a malformed/torn cache or IO
    // failure must degrade to a silent miss (caller falls through to JSON),
    // never crash the load.
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun read(year: String): PlayaMap? {
        val file = fileFor(year)
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != MAGIC || version != SCHEMA_VERSION) return@use null
                val storedYear = input.readUTF()
                if (storedYear != year) return@use null
                PlayaMap(
                    year = storedYear,
                    trashFence = readPolygonRingList(input),
                    streetLines = readStreetLineList(input),
                    streetOutlines = readPolygonRingList(input),
                    cityBlocks = readPolygonRingList(input),
                    plazas = readPolygonRingList(input),
                    toilets = readPolygonRingList(input),
                    cpns = readPointFeatureList(input),
                    art = readPointFeatureList(input),
                )
            }
        } catch (ioe: IOException) {
            null
        } catch (re: RuntimeException) {
            // Malformed cache (e.g. NegativeArraySize, IndexOutOfBounds,
            // BufferUnderflow): treat as a miss, fall through to JSON.
            null
        }
    }

    @Suppress("SwallowedException") // Cache write is best-effort; failure must not abort the load.
    fun write(
        year: String,
        map: PlayaMap,
    ) {
        val file = fileFor(year)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(SCHEMA_VERSION)
                out.writeUTF(year)
                writePolygonRingList(out, map.trashFence)
                writeStreetLineList(out, map.streetLines)
                writePolygonRingList(out, map.streetOutlines)
                writePolygonRingList(out, map.cityBlocks)
                writePolygonRingList(out, map.plazas)
                writePolygonRingList(out, map.toilets)
                writePointFeatureList(out, map.cpns)
                writePointFeatureList(out, map.art)
            }
            // Stream fully flushed/closed by use{}; swap atomically. A torn
            // write leaves only the .tmp, never a half-written final cache.
            if (!tmp.renameTo(file)) throw IOException("rename failed: $tmp -> $file")
        } catch (ioe: IOException) {
            // Next launch retries; JSON path remains available.
            tmp.delete()
        }
    }

    private fun fileFor(year: String): File = File(cacheDir, "playa_map_${year}_v$SCHEMA_VERSION.bin")

    private fun writePolygonRingList(
        out: DataOutputStream,
        list: List<PolygonRing>,
    ) {
        out.writeInt(list.size)
        for (p in list) {
            writeNullableUtf(out, p.name)
            writeLatLonList(out, p.ring)
        }
    }

    private fun readPolygonRingList(input: DataInputStream): List<PolygonRing> {
        val count = readCount(input)
        val out = ArrayList<PolygonRing>(count)
        repeat(count) {
            val name = readNullableUtf(input)
            val ring = readLatLonList(input)
            out.add(PolygonRing(name = name, ring = ring))
        }
        return out
    }

    private fun writeStreetLineList(
        out: DataOutputStream,
        list: List<StreetLine>,
    ) {
        out.writeInt(list.size)
        for (s in list) {
            writeNullableUtf(out, s.name)
            writeNullableInt(out, s.kind?.ordinal)
            writeNullableInt(out, s.widthFeet)
            writeLatLonList(out, s.points)
        }
    }

    private fun readStreetLineList(input: DataInputStream): List<StreetLine> {
        val count = readCount(input)
        val kinds = StreetKind.entries
        val out = ArrayList<StreetLine>(count)
        repeat(count) {
            val name = readNullableUtf(input)
            val kindOrdinal = readNullableInt(input)
            val widthFeet = readNullableInt(input)
            val points = readLatLonList(input)
            out.add(
                StreetLine(
                    name = name,
                    kind = kindOrdinal?.let { kinds[it] },
                    widthFeet = widthFeet,
                    points = points,
                ),
            )
        }
        return out
    }

    private fun writePointFeatureList(
        out: DataOutputStream,
        list: List<PointFeature>,
    ) {
        out.writeInt(list.size)
        for (p in list) {
            writeNullableUtf(out, p.name)
            writeNullableUtf(out, p.kind)
            out.writeDouble(p.location.lon)
            out.writeDouble(p.location.lat)
        }
    }

    private fun readPointFeatureList(input: DataInputStream): List<PointFeature> {
        val count = readCount(input)
        val out = ArrayList<PointFeature>(count)
        repeat(count) {
            val name = readNullableUtf(input)
            val kind = readNullableUtf(input)
            val lon = input.readDouble()
            val lat = input.readDouble()
            out.add(PointFeature(name = name, kind = kind, location = LatLon(lon = lon, lat = lat)))
        }
        return out
    }

    private fun writeLatLonList(
        out: DataOutputStream,
        points: List<LatLon>,
    ) {
        out.writeInt(points.size)
        for (p in points) {
            out.writeDouble(p.lon)
            out.writeDouble(p.lat)
        }
    }

    private fun readLatLonList(input: DataInputStream): List<LatLon> {
        val count = readCount(input)
        val out = ArrayList<LatLon>(count)
        repeat(count) { out.add(LatLon(lon = input.readDouble(), lat = input.readDouble())) }
        return out
    }

    private fun writeNullableUtf(
        out: DataOutputStream,
        value: String?,
    ) {
        if (value == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeUTF(value)
        }
    }

    private fun readNullableUtf(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    private fun writeNullableInt(
        out: DataOutputStream,
        value: Int?,
    ) {
        if (value == null) {
            out.writeBoolean(false)
        } else {
            out.writeBoolean(true)
            out.writeInt(value)
        }
    }

    private fun readNullableInt(input: DataInputStream): Int? = if (input.readBoolean()) input.readInt() else null

    /**
     * Reads a count/length and rejects out-of-range values before any
     * allocation. A corrupt-but-header-valid cache can encode a negative
     * count (NegativeArraySizeException) or a huge one (OutOfMemoryError);
     * both escape the IOException catch and crash the load. Treat either as
     * a corrupt cache by throwing IOException, which the read() catch turns
     * into a clean miss (null) and the caller falls through to JSON.
     */
    private fun readCount(input: DataInputStream): Int {
        val count = input.readInt()
        if (count !in 0..MAX_CACHE_COUNT) throw IOException("corrupt cache count: $count")
        return count
    }

    companion object {
        // 'PLAY' as ASCII so a hex dump of the file header is recognisable.
        private const val MAGIC = 0x504C4159

        // Bump on any breaking change to the on-disk shape — older caches
        // become unreadable and re-derive from JSON automatically.
        private const val SCHEMA_VERSION = 1

        // Upper bound on any count/length read from the cache. Anything
        // larger is taken as corruption rather than a real element count,
        // guarding against giant ArrayList allocations (OutOfMemoryError).
        private const val MAX_CACHE_COUNT = 5_000_000
    }
}
