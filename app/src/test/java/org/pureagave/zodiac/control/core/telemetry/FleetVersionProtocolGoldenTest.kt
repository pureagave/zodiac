package org.pureagave.zodiac.control.core.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tablet's half of the cross-language `$ZVER` contract (FLEET-1).
 *
 * Reads `protocol/version-protocol-golden.json` — the same file
 * `jetson/tests/test_version_protocol_golden.py` reads — and checks this
 * implementation against it. Neither the Kotlin nor the Python (nor the beacon's)
 * implementation is authoritative; the corpus is. If you change the `$ZVER`
 * format, this suite fails until the corpus is regenerated
 * (`protocol/gen-version-golden.py`) and every side agrees. That is the feature.
 */
class FleetVersionProtocolGoldenTest {
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
                "The \$ZVER wire format is a contract with jetson/zvision/version_protocol.py " +
                "and the :beacon builder, and the corpus is the only thing enforcing it.\nLooked in:\n" +
                tried.joinToString("\n") { "  $it" },
        )
    }

    /** A corpus truncated to nothing would make every other test here vacuously green. */
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
    fun the_shared_header_matches_this_implementation() {
        // FleetVersionProtocol keeps its type private, so verify via output: a
        // built sentence must open with the corpus's declared header.
        assertEquals("ZVER", corpus.getString("header"))
        val sample = FleetVersionProtocol.build(FleetVersion("AB12CD", "SM-X810", BuildIdentity("0.1.0", "abcdef123", false, 1L)))
        assertTrue("build() must emit a \$${corpus.getString("header")} sentence", sample.startsWith("\$ZVER,"))
    }

    @Test
    fun every_format_vector_matches_byte_for_byte() {
        val vectors = corpus.getJSONArray("format_vectors")
        for (i in 0 until vectors.length()) {
            val f = vectors.getJSONObject(i).getJSONObject("fields")
            val expected = vectors.getJSONObject(i).getString("line")
            val version = fleetVersionOf(f)
            // The corpus stores the sentence up to the checksum; build() appends
            // the CRLF terminator, which is framing, not payload — strip it.
            assertEquals(expected, FleetVersionProtocol.build(version).trimEnd('\r', '\n'))
        }
    }

    @Test
    fun every_parse_vector_matches() {
        val vectors = corpus.getJSONArray("parse_vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val actual = FleetVersionProtocol.parse(v.getString("line"))
            if (!v.getBoolean("valid")) {
                assertNull("[${v.optString("why", "line $i")}] expected this line to be rejected", actual)
                continue
            }
            val f = v.getJSONObject("fields")
            assertNotNull("expected a parsed \$ZVER, got null for ${v.getString("line")}", actual)
            assertEquals(fleetVersionOf(f), actual)
        }
    }

    private fun fleetVersionOf(f: JSONObject): FleetVersion =
        FleetVersion(
            node = f.getString("node"),
            name = f.getString("name"),
            identity = BuildIdentity(f.getString("base"), f.getString("sha"), f.getBoolean("dirty"), f.getLong("epoch")),
        )

    private companion object {
        const val MIN_PARSE_VECTORS = 20
        const val MIN_FORMAT_VECTORS = 5
    }
}
