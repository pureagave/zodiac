package org.pureagave.zodiac.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Nmea.zver]'s own sanitisation/clamp logic — the branches the golden corpus
 * (clean inputs only) does not exercise. Asserts the sentence *body* fields so no
 * checksum has to be hand-computed; the byte-for-byte agreement with the other
 * two implementations lives in [BeaconVersionGoldenTest].
 */
class NmeaZverTest {
    private fun fields(sentence: String): List<String> = sentence.substringAfter('$').substringBefore('*').split(',')

    @Test
    fun node_is_uppercased_filtered_and_last_8() {
        // Non-[A-Z0-9] stripped, uppercased, last 8 kept.
        assertEquals("34567890", fields(zver(node = "ab-cd-ef-12-34-56-78-90"))[1])
    }

    @Test
    fun illegal_characters_are_stripped_from_name_and_base() {
        val f = fields(zver(name = "host name!*,", base = "1.0 beta,;"))
        assertEquals("hostname", f[2]) // space, !, *, comma dropped
        assertEquals("1.0beta", f[3]) // space, comma, ; dropped; '.' kept
    }

    @Test
    fun empty_after_sanitising_falls_back() {
        val f = fields(zver(node = "!!!", name = ",,,", base = " "))
        assertEquals("0", f[1])
        assertEquals("node", f[2])
        assertEquals("0.0.0", f[3])
    }

    @Test
    fun epoch_is_clamped_into_the_grammar() {
        assertEquals("0", fields(zver(epoch = -1L))[6])
        assertEquals("9999999999", fields(zver(epoch = 1_000_000_000_000_000L))[6])
    }

    @Test
    fun dirty_flag_encodes_0_or_1() {
        assertEquals("1", fields(zver(dirty = true))[5])
        assertEquals("0", fields(zver(dirty = false))[5])
    }

    @Test
    fun the_sentence_is_framed_with_a_correct_xor_checksum() {
        val s = zver()
        val body = s.substringAfter('$').substringBefore('*')
        val cc = s.substringAfter('*').trimEnd('\r', '\n')
        assertEquals("the framed checksum must be the XOR of the body", Nmea.checksum(body), cc)
        assertTrue("must end with the NMEA CRLF terminator", s.endsWith("\r\n"))
    }

    private fun zver(
        node: String = "9C1977",
        name: String = "SM-X810",
        base: String = "0.1.0",
        sha: String = "8f531e18a",
        dirty: Boolean = false,
        epoch: Long = 1_691_900_000L,
    ): String = Nmea.zver(node, name, base, sha, dirty, epoch)
}
