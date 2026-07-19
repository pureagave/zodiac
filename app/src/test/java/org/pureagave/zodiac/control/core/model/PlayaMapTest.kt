package org.pureagave.zodiac.control.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon

class PlayaMapTest {
    @Test
    fun polygon_centroid_is_the_vertex_average() {
        val square =
            PolygonRing(
                name = null,
                ring =
                    listOf(
                        LatLon(lon = 0.0, lat = 0.0),
                        LatLon(lon = 2.0, lat = 0.0),
                        LatLon(lon = 2.0, lat = 2.0),
                        LatLon(lon = 0.0, lat = 2.0),
                    ),
            )
        val c = square.centroid!!
        assertEquals(1.0, c.lon, 1e-6)
        assertEquals(1.0, c.lat, 1e-6)
    }

    @Test
    fun empty_ring_has_no_centroid() {
        assertNull(PolygonRing(name = null, ring = emptyList()).centroid)
    }
}
