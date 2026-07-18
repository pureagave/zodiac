package org.pureagave.zodiac.control.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.model.PolygonRing
import org.pureagave.zodiac.control.core.model.StreetKind
import org.pureagave.zodiac.control.core.model.StreetLine
import kotlin.math.hypot

class PlayaCityModelTest {
    // Origin at (0, 0): cosLat0 == 1, so a pure-north point (lon == 0) projects
    // to (0, EARTH_RADIUS_M * toRadians(lat)) and its radius from the Man is a
    // monotonic function of latitude alone — easy to reason about.
    private val proj = PlayaProjection(LatLon(lon = 0.0, lat = 0.0))

    /** Radius from the Man of a pure-north LatLon under [proj]. */
    private fun radiusOf(lat: Double): Double {
        val p = proj.project(LatLon(lon = 0.0, lat = lat))
        return hypot(p.eastM, p.northM)
    }

    private fun arcSegment(
        name: String,
        lats: List<Double>,
    ) = StreetLine(
        name = name,
        kind = StreetKind.Arc,
        widthFeet = 30,
        points = lats.map { LatLon(lon = 0.0, lat = it) },
    )

    private fun emptyMap(
        trashFence: List<PolygonRing> = emptyList(),
        streetLines: List<StreetLine> = emptyList(),
    ) = PlayaMap(
        year = "2025",
        trashFence = trashFence,
        streetLines = streetLines,
        streetOutlines = emptyList(),
        cityBlocks = emptyList(),
        plazas = emptyList(),
        toilets = emptyList(),
        cpns = emptyList(),
        art = emptyList(),
    )

    @Test
    fun `toCityModel - arc radius is mean of hypot across all its points`() {
        // One arc "Esplanade" carried as two segments. Its mean radius must be
        // the average of every point's radius, not a per-segment value.
        val map =
            emptyMap(
                streetLines =
                    listOf(
                        arcSegment("Esplanade", listOf(0.01, 0.01)),
                        arcSegment("Esplanade", listOf(0.03, 0.03)),
                    ),
            )

        val model = map.toCityModel(proj)

        val expectedMean = (radiusOf(0.01) + radiusOf(0.01) + radiusOf(0.03) + radiusOf(0.03)) / 4.0
        assertEquals(expectedMean, model.arcRadiiM.getValue("Esplanade"), 1e-6)
    }

    @Test
    fun `toCityModel - arcsInnerToOuter sorted by mean radius ascending`() {
        val map =
            emptyMap(
                streetLines =
                    listOf(
                        arcSegment("Outer", listOf(0.05)),
                        arcSegment("Inner", listOf(0.01)),
                        arcSegment("Middle", listOf(0.03)),
                    ),
            )

        val model = map.toCityModel(proj)

        assertEquals(listOf("Inner", "Middle", "Outer"), model.arcsInnerToOuter)
    }

    @Test
    fun `toCityModel - cityOuterRadiusM is the max arc radius`() {
        val map =
            emptyMap(
                streetLines =
                    listOf(
                        arcSegment("Inner", listOf(0.01)),
                        arcSegment("Outer", listOf(0.05)),
                    ),
            )

        val model = map.toCityModel(proj)

        assertEquals(radiusOf(0.05), model.cityOuterRadiusM, 1e-6)
    }

    @Test
    fun `toCityModel - no arcs leaves empty list and MAX_VALUE outer radius`() {
        // Only a radial present, so there are zero arcs. Documented fallback:
        // empty arc list and cityOuterRadiusM == Double.MAX_VALUE.
        val map =
            emptyMap(
                streetLines =
                    listOf(
                        StreetLine(
                            name = "6:00",
                            kind = StreetKind.Radial,
                            widthFeet = 40,
                            points = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.02)),
                        ),
                    ),
            )

        val model = map.toCityModel(proj)

        assertTrue(model.arcsInnerToOuter.isEmpty())
        assertTrue(model.arcRadiiM.isEmpty())
        assertEquals(Double.MAX_VALUE, model.cityOuterRadiusM, 0.0)
    }

    @Test
    fun `toCityModel - streets with null or empty name or null kind or empty points are dropped`() {
        val keeper =
            StreetLine(
                name = "Keeper",
                kind = StreetKind.Radial,
                widthFeet = 40,
                points = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.01)),
            )
        val map =
            emptyMap(
                streetLines =
                    listOf(
                        keeper,
                        StreetLine("NoKind", null, 40, listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.01))),
                        StreetLine(null, StreetKind.Radial, 40, listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.01))),
                        StreetLine("", StreetKind.Radial, 40, listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.01))),
                        StreetLine("NoPoints", StreetKind.Radial, 40, emptyList()),
                    ),
            )

        val model = map.toCityModel(proj)

        assertEquals(1, model.streetsM.size)
        assertEquals("Keeper", model.streetsM[0].name)
    }

    @Test
    fun `toCityModel - trashFenceM is first ring projected to metres`() {
        val ring =
            listOf(
                LatLon(lon = 0.0, lat = 0.01),
                LatLon(lon = 0.01, lat = 0.0),
                LatLon(lon = 0.0, lat = -0.01),
                LatLon(lon = -0.01, lat = 0.0),
            )
        val map =
            emptyMap(
                trashFence =
                    listOf(
                        PolygonRing(name = "fence", ring = ring),
                        PolygonRing(name = "ignored", ring = listOf(LatLon(0.0, 0.5))),
                    ),
            )

        val model = map.toCityModel(proj)

        assertEquals(ring.size, model.trashFenceM.size)
        // First fence vertex is a pure-north point: projects to (0, +radius).
        val expectedFirst = proj.project(ring[0])
        assertEquals(expectedFirst.eastM, model.trashFenceM[0].eastM, 1e-9)
        assertEquals(expectedFirst.northM, model.trashFenceM[0].northM, 1e-9)
    }

    @Test
    fun `toCityModel - no trash fence leaves trashFenceM empty`() {
        val model = emptyMap().toCityModel(proj)
        assertTrue(model.trashFenceM.isEmpty())
    }
}
