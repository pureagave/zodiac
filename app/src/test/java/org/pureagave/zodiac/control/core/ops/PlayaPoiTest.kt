package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import kotlin.math.atan2
import kotlin.math.hypot

class PlayaPoiTest {
    private val projection = PlayaProjection(GoldenSpike.Y2025)

    private fun bearingOf(
        east: Double,
        north: Double,
    ): Double = ((Math.toDegrees(atan2(east, north)) % 360.0) + 360.0) % 360.0

    @Test
    fun the_mans_own_gps_projects_to_the_origin() {
        val p = artPoint(GoldenSpike.Y2025.lat, GoldenSpike.Y2025.lon, projection)
        assertEquals(0.0, hypot(p.eastM, p.northM), 1.0)
    }

    @Test
    fun temple_gps_lands_on_the_12_oclock_axis() {
        // 2025 "Temple of the Deep" GPS — ~762 m out on the 12:00 radial (45° true).
        val p = artPoint(40.791799, -119.196602, projection)
        assertEquals(762.0, hypot(p.eastM, p.northM), 25.0)
        assertEquals(45.0, bearingOf(p.eastM, p.northM), 3.0)
    }

    @Test
    fun camp_clock_and_street_project_to_the_right_ring_and_bearing() {
        // "2:00 & E": 2:00 → 105° true (axis 45° + 60°); E ring ≈ 1237 m.
        val p = campPoint("2:00", "E")!!
        assertEquals(1237.0, hypot(p.eastM, p.northM), 1.0)
        assertEquals(105.0, bearingOf(p.eastM, p.northM), 0.5)
    }

    @Test
    fun camp_address_places_the_same_whichever_field_holds_the_clock() {
        // The 2026 feed varies the order: "G & 9:15" carries the letter in
        // `frontage`, "4:30 & D" the clock. Both must land on the same corner as
        // the canonical (clock, letter) call — this is the bug that silently
        // dropped 672 of 1190 camps when frontage was assumed to be the clock.
        assertEquals(campPoint("2:00", "E"), campPoint("E", "2:00"))
        assertEquals(campPoint("9:15", "G"), campPoint("G", "9:15"))
    }

    @Test
    fun real_feed_camps_in_both_orders_place_on_their_rings() {
        // Snuggles: frontage="G", intersection="9:15" (letter first).
        val snuggles = campPoint("G", "9:15")!!
        assertEquals(1470.0, hypot(snuggles.eastM, snuggles.northM), 1.0) // G ring
        // The Airship: frontage="4:30", intersection="D" (clock first).
        val airship = campPoint("4:30", "D")!!
        assertEquals(1150.0, hypot(airship.eastM, airship.northM), 1.0) // D ring
    }

    @Test
    fun esplanade_places_and_tolerates_the_esp_abbreviation() {
        val spelled = campPoint("3:00", "Esplanade")!!
        assertEquals(761.5, hypot(spelled.eastM, spelled.northM), 1.0)
        assertEquals(spelled, campPoint("ESP", "3:00")) // reversed order + abbreviation
    }

    @Test
    fun two_clocks_or_two_letters_do_not_false_place() {
        assertNull(campPoint("3:00", "9:00")) // both clocks — no ring letter
        assertNull(campPoint("E", "G")) // both letters — no clock
    }

    @Test
    fun camp_with_unplaceable_address_is_null() {
        assertNull(campPoint("2:00", "Center Camp Plaza"))
        assertNull(campPoint(null, "E"))
        assertNull(campPoint("2:00", null))
    }

    @Test
    fun hour_only_frontage_defaults_minutes_to_zero() {
        assertEquals(campPoint("2:00", "E"), campPoint("2", "E"))
    }

    @Test
    fun zero_hour_frontage_maps_to_twelve_oclock() {
        // "0:00" → 12:00, which sits on the BRC axis (45° true).
        val p = campPoint("0:00", "D")!!
        assertEquals(45.0, bearingOf(p.eastM, p.northM), 0.5)
        assertEquals(campPoint("12:00", "D"), campPoint("0:00", "D"))
    }

    @Test
    fun out_of_range_clock_is_unplaceable() {
        assertNull(campPoint("2:75", "E")) // minute > 59
        assertNull(campPoint("13:00", "E")) // hour > 12
        assertNull(campPoint("-1:00", "E")) // negative hour
    }

    @Test
    fun frontage_and_street_tolerate_whitespace_and_case() {
        assertEquals(campPoint("2:00", "E"), campPoint(" 2:00 ", " e "))
    }

    @Test
    fun custom_axis_bearing_rotates_the_placement() {
        val default = campPoint("3:00", "E")!!
        val rotated = campPoint("3:00", "E", axisBearingDeg = 0.0)!!
        assertNotEquals(default, rotated)
    }
}
