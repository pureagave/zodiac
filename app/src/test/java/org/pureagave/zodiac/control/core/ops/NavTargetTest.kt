package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon

class NavTargetTest {
    @Test
    fun there_are_exactly_three_targets_with_expected_labels() {
        assertEquals(3, NavTarget.entries.size)
        assertEquals(listOf("HOME", "MAN", "TEMPLE"), NavTarget.entries.map { it.label })
    }

    @Test
    fun home_is_the_camp_and_man_is_the_active_golden_spike() {
        assertEquals(Camp.GALACTIC_RELAY, NavTarget.HOME.location)
        assertEquals(GoldenSpike.ACTIVE, NavTarget.MAN.location)
    }

    @Test
    fun temple_is_the_exact_2026_coordinate() {
        // Read straight from the source: NavTarget.kt pins the 2026 "The Temple" CPN.
        assertEquals(LatLon(lon = -119.201499636, lat = 40.78809942300006), NavTarget.TEMPLE.location)
    }

    @Test
    fun camp_address_is_the_human_facing_brc_address() {
        assertEquals("Heiau & 2:15", Camp.GALACTIC_RELAY_ADDRESS)
    }
}
