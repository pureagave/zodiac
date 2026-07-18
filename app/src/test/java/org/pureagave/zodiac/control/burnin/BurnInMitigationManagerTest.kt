package org.pureagave.zodiac.control.burnin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.FakeVehicleGateway
import org.pureagave.zodiac.control.data.sensor.StubLocationSource

@OptIn(ExperimentalCoroutinesApi::class)
class BurnInMitigationManagerTest {
    // --- Pure phase mapping ---------------------------------------------------

    @Test
    fun phaseForIdle_maps_each_band() {
        val cfg = BurnInConfig() // 300 / 1800 / 3600 s
        assertEquals(BurnInPhase.ACTIVE, BurnInMitigationManager.phaseForIdle(0, cfg))
        assertEquals(BurnInPhase.ACTIVE, BurnInMitigationManager.phaseForIdle(299_999, cfg))
        assertEquals(BurnInPhase.DIM, BurnInMitigationManager.phaseForIdle(300_000, cfg))
        assertEquals(BurnInPhase.DIM, BurnInMitigationManager.phaseForIdle(1_799_999, cfg))
        assertEquals(BurnInPhase.DEEP_IDLE, BurnInMitigationManager.phaseForIdle(1_800_000, cfg))
        assertEquals(BurnInPhase.DEEP_IDLE, BurnInMitigationManager.phaseForIdle(3_599_999, cfg))
        assertEquals(BurnInPhase.SLEEP, BurnInMitigationManager.phaseForIdle(3_600_000, cfg))
    }

    @Test
    fun distanceMeters_is_roughly_correct_near_brc() {
        // ~0.001 deg latitude ≈ 111.32 m at the equator scaling we use.
        val d = BurnInMitigationManager.distanceMeters(LatLon(-119.2, 40.78), LatLon(-119.2, 40.781))
        assertTrue("expected ~111 m, got $d", d in 110.0..113.0)
        assertEquals(0.0, BurnInMitigationManager.distanceMeters(BRC, BRC), 1e-9)
    }

    // --- Config coercion ------------------------------------------------------

    @Test
    fun coerced_forces_timeouts_strictly_increasing() {
        val inverted = BurnInConfig(dimTimeoutSec = 5_000, deepIdleTimeoutSec = 1_000, sleepTimeoutSec = 100)
        val c = inverted.coerced()
        assertTrue(c.dimTimeoutSec < c.deepIdleTimeoutSec)
        assertTrue(c.deepIdleTimeoutSec < c.sleepTimeoutSec)
    }

    @Test
    fun coerced_clamps_amplitudes_and_alphas() {
        val wild = BurnInConfig(pixelShiftAmplitudePx = 999, breatheAmplitude = 5f, sleepBacklight = -1f)
        val c = wild.coerced()
        assertEquals(BurnInConfig.MAX_SHIFT_AMPLITUDE_PX, c.pixelShiftAmplitudePx)
        assertEquals(BurnInConfig.MAX_BREATHE_AMPLITUDE, c.breatheAmplitude)
        assertEquals(0f, c.sleepBacklight)
    }

    // --- Manager wiring -------------------------------------------------------

