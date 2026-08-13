package org.pureagave.zodiac.control.ui.passenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.ui.state.CockpitUiState

/**
 * [vehicleStoppedForArtHold] gates whether the passenger ART card holds and
 * shows its full description. It must read [CockpitUiState.effectiveSpeedKph]
 * — the single-owner speed — not the fix directly, because a bare GGA epoch
 * (no speed field) used to fall through to the debug `speedKph` chip, which
 * is 0 on a real NET drive.
 */
class ParkedGateTest {
    private val here = LatLon(lon = -119.203, lat = 40.786)

    @Test
    fun moving_on_a_gga_epoch_is_not_parked() {
        // A held RMC speed (gpsSpeedKph) with a bare GGA fix on the current
        // epoch (fix.speedKph == null) is exactly the alternate-epoch shape
        // that flipped the flag true while the vehicle was actually moving.
        val state =
            CockpitUiState().copy(
                speedKph = 0,
                gpsSpeedKph = 12.0,
                locationState = LocationSourceState.Active(GpsFix(location = here, speedKph = null)),
            )

        assertFalse(vehicleStoppedForArtHold(state))
    }

    @Test
    fun an_actually_stopped_vehicle_is_parked() {
        val state = CockpitUiState().copy(gpsSpeedKph = 0.0)

        assertTrue(vehicleStoppedForArtHold(state))
    }

    @Test
    fun a_vehicle_reporting_speed_on_the_fix_is_not_parked() {
        val state =
            CockpitUiState().copy(
                gpsSpeedKph = 5.0,
                locationState = LocationSourceState.Active(GpsFix(location = here, speedKph = 5.0)),
            )

        assertFalse(vehicleStoppedForArtHold(state))
    }
}
