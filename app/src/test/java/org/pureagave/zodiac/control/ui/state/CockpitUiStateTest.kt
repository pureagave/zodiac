package org.pureagave.zodiac.control.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.model.PolygonRing
import org.pureagave.zodiac.control.core.ops.DriveTarget
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState

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
}
