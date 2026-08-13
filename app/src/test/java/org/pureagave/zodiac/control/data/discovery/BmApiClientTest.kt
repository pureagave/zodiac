package org.pureagave.zodiac.control.data.discovery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
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
    fun art_with_zeroed_coordinates_survives_but_is_not_placed() {
        // (0,0) is thousands of km from BRC (40.78N, -119.2W) -- exactly the
        // shape a missing/zeroed GPS field takes in a real feed.
        val p =
            client.parseArt(
                JSONObject("""{"uid":"z","name":"Ghost Piece","location":{"gps_latitude":0.0,"gps_longitude":0.0}}"""),
            )!!
        assertEquals("Ghost Piece", p.name)
        assertNull("implausible coordinates must not place a marker", p.point)
    }

    @Test
    fun art_just_inside_the_five_km_plausibility_gate_is_placed() {
        // The existing zeroed/swapped tests land thousands of km out, so they'd
        // still pass if the 5 km gate were widened to, say, 5000 km. These two
        // pin the actual threshold: 4990 m in must place, 5010 m out must not.
        val p = client.parseArt(artDueNorth(4_990.0))!!
        assertNotNull("~4990 m out is inside the 5 km gate and must place", p.point)
    }

    @Test
    fun art_just_outside_the_five_km_plausibility_gate_is_rejected() {
        val p = client.parseArt(artDueNorth(5_010.0))!!
        assertNull("~5010 m out is past the 5 km gate and must not place", p.point)
    }

    /**
     * Art record whose GPS lands exactly [meters] due north of the active Golden
     * Spike. Longitude equals the origin's, so the east offset is zero and the
     * projected radius *is* [meters] — computed here from the Earth radius the
     * projection uses, independently of the gate under test.
     */
    private fun artDueNorth(meters: Double): JSONObject {
        val lat = GoldenSpike.ACTIVE.lat + Math.toDegrees(meters / PlayaProjection.EARTH_RADIUS_M)
        val lon = GoldenSpike.ACTIVE.lon
        return JSONObject("""{"uid":"b","name":"Boundary","location":{"gps_latitude":$lat,"gps_longitude":$lon}}""")
    }

    @Test
    fun art_with_swapped_lat_lon_is_rejected() {
        // Golden Spike is roughly (40.78, -119.2); swapping the fields lands the
        // point ~160 degrees of longitude away from where it should be.
        val p =
            client.parseArt(
                JSONObject(
                    """{"uid":"s","name":"Swapped Piece","location":{"gps_latitude":-119.196602,"gps_longitude":40.791799}}""",
                ),
            )!!
        assertNull("swapped lat/lon must not place a marker", p.point)
    }

    @Test
    fun art_with_implausible_coordinates_increments_the_rejection_callback() {
        var rejected = 0
        val p =
            client.parseArt(
                JSONObject("""{"uid":"z","name":"Ghost Piece","location":{"gps_latitude":0.0,"gps_longitude":0.0}}"""),
                onRejectedCoordinate = { rejected++ },
            )!!
        assertNull(p.point)
        assertEquals(1, rejected)
    }

    @Test
    fun art_with_plausible_coordinates_does_not_invoke_the_rejection_callback() {
        var rejected = 0
        client.parseArt(
            JSONObject(
                """{"uid":"art1","name":"The Temple","location":{"gps_latitude":40.791799,"gps_longitude":-119.196602}}""",
            ),
            onRejectedCoordinate = { rejected++ },
        )
        assertEquals(0, rejected)
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
