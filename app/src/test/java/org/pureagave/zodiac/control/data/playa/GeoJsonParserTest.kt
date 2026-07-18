package org.pureagave.zodiac.control.data.playa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.model.StreetKind

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
    fun malformed_line_feature_is_dropped_valid_one_survives() {
        // One good LineString plus one whose coordinates contain a too-short
        // pair. The hardened parser drops just the bad feature instead of
        // throwing or failing the whole layer.
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-119.2,40.78],[-119.21,40.79]]},
               "properties":{"name":"Esplanade","type":"arc"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-119.2,40.78],[-119.21]]},
               "properties":{"name":"Broken","type":"radial"}}
            ]}
            """.trimIndent()

        val streets = GeoJsonParser.parseStreetLines(raw)

        assertEquals(1, streets.size)
        assertEquals("Esplanade", streets[0].name)
    }

    @Test
    fun string_coordinate_is_skipped_without_throwing() {
        // The specific case the optDouble change fixed: a coordinate element is
        // a string, not a number. getDouble previously threw JSONException and
        // aborted the whole layer; optDouble yields NaN, which drops the
        // feature and keeps the valid one.
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-119.2,40.78],[-119.21,40.79]]},
               "properties":{"name":"Good","type":"arc"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[["nope",40.78],[-119.21,40.79]]},
               "properties":{"name":"Bad","type":"radial"}}
            ]}
            """.trimIndent()

        val streets = GeoJsonParser.parseStreetLines(raw)

        assertEquals(1, streets.size)
        assertEquals("Good", streets[0].name)
    }

    @Test
    fun malformed_polygon_feature_is_dropped_valid_one_survives() {
        // A null coordinate value in one polygon ring drops just that feature;
        // the valid polygon still parses.
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-119.2,40.78],[-119.21,40.78],[-119.21,40.79],[-119.2,40.78]]]},
               "properties":{"Name":"Good Plaza"}},
              {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-119.2,40.78],[-119.21,null],[-119.21,40.79],[-119.2,40.78]]]},
               "properties":{"Name":"Bad Plaza"}}
            ]}
            """.trimIndent()

        val polys = GeoJsonParser.parsePolygons(raw, nameKey = "Name")

        assertEquals(1, polys.size)
        assertEquals("Good Plaza", polys[0].name)
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
