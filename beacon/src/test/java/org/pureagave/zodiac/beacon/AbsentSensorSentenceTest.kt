package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [hdtSentenceOrNull] / [zenvSentenceOrNull] exist so that a device with no
 * rotation-vector or light sensor emits NOTHING for that channel instead of a
 * lying `0.0` (AUDIT-2026-08-09 C1). The critical distinction these tests
 * enforce: absence (null) and a genuine zero reading are different facts, and
 * a device that has never seen a real reading must never be indistinguishable
 * from one reporting true north / true darkness.
 */
class AbsentSensorSentenceTest {
    @Test
    fun no_lux_reading_emits_no_env_sentence() {
        // Mutation: `lux?.let { Nmea.zenv(it) } ?: Nmea.zenv(0.0)` — the shipped
        // bug, papering over "no sensor" with a fabricated reading.
        assertNull(zenvSentenceOrNull(null))
    }

    @Test
    fun a_real_zero_lux_reading_is_still_emitted() {
        // Mutation: `lux?.takeIf { it > 0 }?.let { Nmea.zenv(it) }` — treats
        // genuine darkness (0.0 lux, a real reading) as if it were absent.
        val sentence = zenvSentenceOrNull(0.0)
        assertNotNull("a real 0.0 lux reading must still be broadcast", sentence)
        assertTrue(sentence!!.contains("0.0"))
        assertTrue(sentence.startsWith("\$ZENV,0.0"))
    }

    @Test
    fun no_heading_reading_emits_no_hdt_sentence() {
        // Mutation: same absent-as-zero fallback error, on the compass channel.
        assertNull(hdtSentenceOrNull(null))
    }

    @Test
    fun a_real_zero_heading_is_still_emitted() {
        // Mutation: `headingDeg?.takeIf { it > 0 }?.let { Nmea.hdt(it) }` — due
        // north (0.0°) is a valid heading, not an absent one.
        val sentence = hdtSentenceOrNull(0.0)
        assertNotNull("a real 0.0 degree heading must still be broadcast", sentence)
        assertTrue(sentence!!.contains("0.0"))
        assertTrue(sentence.startsWith("\$GPHDT,0.0,T"))
    }

    @Test
    fun emitted_sentences_still_carry_a_valid_checksum() {
        // Guards against a wrapper that reformats or truncates the sentence
        // and drops/garbles the trailing checksum on the way out.
        val hdt = hdtSentenceOrNull(123.4)
        val zenv = zenvSentenceOrNull(315.0)
        assertNotNull(hdt)
        assertNotNull(zenv)
        listOf(hdt!!, zenv!!).forEach { sentence ->
            val body = sentence.substringAfter('$').substringBefore('*')
            val tail = sentence.substringAfter('*').trimEnd('\r', '\n')
            assertEquals("checksum must be exactly two hex digits: $sentence", 2, tail.length)
            assertEquals("checksum must be uppercase hex: $sentence", tail.uppercase(), tail)
            assertEquals(Nmea.checksum(body), tail)
        }
    }
}
