package org.pureagave.zodiac.control.core.vision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tablet's half of the cross-language ZCOVER contract (RES-P2-1).
 *
 * Reads `protocol/coverage-protocol-golden.json` — the same file
 * `jetson/tests/test_coverage_golden.py` reads — and checks this implementation
 * against it. Neither the Kotlin consumer nor the Python producer is
 * authoritative; the corpus is. If you change the ZCOVER format, this suite
 * fails until the corpus is regenerated (`jetson/tools/gen_coverage_golden.py`)
 * and the Python side agrees. That is the feature.
 */
class CoverageProtocolGoldenTest {
    private val corpus: JSONObject by lazy { JSONObject(corpusFile().readText()) }

    /** Walk up from the Gradle test working directory to the repo root; fail with the paths tried. */
    private fun corpusFile(): File {
        val tried = mutableListOf<File>()
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, "protocol/coverage-protocol-golden.json")
            tried += candidate
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "Coverage golden corpus not found — this suite cannot silently pass without it.\n" +
                "The ZCOVER wire format is a contract with jetson/zvision/coverage_protocol.py " +
                "and the corpus is the only thing enforcing it.\nLooked in:\n" +
                tried.joinToString("\n") { "  $it" },
        )
    }

    /**
     * A corpus truncated to nothing would make every other test here vacuously
     * green — the failure mode this project has been bitten by repeatedly.
     */
    @Test
    fun the_corpus_is_present_and_substantial() {
        assertTrue(
            "expected the shared corpus to hold a real set of parse vectors",
            corpus.getJSONArray("parse_vectors").length() >= MIN_PARSE_VECTORS,
        )
        assertTrue(
            "expected the shared corpus to hold a real set of format vectors",
            corpus.getJSONArray("format_vectors").length() >= MIN_FORMAT_VECTORS,
        )
    }

    @Test
    fun the_shared_constants_match_this_implementation() {
        assertEquals(corpus.getString("header"), CoverageProtocol.HEADER)
    }

    @Test
    fun every_parse_vector_matches() {
        val vectors = corpus.getJSONArray("parse_vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val actual = CoverageProtocol.parse(v.getString("frame"))
            if (v.isNull("expect")) {
                assertNull("[$name] expected this line to be rejected as not a ZCOVER frame", actual)
                continue
            }
            val expect = v.getJSONArray("expect")
            assertNotNull("[$name] expected a parsed coverage frame, got null", actual)
            assertEquals("[$name] arc count", expect.length(), actual!!.size)
            for (j in 0 until expect.length()) {
                val e = expect.getJSONArray(j)
                // Exact, not approximate: the corpus records the value a 32-bit
                // float actually holds, and the Python side mirrors that width.
                assertEquals("[$name] arc $j start", e.getDouble(0).toFloat(), actual[j].start, 0f)
                assertEquals("[$name] arc $j end", e.getDouble(1).toFloat(), actual[j].endInclusive, 0f)
            }
        }
    }

    @Test
    fun every_format_vector_matches_byte_for_byte() {
        val vectors = corpus.getJSONArray("format_vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val arcsJson = v.getJSONArray("arcs")
            val arcs =
                (0 until arcsJson.length()).map { j ->
                    val a = arcsJson.getJSONArray(j)
                    a.getDouble(0).toFloat()..a.getDouble(1).toFloat()
                }
            assertEquals("[$name]", v.getString("frame"), CoverageProtocol.format(arcs))
        }
    }

    private companion object {
        const val MIN_PARSE_VECTORS = 12
        const val MIN_FORMAT_VECTORS = 5
    }
}
