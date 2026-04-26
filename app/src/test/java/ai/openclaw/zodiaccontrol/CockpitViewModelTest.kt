package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.MapLoadResult
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.model.Telemetry
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.FakeVehicleGateway
import ai.openclaw.zodiaccontrol.data.TelemetryRepository
import ai.openclaw.zodiaccontrol.data.playa.PlayaMapRepository
import ai.openclaw.zodiaccontrol.data.sensor.LocationSourceRegistry
import ai.openclaw.zodiaccontrol.data.sensor.RoutedLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.StubLocationSource
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun setHeading_sendsVehicleCommand() =
        runTest {
            val gateway = FakeVehicleGateway()
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = gateway,
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                vm.setHeading(123)
                advanceUntilIdle()

                assertTrue(gateway.history().contains(VehicleCommand.SetHeading(123)))
            } finally {
                store.clear()
            }
        }

    @Test
    fun setMapMode_flipsUiState() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                assertEquals(MapMode.TOP, vm.uiState.value.mapMode)

                vm.setMapMode(MapMode.TILT)
                advanceUntilIdle()
                assertEquals(MapMode.TILT, vm.uiState.value.mapMode)

                vm.setMapMode(MapMode.TOP)
                advanceUntilIdle()
                assertEquals(MapMode.TOP, vm.uiState.value.mapMode)
            } finally {
                store.clear()
            }
        }

    @Test
    fun panBy_clampsToMaxPanMeters() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                val cap = CockpitUiState.MAX_PAN_M

                // Far past +cap east, far past -cap north — both should clamp.
                vm.panBy(cap * 10, -cap * 10)
                advanceUntilIdle()
                assertEquals(cap, vm.uiState.value.panEastM, 0.0)
                assertEquals(-cap, vm.uiState.value.panNorthM, 0.0)

                // Within the cap — accumulates normally.
                vm.recenterPan()
                vm.panBy(100.0, -50.0)
                advanceUntilIdle()
                assertEquals(100.0, vm.uiState.value.panEastM, 0.0)
                assertEquals(-50.0, vm.uiState.value.panNorthM, 0.0)
            } finally {
                store.clear()
            }
        }

    @Test
    fun restartLocationSource_stops_then_starts_active_source() =
        runTest {
            val stub = StubLocationSource(LocationSourceType.FAKE)
            val routed =
                RoutedLocationSource(
                    registry = LocationSourceRegistry(sources = listOf(stub)),
                    scope = this.backgroundScope,
                    initialType = LocationSourceType.FAKE,
                )
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = routed,
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()
                // VM init already issued one start.
                assertEquals(1, stub.startCalls)
                assertEquals(0, stub.stopCalls)

                vm.restartLocationSource()
                advanceUntilIdle()

                assertEquals(2, stub.startCalls)
                assertEquals(1, stub.stopCalls)
            } finally {
                store.clear()
            }
        }

    @Test
    fun setTiltDeg_clampsToRange() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                vm.setTiltDeg(55)
                advanceUntilIdle()
                assertEquals(55, vm.uiState.value.tiltDeg)

                // Below MIN_TILT_DEG (=0) clamps to 0.
                vm.setTiltDeg(-30)
                advanceUntilIdle()
                assertEquals(0, vm.uiState.value.tiltDeg)

                // Above MAX_TILT_DEG (=80) clamps to 80.
                vm.setTiltDeg(120)
                advanceUntilIdle()
                assertEquals(80, vm.uiState.value.tiltDeg)
            } finally {
                store.clear()
            }
        }
}

private object NoOpPlayaMapRepository : PlayaMapRepository {
    override val loadResult: StateFlow<MapLoadResult> = MutableStateFlow(MapLoadResult.Loading).asStateFlow()
    override val map: Flow<PlayaMap> = emptyFlow()

    override suspend fun load() = Unit
}

private fun newFakeRoutedLocationSource(scope: CoroutineScope): RoutedLocationSource {
    val registry = LocationSourceRegistry(sources = listOf(StubLocationSource(LocationSourceType.FAKE)))
    return RoutedLocationSource(
        registry = registry,
        scope = scope,
        initialType = LocationSourceType.FAKE,
    )
}

private class StaticTelemetryRepo : TelemetryRepository {
    override fun stream(): Flow<Telemetry> =
        flowOf(
            Telemetry(
                headingDeg = 42,
                speedKph = 30,
                thermalC = 60,
                linkStable = true,
                mode = CockpitMode.DIAGNOSTIC,
            ),
        )
}
