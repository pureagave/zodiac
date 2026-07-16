package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.burnin.BurnInConfig
import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.model.AUTO_RECENTER_MS
import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.FollowMode
import ai.openclaw.zodiaccontrol.core.model.MapLoadResult
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.model.Telemetry
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.FakeVehicleGateway
import ai.openclaw.zodiaccontrol.data.TelemetryRepository
import ai.openclaw.zodiaccontrol.data.playa.PlayaMapRepository
import ai.openclaw.zodiaccontrol.data.prefs.CockpitPreferences
import ai.openclaw.zodiaccontrol.data.prefs.CockpitPrefsSnapshot
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
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
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
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
    fun panBy_clampsToMaxOffsetAndSwitchesToFreeMode() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle() // let VM init settle
                val cap = CockpitUiState.MAX_CAMERA_OFFSET_M

                // panBy clamps to ±cap and immediately switches to FREE
                // (the state update is synchronous; no scheduler advance
                // here, otherwise the 60 s auto-recenter timer would fire
                // and put us back in TRACK_UP).
                vm.panBy(cap * 10, -cap * 10)
                val parked = vm.uiState.value.cameraOverride
                assertEquals(ai.openclaw.zodiaccontrol.core.model.FollowMode.FREE, vm.uiState.value.followMode)
                assertEquals(cap, parked!!.eastM, 0.0)
                assertEquals(-cap, parked.northM, 0.0)

                // Recenter clears the override and goes back to TRACK_UP.
                vm.recenterPan()
                assertEquals(null, vm.uiState.value.cameraOverride)
                assertEquals(ai.openclaw.zodiaccontrol.core.model.FollowMode.TRACK_UP, vm.uiState.value.followMode)
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
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
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
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
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

    @Test
    fun setSpeed_clampsToRange() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                // Above MAX_SPEED_KPH (=160) clamps to 160.
                vm.setSpeed(161)
                advanceUntilIdle()
                assertEquals(CockpitUiState.MAX_SPEED_KPH, vm.uiState.value.speedKph)

                // Below MIN_SPEED_KPH (=0) clamps to 0.
                vm.setSpeed(-1)
                advanceUntilIdle()
                assertEquals(CockpitUiState.MIN_SPEED_KPH, vm.uiState.value.speedKph)

                // In-range value passes through.
                vm.setSpeed(80)
                advanceUntilIdle()
                assertEquals(80, vm.uiState.value.speedKph)
            } finally {
                store.clear()
            }
        }

    @Test
    fun setHeading_clampsToRange() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                // 360 wraps past MAX_HEADING_DEG (=359) -> clamps to 359.
                vm.setHeading(360)
                advanceUntilIdle()
                assertEquals(CockpitUiState.MAX_HEADING_DEG, vm.uiState.value.headingDeg)

                // Below MIN_HEADING_DEG (=0) clamps to 0.
                vm.setHeading(-1)
                advanceUntilIdle()
                assertEquals(CockpitUiState.MIN_HEADING_DEG, vm.uiState.value.headingDeg)

                // In-range value passes through.
                vm.setHeading(123)
                advanceUntilIdle()
                assertEquals(123, vm.uiState.value.headingDeg)
            } finally {
                store.clear()
            }
        }

    @Test
    fun nudgeViewRotation_zeroDelta_isNoOp() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle() // let VM init settle

                val rotationBefore = vm.uiState.value.viewRotationDeg
                val modeBefore = vm.uiState.value.followMode

                // Zero delta returns early: neither rotation nor follow mode moves,
                // and no auto-recenter timer is armed. Read state synchronously.
                vm.nudgeViewRotation(0f)
                assertEquals(rotationBefore, vm.uiState.value.viewRotationDeg, 0.0)
                assertEquals(modeBefore, vm.uiState.value.followMode)
            } finally {
                store.clear()
            }
        }

    @Test
    fun nudgeViewRotation_normalizesIntoRange() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle() // let VM init settle

                // First nudge parks rotation near the top of the range and switches to
                // FREE (in FREE the GPS collector no longer rewrites viewRotationDeg, so
                // the next nudge accumulates deterministically). Do NOT advance the
                // scheduler after a nudge or the 60 s auto-recenter timer would fire.
                vm.nudgeViewRotation(350f)
                assertEquals(FollowMode.FREE, vm.uiState.value.followMode)

                // 350 + 20 = 370 raw; floored modulo normalizes to 10, staying in [0,360).
                vm.nudgeViewRotation(20f)
                val rotation = vm.uiState.value.viewRotationDeg
                assertEquals(10.0, rotation, 1e-3)
                assertTrue("expected $rotation in [0, 360)", rotation >= 0.0 && rotation < 360.0)
            } finally {
                store.clear()
            }
        }

    @Test
    fun sendFailure_setsCommandError_thenSuccessClearsIt() =
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
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]

                // A throwing send surfaces as a non-null commandError.
                gateway.failSend = true
                vm.setHeading(90)
                advanceUntilIdle()
                assertNotNull(vm.uiState.value.commandError)

                // A subsequent successful send clears the prior error.
                gateway.failSend = false
                vm.setHeading(90)
                advanceUntilIdle()
                assertNull(vm.uiState.value.commandError)
            } finally {
                store.clear()
            }
        }

    @Test
    fun cycleConcept_advancesThroughAllFourAndPersistsEach() =
        runTest {
            val prefs = RecordingCockpitPreferences()
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = prefs,
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()

                // Default concept is RADAR; the cycle is RADAR -> MAP -> DRIVER -> RADAR.
                assertEquals(CockpitConcept.RADAR, vm.uiState.value.concept)

                vm.cycleConcept()
                advanceUntilIdle()
                assertEquals(CockpitConcept.MAP, vm.uiState.value.concept)

                vm.cycleConcept()
                advanceUntilIdle()
                assertEquals(CockpitConcept.DRIVER, vm.uiState.value.concept)

                vm.cycleConcept()
                advanceUntilIdle()
                assertEquals(CockpitConcept.RADAR, vm.uiState.value.concept)

                // Every advance is persisted in order.
                assertEquals(
                    listOf(CockpitConcept.MAP, CockpitConcept.DRIVER, CockpitConcept.RADAR),
                    prefs.concepts,
                )
            } finally {
                store.clear()
            }
        }

    @Test
    fun selectLocationSource_updatesStatePersistsAndRoutesSelect() =
        runTest {
            val fake = StubLocationSource(LocationSourceType.FAKE)
            val system = StubLocationSource(LocationSourceType.SYSTEM)
            val routed =
                RoutedLocationSource(
                    registry = LocationSourceRegistry(sources = listOf(fake, system)),
                    scope = this.backgroundScope,
                    initialType = LocationSourceType.FAKE,
                )
            val prefs = RecordingCockpitPreferences()
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = routed,
                        preferences = prefs,
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()
                assertEquals(LocationSourceType.FAKE, vm.uiState.value.selectedLocationSource)

                vm.selectLocationSource(LocationSourceType.SYSTEM)
                advanceUntilIdle()

                // State follows the routed source's selection, the choice is persisted,
                // and the routed source stopped FAKE and started SYSTEM.
                assertEquals(LocationSourceType.SYSTEM, vm.uiState.value.selectedLocationSource)
                assertEquals(listOf(LocationSourceType.SYSTEM), prefs.locationSources)
                assertEquals(1, fake.stopCalls)
                assertEquals(1, system.startCalls)
            } finally {
                store.clear()
            }
        }

    @Test
    fun setMapMode_persistsSelectedMode() =
        runTest {
            val prefs = RecordingCockpitPreferences()
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = prefs,
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()

                vm.setMapMode(MapMode.TILT)
                advanceUntilIdle()
                assertEquals(listOf(MapMode.TILT), prefs.mapModes)
            } finally {
                store.clear()
            }
        }

    @Test
    fun setTiltDeg_persistsClampedValue() =
        runTest {
            val prefs = RecordingCockpitPreferences()
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = prefs,
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()

                // Out-of-range request is clamped before persistence (120 -> MAX_TILT_DEG).
                vm.setTiltDeg(120)
                advanceUntilIdle()
                assertEquals(listOf(CockpitUiState.MAX_TILT_DEG), prefs.tiltDegs)
            } finally {
                store.clear()
            }
        }

    @Test
    fun selectTransport_andSetTransportConnected_reflectInState() =
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
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()
                assertEquals(TransportType.BLE, vm.uiState.value.selectedTransport)
                assertEquals(ConnectionPhase.DISCONNECTED, vm.uiState.value.connectionPhase)

                // Selecting a transport flows back through the gateway into state.
                vm.selectTransport(TransportType.USB)
                advanceUntilIdle()
                assertEquals(TransportType.USB, vm.uiState.value.selectedTransport)

                // Connecting / disconnecting drives the connection phase.
                vm.setTransportConnected(true)
                advanceUntilIdle()
                assertEquals(ConnectionPhase.CONNECTED, vm.uiState.value.connectionPhase)

                vm.setTransportConnected(false)
                advanceUntilIdle()
                assertEquals(ConnectionPhase.DISCONNECTED, vm.uiState.value.connectionPhase)
            } finally {
                store.clear()
            }
        }

    @Test
    fun nudgeFakeGps_thenReset_movesEgoFixAndReturnsToCenter() =
        runTest {
            // Back the routed source with the *same* FakeLocationSource the VM steers,
            // so the live ticker's fixes flow into uiState.locationState (and egoFix).
            val fake =
                ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                    scope = this.backgroundScope,
                )
            val routed =
                RoutedLocationSource(
                    registry = LocationSourceRegistry(sources = listOf(fake)),
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
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource = fake,
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                // The fake source has an infinite-delay ticker; never advanceUntilIdle
                // it. runCurrent drains init + the first emitted Active fix only.
                runCurrent()
                val centerFix = vm.uiState.value.egoFix
                assertNotNull(centerFix)
                assertEquals(GoldenSpike.Y2025.lat, centerFix!!.location.lat, 1e-9)
                assertEquals(GoldenSpike.Y2025.lon, centerFix.location.lon, 1e-9)

                // Teleporting north pushes an immediate fix at a higher latitude.
                vm.nudgeFakeGps(0.0, 500.0)
                runCurrent()
                val nudged = vm.uiState.value.egoFix
                assertNotNull(nudged)
                assertTrue(
                    "expected nudged lat ${nudged!!.location.lat} north of center ${centerFix.location.lat}",
                    nudged.location.lat > centerFix.location.lat,
                )

                // Reset clears the parked offset, returning the ego to center.
                vm.resetFakeGps()
                runCurrent()
                val resetFix = vm.uiState.value.egoFix
                assertNotNull(resetFix)
                assertEquals(GoldenSpike.Y2025.lat, resetFix!!.location.lat, 1e-9)
                assertEquals(GoldenSpike.Y2025.lon, resetFix.location.lon, 1e-9)
            } finally {
                store.clear()
            }
        }

    @Test
    fun telemetryStream_doesNotOverwriteUserHeading() =
        runTest {
            // StaticTelemetryRepo reports headingDeg = 42 but thermalC = 60 / DIAGNOSTIC.
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()

                // User sets a heading that differs from the telemetry-reported 42.
                vm.setHeading(200)
                advanceUntilIdle()

                // Telemetry drove thermal / mode (so the collector demonstrably ran)...
                assertEquals(60, vm.uiState.value.thermalC)
                assertEquals(CockpitMode.DIAGNOSTIC, vm.uiState.value.mode)
                // ...but never clobbered the user-owned heading.
                assertEquals(200, vm.uiState.value.headingDeg)
            } finally {
                store.clear()
            }
        }

    @Test
    fun autoRecenter_revertsToTrackUpAfterTimeout() =
        runTest {
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource =
                            ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle() // let VM init settle

                // A rotate gesture switches to FREE and arms the auto-recenter timer.
                // Read synchronously so we observe FREE before the timer fires.
                vm.nudgeViewRotation(30f)
                assertEquals(FollowMode.FREE, vm.uiState.value.followMode)

                // The delay(AUTO_RECENTER_MS) runs on the test scheduler; advancing
                // virtual time past it fires the deterministic revert to TRACK_UP.
                advanceTimeBy(AUTO_RECENTER_MS)
                runCurrent()
                assertEquals(FollowMode.TRACK_UP, vm.uiState.value.followMode)
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

private class NoOpCockpitPreferences : CockpitPreferences {
    override suspend fun read(): CockpitPrefsSnapshot = CockpitPrefsSnapshot.DEFAULT

    override suspend fun setLocationSource(type: LocationSourceType) = Unit

    override suspend fun setMapMode(mode: MapMode) = Unit

    override suspend fun setTiltDeg(deg: Int) = Unit

    override suspend fun setPixelsPerMeter(zoom: Double) = Unit

    override suspend fun setConcept(concept: CockpitConcept) = Unit

    override suspend fun readBurnInConfig(): BurnInConfig = BurnInConfig()

    override suspend fun setBurnInConfig(config: BurnInConfig) = Unit
}

private class RecordingCockpitPreferences(
    private val snapshot: CockpitPrefsSnapshot = CockpitPrefsSnapshot.DEFAULT,
) : CockpitPreferences {
    val locationSources = mutableListOf<LocationSourceType>()
    val mapModes = mutableListOf<MapMode>()
    val tiltDegs = mutableListOf<Int>()
    val zooms = mutableListOf<Double>()
    val concepts = mutableListOf<CockpitConcept>()

    override suspend fun read(): CockpitPrefsSnapshot = snapshot

    override suspend fun setLocationSource(type: LocationSourceType) {
        locationSources += type
    }

    override suspend fun setMapMode(mode: MapMode) {
        mapModes += mode
    }

    override suspend fun setTiltDeg(deg: Int) {
        tiltDegs += deg
    }

    override suspend fun setPixelsPerMeter(zoom: Double) {
        zooms += zoom
    }

    override suspend fun setConcept(concept: CockpitConcept) {
        concepts += concept
    }

    override suspend fun readBurnInConfig(): BurnInConfig = BurnInConfig()

    override suspend fun setBurnInConfig(config: BurnInConfig) = Unit
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
