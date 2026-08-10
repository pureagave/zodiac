package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.StreetKind
import org.pureagave.zodiac.control.core.navigation.bearingFromOriginTo
import org.pureagave.zodiac.control.core.navigation.pointToPolylineDistance
import org.pureagave.zodiac.control.data.playa.GeoJsonParser
import java.io.File
import kotlin.math.hypot

/**
 * Every coordinate here is checked against something *outside* the source
 * file that declares it — the bundled Innovate GIS assets, or BRC's published
 * city geometry. Restating the literal from [NavTarget] would only prove the
 * compiler can copy a number (which is exactly what the previous version of
 * `temple_is_the_exact_2026_coordinate` did: it re-typed the constant, so a
 * wrong Temple would have shipped green).
 */
class NavTargetTest {
    @Test
    fun there_are_exactly_three_targets_with_expected_labels() {
        assertEquals(3, NavTarget.entries.size)
        assertEquals(listOf("HOME", "MAN", "TEMPLE"), NavTarget.entries.map { it.label })
    }

    @Test
    fun man_is_the_bundled_gis_the_man_cpn() {
        // The Golden Spike origin the whole projection hangs off. Independent
        // source: the "The Man" CPN in the shipped GIS for the active year.
        assertEquals(gisCpn("The Man"), NavTarget.MAN.location)
        assertEquals("MAN target must be the active origin", GoldenSpike.ACTIVE, NavTarget.MAN.location)
    }

    @Test
    fun temple_is_the_bundled_gis_the_temple_cpn() {
        // Independent source #1: the shipped dataset, not the Kotlin literal.
        // Re-run the year migration and this catches a stale Temple instantly.
        assertEquals(gisCpn("The Temple"), NavTarget.TEMPLE.location)
    }

    @Test
    fun temple_sits_2500_ft_up_the_12_oclock_axis_from_the_man() {
        // Independent source #2 — BRC's published city plan: the Temple stands
        // on the 12:00 promenade, 2,500 ft (762 m) out from the Man. Holds even
        // if the GIS file itself were swapped for a bad one.
        val p = PlayaProjection(GoldenSpike.ACTIVE).project(NavTarget.TEMPLE.location)

        assertEquals("range from the Man", TEMPLE_RANGE_M, hypot(p.eastM, p.northM), RANGE_TOLERANCE_M)
        assertEquals("bearing from the Man", BRC_AXIS_BEARING_DEG, bearingFromOriginTo(p), BEARING_TOLERANCE_DEG)
    }

    @Test
    fun home_is_the_camp() {
        assertEquals(Camp.GALACTIC_RELAY, NavTarget.HOME.location)
    }

    @Test
    fun home_lands_on_the_two_bundled_gis_streets_of_its_address() {
        // Independent source: the camp address. Heiau is the 2026 H ring, so
        // "Heiau & 2:15" must project onto both the bundled H arc and the
        // bundled 2:15 radial — a wrong camp constant misses one or both.
        val projection = PlayaProjection(GoldenSpike.ACTIVE)
        val p = projection.project(NavTarget.HOME.location)
        val offArc = streetPoints("H", StreetKind.Arc).minOf { pointToPolylineDistance(p, it) }
        val offRadial = streetPoints("2:15", StreetKind.Radial).minOf { pointToPolylineDistance(p, it) }

        assertTrue("HOME is ${"%.1f".format(offArc)} m off the H arc", offArc < CORNER_TOLERANCE_M)
        assertTrue("HOME is ${"%.1f".format(offRadial)} m off the 2:15 radial", offRadial < CORNER_TOLERANCE_M)
    }

    @Test
    fun camp_address_is_the_human_facing_brc_address() {
        assertEquals("Heiau & 2:15", Camp.GALACTIC_RELAY_ADDRESS)
    }

    private companion object {
        /** 2,500 ft — the published Man→Temple spacing on the 12:00 promenade. */
        const val TEMPLE_RANGE_M = 2_500 * 0.3048

        /** The 12:00 axis points NE; BM has surveyed it to 45° true for years. */
        const val BRC_AXIS_BEARING_DEG = 45.0

        /** The GIS pins the Temple to ~2,499 ft / 44.9°; leave room for survey slop, not for a wrong city. */
        const val RANGE_TOLERANCE_M = 10.0
        const val BEARING_TOLERANCE_DEG = 0.5

        /** Half a street width — "the right corner" at BRC block scale. */
        const val CORNER_TOLERANCE_M = 10.0

        private fun gisCpn(name: String) =
            GeoJsonParser
                .parsePoints(assetFile("cpns.geojson").readText(), "NAME", "TYPE")
                .single { it.name == name }
                .location

        /** Polylines for a street, one list per GIS feature (radials don't join). */
        private fun streetPoints(
            name: String,
            kind: StreetKind,
        ): List<List<PlayaPoint>> {
            val projection = PlayaProjection(GoldenSpike.ACTIVE)
            val lines =
                GeoJsonParser
                    .parseStreetLines(assetFile("street_lines.geojson").readText())
                    .filter { it.kind == kind && it.name.equals(name, ignoreCase = true) }
            check(lines.isNotEmpty()) { "no bundled $kind named $name" }
            return lines.map { line -> line.points.map(projection::project) }
        }

        /**
         * Unit tests run with the module dir as CWD under AGP, but don't rely
         * on it — walk up until the assets tree appears.
         */
        private fun assetFile(name: String): File {
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
