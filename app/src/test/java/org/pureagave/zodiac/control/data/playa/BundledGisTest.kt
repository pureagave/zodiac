package org.pureagave.zodiac.control.data.playa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.model.StreetKind
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.pointToPolylineDistance
import org.pureagave.zodiac.control.core.navigation.toCityModel
import org.pureagave.zodiac.control.core.ops.StreetRingRadiiM
import org.pureagave.zodiac.control.core.ops.addressTarget
import java.io.File
import kotlin.math.hypot

/**
 * Ground-truth tests against the **bundled** BRC GIS assets rather than
 * hand-written fixtures. The constants the nav stack rides on
 * ([StreetRingRadiiM]) and the parser's property mapping are both assumptions
 * about a dataset that BM re-publishes every year with no compatibility
 * promise — 2026 renamed `type`→`kind`/`source` and `width`→`width_ft`, which
 * silently untagged every street. These tests fail when that happens again.
 */
class BundledGisTest {
    // --- the parser must understand the year we actually ship ------------

    @Test
    fun bundled_active_year_streets_are_kind_tagged() {
        val streets = GeoJsonParser.parseStreetLines(activeStreetLinesJson())

        val radials = streets.count { it.kind == StreetKind.Radial }
        val arcs = streets.count { it.kind == StreetKind.Arc }
        // A whole city: hundreds of each. An untagged parse yields zero, and
        // PlayaCityModel drops every kind-less street -> no routes, no cues.
        assertTrue("radial streets tagged: $radials", radials > MIN_TAGGED)
        assertTrue("arc streets tagged: $arcs", arcs > MIN_TAGGED)
        assertEquals("every street should be named", 0, streets.count { it.name.isNullOrEmpty() })
    }

    @Test
    fun bundled_active_year_streets_carry_widths() {
        val streets = GeoJsonParser.parseStreetLines(activeStreetLinesJson())

        val withWidth = streets.count { it.widthFeet != null }
        assertEquals("every street carries a width", streets.size, withWidth)
        assertTrue("widths look like feet", streets.all { (it.widthFeet ?: 0) in MIN_WIDTH_FT..MAX_WIDTH_FT })
    }

    // --- the ring radii the address keypad projects onto ------------------

    @Test
    fun street_ring_radii_match_the_bundled_gis() {
        val rings = ringPointsByName()

        StreetRingRadiiM.forEach { (name, coded) ->
            val points = rings[name] ?: error("no bundled GIS ring for $name")
            val measured = points.map { hypot(it.eastM, it.northM) }.average()
            assertEquals(
                "$name radius: coded $coded m vs GIS ${"%.1f".format(measured)} m",
                measured,
                coded,
                RADIUS_TOLERANCE_M,
            )
        }
    }

    // --- the end-to-end address gate: our camp -----------------------------

    @Test
    fun home_camp_address_lands_on_both_of_its_streets() {
        // Galactic Relay is at 2:15 & H. The keypad resolves that through
        // addressTarget(); the answer has to sit on the real polylines for
        // both streets, not merely near the idealised circle.
        val projection = PlayaProjection(GoldenSpike.ACTIVE)
        val target = addressTarget(ClockTime(hours = 2, minutes = 15), "H", projection)
        checkNotNull(target) { "2:15 & H must resolve" }
        val p = projection.project(target.location)

        val ring = ringPointsByName()["H"] ?: error("no H ring")
        val radial = radialSegments("2:15")
        assertTrue("2:15 radial present in bundled GIS", radial.isNotEmpty())

        val offRing = pointToPolylineDistance(p, ring)
        val offRadial = radial.minOf { pointToPolylineDistance(p, it) }
        assertTrue("target is ${"%.1f".format(offRing)} m off the H ring", offRing < ADDRESS_TOLERANCE_M)
        assertTrue("target is ${"%.1f".format(offRadial)} m off the 2:15 radial", offRadial < ADDRESS_TOLERANCE_M)
    }

