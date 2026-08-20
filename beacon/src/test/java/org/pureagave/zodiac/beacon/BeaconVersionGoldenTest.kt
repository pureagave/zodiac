package org.pureagave.zodiac.beacon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The beacon's half of the cross-language `$ZVER` contract (FLEET-1).
 *
 * The beacon only *emits*, so it validates only the **format** vectors: for every
 * clean input in `protocol/version-protocol-golden.json` — the same file the
 * tablet's `FleetVersionProtocolGoldenTest.kt` and the Jetson's
 * `test_version_protocol_golden.py` read — [Nmea.zver] must produce the exact
 * sentence, byte for byte. If the beacon's builder drifts from the wire, this
 * fails; that is the feature.
 */
class BeaconVersionGoldenTest {
    private val corpus: JSONObject by lazy { JSONObject(corpusFile().readText()) }

    /** Walk up from the Gradle test working directory to the repo root; fail with the paths tried. */
    private fun corpusFile(): File {
        val tried = mutableListOf<File>()
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, "protocol/version-protocol-golden.json")
            tried += candidate
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "Version golden corpus not found — this suite cannot silently pass without it.\n" +
                "Looked in:\n" + tried.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun the_corpus_is_present_and_substantial() {
        assertTrue(
            "expected the shared corpus to hold a real set of format vectors",
            corpus.getJSONArray("format_vectors").length() >= MIN_FORMAT_VECTORS,
        )
        assertEquals("ZVER", corpus.getString("header"))
    }

    @Test
    fun every_format_vector_is_built_byte_for_byte() {
        val vectors = corpus.getJSONArray("format_vectors")
        for (i in 0 until vectors.length()) {
            val f = vectors.getJSONObject(i).getJSONObject("fields")
            val expected = vectors.getJSONObject(i).getString("line")
            // The corpus stores the sentence up to the checksum; Nmea.zver appends
            // the CRLF terminator (framing, not payload) — strip it before comparing.
            val built =
                Nmea.zver(
                    node = f.getString("node"),
                    name = f.getString("name"),
                    base = f.getString("base"),
                    sha = f.getString("sha"),
                    dirty = f.getBoolean("dirty"),
                    epoch = f.getLong("epoch"),
                ).trimEnd('\r', '\n')
            assertEquals(expected, built)
        }
    }

    private companion object {
        const val MIN_FORMAT_VECTORS = 5
    }
}
