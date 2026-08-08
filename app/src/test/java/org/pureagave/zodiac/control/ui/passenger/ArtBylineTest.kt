package org.pureagave.zodiac.control.ui.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind

class ArtBylineTest {
    @Test
    fun the_artist_comes_first_because_that_is_who_to_thank() {
        val poi = art(artist = "Abram Santa Cruz", hometown = "Long Beach, CA, United States")

        assertEquals("ABRAM SANTA CRUZ  ·  LONG BEACH, CA, UNITED STATES", artByline(poi))
    }

    @Test
    fun a_missing_hometown_does_not_leave_a_dangling_separator() {
        assertEquals("ABRAM SANTA CRUZ", artByline(art(artist = "Abram Santa Cruz", hometown = null)))
        assertEquals("ABRAM SANTA CRUZ", artByline(art(artist = "Abram Santa Cruz", hometown = "")))
    }

    @Test
    fun an_unattributed_piece_says_so_rather_than_showing_an_empty_line() {
        assertEquals("ARTIST UNKNOWN", artByline(art(artist = "", hometown = null)))
    }

    @Test
    fun no_art_nearby_explains_itself() {
        // Today's real state: BM has published 2026 art names but no
        // placements, so nothing can be positioned yet.
        assertTrue(artByline(null).contains("NOTHING PLACED"))
    }

    private fun art(
        artist: String,
        hometown: String?,
    ) = PlayaPoi(
        uid = "u",
        name = "Electric Dandelion",
        kind = PoiKind.ART,
        point = null,
        subtitle = artist,
        hometown = hometown,
        description = "A 24 ft. tall dandelion sculpture that doubles as fireworks at night.",
    )
}
