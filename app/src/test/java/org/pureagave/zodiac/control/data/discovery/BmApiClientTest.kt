package org.pureagave.zodiac.control.data.discovery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.ops.PoiKind

/**
 * Exercises the BM-API record parsers directly (JSON in → PlayaPoi out), no
 * network. Pins the placement + drop rules every discovered art/camp depends on.
 */
class BmApiClientTest {
    private val client = BmApiClient(apiKey = "test")

    @Test
    fun art_with_gps_gets_a_point_and_artist_subtitle() {
        val p =
            client.parseArt(
                JSONObject(
                    """{"uid":"art1","name":"The Temple","artist":"Jane",
                       "location":{"gps_latitude":40.791799,"gps_longitude":-119.196602}}""",
                ),
            )!!
        assertEquals("The Temple", p.name)
        assertEquals(PoiKind.ART, p.kind)
        assertNotNull("art with GPS should be placed", p.point)
        assertEquals("Jane", p.subtitle)
    }

    @Test
    fun art_without_location_is_still_listed_with_a_null_point() {
        val p = client.parseArt(JSONObject("""{"uid":"art2","name":"No GPS Art","artist":"Bob"}"""))!!
        assertEquals("No GPS Art", p.name)
        assertNull(p.point)
    }

    @Test
    fun art_subtitle_falls_back_to_location_string_without_an_artist() {
        val p = client.parseArt(JSONObject("""{"uid":"a","name":"Piece","location":{"location_string":"9:00 & C"}}"""))!!
        assertEquals("9:00 & C", p.subtitle)
    }

    @Test
    fun blank_name_art_is_dropped() {
        assertNull(client.parseArt(JSONObject("""{"uid":"x","name":"","artist":"X"}""")))
        assertNull(client.parseArt(JSONObject("""{"uid":"x","name":"   "}""")))
    }

    @Test
    fun camp_with_a_clock_address_is_placed() {
        // NB (latent discrepancy, flagged for real-API verification): parseCamp
        // reads `location_string` from the TOP LEVEL of the record, whereas
        // parseArt reads it NESTED under `location`. If the real API nests it for
        // camps too, camp subtitles are empty in production. This pins current
        // behaviour (top-level) so any change is deliberate.
        val p =
            client.parseCamp(
                JSONObject(
                    """{"uid":"c1","name":"Camp E","location_string":"2:00 & E",
                       "location":{"frontage":"2:00","intersection":"E"}}""",
                ),
            )!!
        assertEquals(PoiKind.CAMP, p.kind)
        assertNotNull("a clock/street camp should be placed", p.point)
        assertEquals("2:00 & E", p.subtitle)
    }

    @Test
    fun camp_with_an_unplaceable_address_survives_with_a_null_point() {
        val p =
            client.parseCamp(
                JSONObject("""{"uid":"c2","name":"Plaza Camp","location":{"frontage":"2:00","intersection":"Portal"}}"""),
            )!!
        assertEquals("Plaza Camp", p.name)
        assertNull(p.point)
    }

    @Test
    fun blank_name_camp_is_dropped() {
        assertNull(client.parseCamp(JSONObject("""{"uid":"y","name":"","location":{"frontage":"2:00","intersection":"E"}}""")))
    }
}
