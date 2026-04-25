package ai.openclaw.zodiaccontrol.data.playa

import ai.openclaw.zodiaccontrol.core.model.StreetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoJsonParserTest {
    @Test
    fun parse_street_lines_extracts_name_kind_width_and_path() {
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-119.2,40.78],[-119.21,40.79]]},
               "properties":{"name":"4:30","width":"40","type":"radial"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-119.2,40.78],[-119.205,40.785]]},
               "properties":{"name":"Esplanade","width":"30","type":"arc"}}
            ]}
            """.trimIndent()

        val streets = GeoJsonParser.parseStreetLines(raw)

        assertEquals(2, streets.size)
        assertEquals("4:30", streets[0].name)
        assertEquals(StreetKind.Radial, streets[0].kind)
        assertEquals(40, streets[0].widthFeet)
        assertEquals(2, streets[0].points.size)
        assertEquals(-119.2, streets[0].points[0].lon, 0.0)
        assertEquals(StreetKind.Arc, streets[1].kind)
    }

    @Test
    fun parse_polygons_uses_outer_ring_and_optional_name_key() {
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-119.2,40.78],[-119.21,40.78],[-119.21,40.79],[-119.2,40.78]]]},
               "properties":{"Name":"3:00 & B Plaza"}}
            ]}
            """.trimIndent()

        val polys = GeoJsonParser.parsePolygons(raw, nameKey = "Name")

        assertEquals(1, polys.size)
        assertEquals("3:00 & B Plaza", polys[0].name)
        assertEquals(4, polys[0].ring.size)
    }

    @Test
    fun parse_points_returns_named_locations() {
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-119.203,40.787]},
               "properties":{"NAME":"The Man","TYPE":"CPN"}}
            ]}
            """.trimIndent()

        val points = GeoJsonParser.parsePoints(raw, nameKey = "NAME", kindKey = "TYPE")

        assertEquals(1, points.size)
        assertEquals("The Man", points[0].name)
        assertEquals("CPN", points[0].kind)
        assertEquals(-119.203, points[0].location.lon, 0.0)
    }

    @Test
    fun unknown_geometry_types_are_skipped() {
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[]},"properties":{}}
            ]}
            """.trimIndent()

        assertEquals(0, GeoJsonParser.parseStreetLines(raw).size)
    }

    @Test
    fun missing_optional_properties_are_null() {
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-119.2,40.78],[-119.21,40.79]]},
               "properties":{}}
            ]}
            """.trimIndent()

        val streets = GeoJsonParser.parseStreetLines(raw)

        assertEquals(1, streets.size)
        assertNull(streets[0].name)
        assertNull(streets[0].kind)
        assertNull(streets[0].widthFeet)
    }
}
