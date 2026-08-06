package org.pureagave.zodiac.control.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.model.PolygonRing
import org.pureagave.zodiac.control.core.ops.DriveTarget
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.telemetry.BeaconHealth
import org.pureagave.zodiac.control.core.telemetry.Odometer

/**
 * [CockpitUiState.activeDriveTarget] is the single destination every guidance
 * surface (heading chevron, ops footer, RADAR blip) steers to, so its
 * precedence — custom > BATH > preset — and the dynamic nearest-toilet
 * resolution are worth pinning.
 */
class CockpitUiStateTest {
    private val spike = GoldenSpike.Y2025

    private fun ringAround(center: LatLon): PolygonRing {
        val d = 0.00005
        return PolygonRing(
            name = null,
            ring =
                listOf(
                    LatLon(lon = center.lon - d, lat = center.lat - d),
                    LatLon(lon = center.lon + d, lat = center.lat - d),
                    LatLon(lon = center.lon + d, lat = center.lat + d),
                    LatLon(lon = center.lon - d, lat = center.lat + d),
                ),
        )
    }

    private fun mapWithToilets(toilets: List<PolygonRing>) =
        PlayaMap(
            year = "2025",
            trashFence = emptyList(),
            streetLines = emptyList(),
            streetOutlines = emptyList(),
            cityBlocks = emptyList(),
            plazas = emptyList(),
            toilets = toilets,
            cpns = emptyList(),
            art = emptyList(),
        )

    @Test
    fun custom_target_overrides_bath_and_preset() {
        val custom = DriveTarget(label = "CUSTOM", location = LatLon(lon = -119.2, lat = 40.79))
        val state = CockpitUiState().copy(customTarget = custom, driveToBath = true, navTarget = NavTarget.MAN)
        assertEquals(custom, state.activeDriveTarget)
    }

    @Test
    fun default_is_the_home_preset() {
        val target = CockpitUiState().activeDriveTarget
        assertEquals(NavTarget.HOME.label, target?.label)
        assertEquals(NavTarget.HOME.location, target?.location)
    }

    @Test
    fun preset_follows_the_selected_nav_target() {
        val target = CockpitUiState().copy(navTarget = NavTarget.TEMPLE).activeDriveTarget
        assertEquals(NavTarget.TEMPLE.label, target?.label)
        assertEquals(NavTarget.TEMPLE.location, target?.location)
    }

    @Test
    fun bath_without_a_fix_is_null() {
        // driveToBath but locationState defaults to Disconnected → no ego → null.
        val state = CockpitUiState().copy(driveToBath = true, playaMap = mapWithToilets(listOf(ringAround(spike))))
        assertNull(state.activeDriveTarget)
    }

    @Test
    fun bath_without_toilets_is_null() {
        val ego = GpsFix(location = spike)
        val state = CockpitUiState().copy(driveToBath = true, locationState = LocationSourceState.Active(ego), playaMap = null)
        assertNull(state.activeDriveTarget)
    }

    @Test
    fun bath_resolves_to_the_nearest_toilet() {
        val near = LatLon(lon = spike.lon + 0.0003, lat = spike.lat + 0.0003)
        val far = LatLon(lon = spike.lon - 0.008, lat = spike.lat - 0.008)
        // Put the far bank first so a correct result can't be an accident of order.
        val map = mapWithToilets(listOf(ringAround(far), ringAround(near)))
        val ego = GpsFix(location = LatLon(lon = spike.lon + 0.00035, lat = spike.lat + 0.00035))
        val state = CockpitUiState().copy(driveToBath = true, locationState = LocationSourceState.Active(ego), playaMap = map)

        val target = state.activeDriveTarget
        assertNotNull(target)
        assertEquals("BATH", target!!.label)
        assertEquals(near.lat, target.location.lat, 1e-4)
        assertEquals(near.lon, target.location.lon, 1e-4)
    }

    // -- ops footer: does the beacon line get drawn at all? --------------------

    @Test
    fun no_beacon_on_the_bus_means_no_beacon_line() {
        // A footer of dashes for readings that will never arrive is worse than
        // no footer, so the second line stays collapsed until something reports.
        assertFalse(CockpitUiState().beaconReadout.any)
    }

    @Test
    fun any_single_beacon_reading_opens_the_line() {
        assertTrue(CockpitUiState().copy(odometer = Odometer(1.0, 2.0)).beaconReadout.any)
        assertTrue(CockpitUiState().copy(beaconHealth = BeaconHealth(90, 1, 8, 60)).beaconReadout.any)
        assertTrue(CockpitUiState().copy(shockAlertG = 3.2).beaconReadout.any)
    }

    @Test
    fun a_shock_alert_alone_opens_the_line_then_closes_it_again() {
        // The VM clears shockAlertG on a timer; with no other beacon data the
        // footer must collapse back rather than leaving an empty second row.
        val jolted = CockpitUiState().copy(shockAlertG = 4.1)
        assertTrue(jolted.beaconReadout.any)
        assertFalse(jolted.copy(shockAlertG = null).beaconReadout.any)
    }
}