    @Test
    fun starts_active() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, _, _) = newManager(this)
            assertEquals(BurnInPhase.ACTIVE, manager.phase.value)
        }

    @Test
    fun idle_escalates_through_dim_deep_and_sleep() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, _, _) = newManager(this)

            advanceTimeBy(305_000)
            runCurrent()
            assertEquals(BurnInPhase.DIM, manager.phase.value)

            advanceTimeBy(1_500_000) // → ~1.8M total
            runCurrent()
            assertEquals(BurnInPhase.DEEP_IDLE, manager.phase.value)

            advanceTimeBy(1_800_000) // → ~3.6M total
            runCurrent()
            assertEquals(BurnInPhase.SLEEP, manager.phase.value)
        }

    @Test
    fun touch_wakes_from_sleep() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, _, _) = newManager(this)
            advanceTimeBy(3_605_000)
            runCurrent()
            assertEquals(BurnInPhase.SLEEP, manager.phase.value)

            manager.onUserInteraction()
            assertEquals(BurnInPhase.ACTIVE, manager.phase.value)
        }

    @Test
    fun gps_movement_wakes_but_a_parked_fix_does_not() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, gps, _) = newManager(this)

            // Establish a stationary reference fix while still ACTIVE.
            gps.emit(LocationSourceState.Active(GpsFix(BRC, speedKph = 0.0)))

            advanceTimeBy(305_000)
            runCurrent()
            assertEquals(BurnInPhase.DIM, manager.phase.value)

            // Same spot, no speed → not movement → stays DIM.
            gps.emit(LocationSourceState.Active(GpsFix(BRC, speedKph = 0.0)))
            assertEquals(BurnInPhase.DIM, manager.phase.value)

            // Now actually moving → wakes.
            gps.emit(LocationSourceState.Active(GpsFix(BRC, speedKph = 12.0)))
            assertEquals(BurnInPhase.ACTIVE, manager.phase.value)
        }

    @Test
    fun slow_drift_beyond_threshold_counts_as_movement() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, gps, _) = newManager(this)
            gps.emit(LocationSourceState.Active(GpsFix(BRC, speedKph = 0.0))) // reference

            advanceTimeBy(305_000)
            runCurrent()
            assertEquals(BurnInPhase.DIM, manager.phase.value)

            // ~111 m north at 0 speed — below speed threshold but well past 3 m.
            gps.emit(LocationSourceState.Active(GpsFix(LatLon(BRC.lon, BRC.lat + 0.001), speedKph = 0.0)))
            assertEquals(BurnInPhase.ACTIVE, manager.phase.value)
        }

    @Test
    fun vehicle_link_phase_change_wakes() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, _, gateway) = newManager(this)

            advanceTimeBy(305_000)
            runCurrent()
            assertEquals(BurnInPhase.DIM, manager.phase.value)

            gateway.connect() // DISCONNECTED → CONNECTED
            assertEquals(BurnInPhase.ACTIVE, manager.phase.value)
        }

    @Test
    fun enter_park_jumps_to_deep_idle_immediately() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, _, _) = newManager(this)
            manager.enterPark()
            assertEquals(BurnInPhase.DEEP_IDLE, manager.phase.value)
        }

    @Test
    fun park_then_idles_on_to_sleep() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, _, _) = newManager(this)
            manager.enterPark()
            assertEquals(BurnInPhase.DEEP_IDLE, manager.phase.value)

            // Remaining deep→sleep gap is 3600−1800 = 1800 s; advance past it.
            advanceTimeBy(1_805_000)
            runCurrent()
            assertEquals(BurnInPhase.SLEEP, manager.phase.value)
        }

    @Test
    fun update_config_is_coerced() =
        runTest(UnconfinedTestDispatcher()) {
            val (manager, _, _) = newManager(this)
            manager.updateConfig(BurnInConfig(pixelShiftAmplitudePx = 999))
            assertEquals(BurnInConfig.MAX_SHIFT_AMPLITUDE_PX, manager.config.value.pixelShiftAmplitudePx)
        }

    private data class Harness(
        val manager: BurnInMitigationManager,
        val gps: StubLocationSource,
        val gateway: FakeVehicleGateway,
    )

    private fun newManager(scope: TestScope): Harness {
        val gps = StubLocationSource(LocationSourceType.FAKE)
        val gateway = FakeVehicleGateway()
        val manager =
            BurnInMitigationManager(
                locationState = gps.state,
                connectionState = gateway.connectionState,
                scope = scope.backgroundScope,
                clock = { scope.testScheduler.currentTime },
                tickMillis = 1_000,
            )
        return Harness(manager, gps, gateway)
    }

    private companion object {
        val BRC = LatLon(lon = -119.203, lat = 40.786)
    }
}
