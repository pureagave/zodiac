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

    @Test
    fun the_address_leads_because_it_is_the_only_actionable_fact() {
        val poi = full().copy(address = "3:45 & Esplanade", guidedTours = true)

        val tags = artTags(poi, nearbyCount = 1)

        assertEquals("3:45 & ESPLANADE", tags.first())
        assertTrue(tags.contains("GUIDED TOURS"))
    }

    @Test
    fun funding_category_and_volunteer_calls_are_never_shown() {
        // Dropped deliberately: they read as filler beside the artist's own
        // words, and the slot belongs to the address.
        val poi = full().copy(needsVolunteers = true, category = "Open Playa", program = "Self-Funded")

        val tags = artTags(poi, nearbyCount = 1)

        assertTrue(tags.none { it.contains("VOLUNTEER") })
        assertTrue(tags.none { it.contains("OPEN PLAYA") })
        assertTrue(tags.none { it.contains("SELF-FUNDED") })
    }

    @Test
    fun with_no_address_yet_the_row_carries_only_what_is_known() {
        // The 2026 state today: placements embargoed, so no address exists.
        val bare = full().copy(address = null)

        assertEquals(emptyList<String>(), artTags(bare, nearbyCount = 1))
    }

    @Test
    fun the_label_follows_where_the_piece_is() {
        assertEquals("YOU ARE PARKED AT", artLabel(parked = true, abeam = true, approaching = true))
        assertEquals("PASSING", artLabel(parked = false, abeam = true, approaching = true))
        assertEquals("COMING UP", artLabel(parked = false, abeam = false, approaching = true))
        assertEquals("ART NEARBY", artLabel(parked = false, abeam = false, approaching = false))
    }

    @Test
    fun more_nearby_is_only_mentioned_when_there_is_more() {
        assertTrue(artTags(full(), nearbyCount = 3).contains("+2 MORE NEARBY"))
        assertTrue(artTags(full(), nearbyCount = 1).none { it.contains("MORE NEARBY") })
    }

    @Test
    fun nothing_nearby_produces_no_tags_at_all() {
        assertEquals(emptyList<String>(), artTags(null, nearbyCount = 0))
    }

    private fun full() =
        PlayaPoi(
            uid = "u",
            name = "Electric Dandelion",
            kind = PoiKind.ART,
            point = null,
            subtitle = "Abram Santa Cruz",
            hometown = "Long Beach, CA",
            description = "A 24 ft. tall dandelion sculpture.",
            category = "Open Playa",
            program = "Self-Funded",
        )

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
