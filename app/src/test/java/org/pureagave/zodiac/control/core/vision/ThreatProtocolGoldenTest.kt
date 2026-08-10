package org.pureagave.zodiac.control.core.vision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tablet's half of the cross-language ZTHREAT contract.
 *
 * This suite asserts nothing of its own invention: it reads
 * `protocol/threat-protocol-golden.json`, the same file
 * `jetson/tests/test_threat_protocol_golden.py` reads, and checks this
 * implementation against it. That is the whole point — the Kotlin parser and the
 * Python one are written by hand in different languages, and until this corpus
 * existed the only thing keeping them in step was that the same person had
 * recently read both. They had silently drifted in ten measured ways.
 *
 * If you change the wire format, this suite fails until the corpus is
 * regenerated *and* the Python side agrees with it. That is the feature.
 */
class ThreatProtocolGoldenTest {
    private val corpus: JSONObject by lazy { JSONObject(corpusFile().readText()) }

    /**
     * Walk up from the Gradle test working directory (the `app/` module dir) to
     * the repo root. Resolved at runtime rather than hard-coded so the suite
     * survives being run from the module, the root, or CI — and fails with the
     * paths it tried rather than a bare FileNotFoundException.
     */
    private fun corpusFile(): File {
        val tried = mutableListOf<File>()
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, "protocol/threat-protocol-golden.json")
            tried += candidate
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError(
            "Golden corpus not found — this suite cannot silently pass without it.\n" +
                "The ZTHREAT wire format is a contract with jetson/zvision/threat_protocol.py " +
                "and the corpus is the only thing enforcing it.\nLooked in:\n" +
                tried.joinToString("\n") { "  $it" },
        )
    }

    /**
     * A corpus that failed to load, or got truncated to nothing, would make every
     * other test in this class vacuously green — the exact failure mode this
     * project has been bitten by five times ("tests that agree with the code they
     * test"). So assert the corpus is substantial before trusting it.
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

    /** The constants are part of the contract, not private implementation detail. */
    @Test
    fun the_shared_constants_match_this_implementation() {
        assertEquals(corpus.getString("header"), ThreatProtocol.HEADER)
        // Reached through the wire rather than through a visibility change: a
        // frame of exactly max_contacts survives whole, one more is truncated.
        val max = corpus.getInt("max_contacts")
        val frame = StringBuilder(ThreatProtocol.HEADER)
        repeat(max + 1) { frame.append(";$it:0.0:0.500:0") }
        assertEquals(max, ThreatProtocol.parse(frame.toString())!!.size)
    }

    @Test
    fun every_parse_vector_matches() {
        val vectors = corpus.getJSONArray("parse_vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val actual = ThreatProtocol.parse(v.getString("frame"))
            if (v.isNull("expect")) {
                assertNull("[$name] expected this line to be rejected as not a ZTHREAT frame", actual)
                continue
            }
            val expect = v.getJSONArray("expect")
            assertNotNull("[$name] expected a parsed frame, got null", actual)
            assertEquals("[$name] contact count", expect.length(), actual!!.size)
            for (j in 0 until expect.length()) {
                val e = expect.getJSONObject(j)
                val a = actual[j]
                assertEquals("[$name] contact $j id", e.getInt("id"), a.id)
                // Exact, not approximate: the corpus records the value a 32-bit
                // float actually holds, and the Python side mirrors that width.
                assertEquals("[$name] contact $j az", e.getDouble("az").toFloat(), a.relAzDeg, 0f)
                assertEquals("[$name] contact $j size", e.getDouble("size").toFloat(), a.size, 0f)
                assertEquals("[$name] contact $j collision", e.getBoolean("collision"), a.collision)
            }
        }
    }

    @Test
    fun every_format_vector_matches_byte_for_byte() {
        val vectors = corpus.getJSONArray("format_vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val contacts = v.getJSONArray("contacts")
            val threats =
                (0 until contacts.length()).map { j ->
                    val c = contacts.getJSONObject(j)
                    DriverThreat(
                        relAzDeg = number(c.get("az")),
                        size = number(c.get("size")),
                        collision = c.getBoolean("col"),
                        id = c.getInt("id"),
                    )
                }
            assertEquals("[$name]", v.getString("frame"), ThreatProtocol.format(threats))
        }
    }

    /** JSON cannot carry NaN/Infinity as numbers, so the corpus spells them. */
    private fun number(v: Any): Float =
        when (v) {
            "NaN" -> Float.NaN
            "Infinity" -> Float.POSITIVE_INFINITY
            "-Infinity" -> Float.NEGATIVE_INFINITY
            else -> (v as Number).toFloat()
        }

    private companion object {
        const val MIN_PARSE_VECTORS = 100
        const val MIN_FORMAT_VECTORS = 20
    }
}