    @Test
    fun city_model_built_from_the_bundled_map_can_actually_route() {
        // The consumer that matters: PlayaCityModel drops every kind-less
        // street, so an unrecognised schema leaves the navigator with no arcs
        // to cross, no radials to route along, and an infinite city radius —
        // all while the map still draws perfectly.
        val map = bundledMap()
        val city = map.toCityModel(PlayaProjection(GoldenSpike.ACTIVE))

        assertTrue("streets in city model: ${city.streetsM.size}", city.streetsM.size > MIN_TAGGED)
        assertEquals(
            "arcs, inner to outer",
            listOf("ESP", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"),
            city.arcsInnerToOuter,
        )
        assertTrue("city outer radius ${city.cityOuterRadiusM} m", city.cityOuterRadiusM < MAX_CITY_RADIUS_M)
        assertTrue(
            "2:15 radial retained for routing",
            city.streetsM.any { it.name == "2:15" && it.kind == StreetKind.Radial },
        )
    }

    @Test
    fun bundled_plazas_are_named_so_their_labels_render() {
        // Plaza names are the only polygon names that reach the screen, via
        // PlayaMap.plazaLabelSeeds — which drops any plaza without one.
        val plazas = GeoJsonParser.parsePolygons(assetFile("plazas.geojson").readText(), "Name", "name")

        assertTrue("plazas parsed: ${plazas.size}", plazas.size >= MIN_PLAZAS)
        assertEquals("every plaza named", 0, plazas.count { it.name.isNullOrEmpty() })
        assertTrue("Center Camp Plaza present", plazas.any { it.name == "Center Camp Plaza" })
    }

    // --- helpers ----------------------------------------------------------

    private fun bundledMap(): PlayaMap =
        PlayaMap(
            year = GoldenSpike.ACTIVE_YEAR.toString(),
            trashFence = GeoJsonParser.parsePolygons(assetFile("trash_fence.geojson").readText()),
            streetLines = GeoJsonParser.parseStreetLines(activeStreetLinesJson()),
            streetOutlines = emptyList(),
            cityBlocks = emptyList(),
            plazas = emptyList(),
            toilets = emptyList(),
            cpns = emptyList(),
            art = emptyList(),
        )

    private fun activeStreetLinesJson(): String = assetFile("street_lines.geojson").readText()

    /** Ring points keyed by [StreetRingRadiiM]'s naming (`ESPLANADE`, `A`…). */
    private fun ringPointsByName(): Map<String, List<PlayaPoint>> {
        val projection = PlayaProjection(GoldenSpike.ACTIVE)
        return GeoJsonParser.parseStreetLines(activeStreetLinesJson())
            .filter { it.kind == StreetKind.Arc && !it.name.isNullOrEmpty() }
            .groupBy { ringKey(it.name!!) }
            .mapValues { (_, lines) -> lines.flatMap { it.points }.map(projection::project) }
    }

    /** Each `2:15`-style radial kept as separate polylines (they don't join). */
    private fun radialSegments(name: String): List<List<PlayaPoint>> {
        val projection = PlayaProjection(GoldenSpike.ACTIVE)
        return GeoJsonParser.parseStreetLines(activeStreetLinesJson())
            .filter { it.kind == StreetKind.Radial && it.name == name }
            .map { line -> line.points.map(projection::project) }
    }

    private companion object {
        const val MIN_TAGGED = 100
        const val MIN_PLAZAS = 10
        const val MIN_WIDTH_FT = 10
        const val MAX_WIDTH_FT = 200

        /** Ring polylines wobble ~±1.5 m about their mean; the mean itself should land. */
        const val RADIUS_TOLERANCE_M = 1.5

        /** Half a street width — "the right corner" at BRC block scale. */
        const val ADDRESS_TOLERANCE_M = 10.0

        /** K is the outermost ring at ~1753 m; an untagged parse yields MAX_VALUE. */
        const val MAX_CITY_RADIUS_M = 2_000.0

        /** The GIS abbreviates the Esplanade; [StreetRingRadiiM] spells it. */
        fun ringKey(name: String): String = if (name.equals("ESP", ignoreCase = true)) "ESPLANADE" else name.uppercase()

        /**
         * Unit tests run with the module dir as CWD under AGP, but don't rely
         * on it — walk up until the assets tree appears.
         */
        fun assetFile(name: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, "app/src/main/assets/brc/${GoldenSpike.ACTIVE_YEAR}/$name")
                if (candidate.isFile) return candidate
                val here = File(dir, "src/main/assets/brc/${GoldenSpike.ACTIVE_YEAR}/$name")
                if (here.isFile) return here
                dir = dir.parentFile
            }
            error("bundled GIS asset $name not found for ${GoldenSpike.ACTIVE_YEAR}")
        }
    }
}
