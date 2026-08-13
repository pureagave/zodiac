package org.pureagave.zodiac.control

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.pureagave.zodiac.control.burnin.BurnInConfig
import org.pureagave.zodiac.control.core.connection.ConnectionPhase
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.AUTO_RECENTER_MS
import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.core.model.CockpitMode
import org.pureagave.zodiac.control.core.model.FollowMode
import org.pureagave.zodiac.control.core.model.MapLoadResult
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.model.StreetKind
import org.pureagave.zodiac.control.core.model.StreetLine
import org.pureagave.zodiac.control.core.model.Telemetry
import org.pureagave.zodiac.control.core.model.VehicleCommand
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.clockToBearing
import org.pureagave.zodiac.control.core.ops.NavShareMessage
import org.pureagave.zodiac.control.core.ops.NavSharePayload
import org.pureagave.zodiac.control.core.ops.NavShareProtocol
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.core.telemetry.AmbientLight
import org.pureagave.zodiac.control.core.telemetry.BeaconHealth
import org.pureagave.zodiac.control.core.telemetry.BeaconSensors
import org.pureagave.zodiac.control.core.telemetry.Odometer
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.VisionFeed
import org.pureagave.zodiac.control.data.FakeVehicleGateway
import org.pureagave.zodiac.control.data.TelemetryRepository
import org.pureagave.zodiac.control.data.nav.NavSharePublisher
import org.pureagave.zodiac.control.data.playa.PlayaMapRepository
import org.pureagave.zodiac.control.data.prefs.CockpitPreferences
import org.pureagave.zodiac.control.data.prefs.CockpitPrefsSnapshot
import org.pureagave.zodiac.control.data.sensor.FakeLocationSource
import org.pureagave.zodiac.control.data.sensor.LocationSourceRegistry
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource
import org.pureagave.zodiac.control.data.sensor.StubLocationSource
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModelFactory

// Canonical ViewModel test: many small scenarios; size mirrors the VM's own
// broad surface (the VM itself carries relaxed detekt thresholds for the same reason).
@Suppress("LargeClass")
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                assertEquals(org.pureagave.zodiac.control.core.model.FollowMode.FREE, vm.uiState.value.followMode)
                assertEquals(cap, parked!!.eastM, 0.0)
                assertEquals(-cap, parked.northM, 0.0)

                // Recenter clears the override and goes back to TRACK_UP.
                vm.recenterPan()
                assertEquals(null, vm.uiState.value.cameraOverride)
                assertEquals(org.pureagave.zodiac.control.core.model.FollowMode.TRACK_UP, vm.uiState.value.followMode)
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
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
    fun nudgeViewRotation_incidentalTwistStaysTrackUp_cumulativeCommitsFree() =
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
                                scope = this.backgroundScope,
                            ),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle() // let VM init settle
                assertEquals(FollowMode.TRACK_UP, vm.uiState.value.followMode)

                // A pinch co-fires a rotate step for every incidental twist of the
                // fingers. Four 1-degree steps (4 deg total) stay below the 5 deg
                // commit threshold, so the gesture must not flip out of track-up.
                repeat(4) { vm.nudgeViewRotation(1f) }
                assertEquals(FollowMode.TRACK_UP, vm.uiState.value.followMode)

                // The fifth 1-degree step crosses the 5 deg cumulative threshold and
                // commits to FREE. Do NOT advance the scheduler here (mirrors
                // nudgeViewRotation_normalizesIntoRange) or the 60 s auto-recenter
                // job would fire; store.clear() below cancels it either way.
                vm.nudgeViewRotation(1f)
                assertEquals(FollowMode.FREE, vm.uiState.value.followMode)
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                assertEquals(GoldenSpike.ACTIVE.lat, centerFix!!.location.lat, 1e-9)
                assertEquals(GoldenSpike.ACTIVE.lon, centerFix.location.lon, 1e-9)

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
                assertEquals(GoldenSpike.ACTIVE.lat, resetFix!!.location.lat, 1e-9)
                assertEquals(GoldenSpike.ACTIVE.lon, resetFix.location.lon, 1e-9)
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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
                            org.pureagave.zodiac.control.data.sensor.FakeLocationSource(
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

    @Test
    fun driveToAddress_setsCustomTarget_clearsBath_andIgnoresUnknownRing() =
        runTest {
            val store = ViewModelStore()
            try {
                val vm = driveToVm(this.backgroundScope, store)
                advanceUntilIdle()

                vm.driveToNearestToilet()
                advanceUntilIdle()
                assertTrue(vm.uiState.value.driveToBath)

                // A valid ring sets the custom target and clears the BATH lock.
                vm.driveToAddress(ClockTime(2, 15), "H")
                advanceUntilIdle()
                assertEquals("2:15 & H", vm.uiState.value.customTarget?.label)
                assertFalse(vm.uiState.value.driveToBath)

                // An unknown ring is a no-op — the prior target stands.
                vm.driveToAddress(ClockTime(4, 0), "Z")
                advanceUntilIdle()
                assertEquals("2:15 & H", vm.uiState.value.customTarget?.label)
            } finally {
                store.clear()
            }
        }

    @Test
    fun setNavTarget_clearsCustomTargetAndBath() =
        runTest {
            val store = ViewModelStore()
            try {
                val vm = driveToVm(this.backgroundScope, store)
                advanceUntilIdle()

                vm.driveToAddress(ClockTime(2, 15), "H")
                advanceUntilIdle()
                assertNotNull(vm.uiState.value.customTarget)

                vm.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()
                assertNull(vm.uiState.value.customTarget)
                assertFalse(vm.uiState.value.driveToBath)
                assertEquals(NavTarget.MAN, vm.uiState.value.navTarget)
            } finally {
                store.clear()
            }
        }

    @Test
    fun route_populates_after_the_map_loads_and_an_address_is_set() =
        runTest {
            // Back the routed source with the same FakeLocationSource the VM steers,
            // so a live fix (the Man, by default) flows into egoFix.
            val fake = FakeLocationSource(scope = this.backgroundScope)
            val routed =
                RoutedLocationSource(
                    registry = LocationSourceRegistry(sources = listOf(fake)),
                    scope = this.backgroundScope,
                    initialType = LocationSourceType.FAKE,
                )
            val mapRepo = ControllablePlayaMapRepository()
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = mapRepo,
                        locationSource = routed,
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource = fake,
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                // FakeLocationSource has an infinite ticker — use runCurrent, never advanceUntilIdle.
                runCurrent()
                assertNotNull("ego should have a fix at the Man", vm.uiState.value.egoFix)

                // Enter H & 2:30 — with no city model loaded yet the route can't resolve.
                vm.driveToAddress(ClockTime(2, 30), "H")
                runCurrent()
                assertEquals("2:30 & H", vm.uiState.value.customTarget?.label)
                assertTrue("no city yet → empty route", vm.uiState.value.routeWaypointsM.isEmpty())

                // The map loads → cityModel is built → the route recomputes.
                mapRepo.emitLoaded(routableMap())
                runCurrent()
                assertTrue(
                    "route should populate once the city model exists",
                    vm.uiState.value.routeWaypointsM.isNotEmpty(),
                )
                assertEquals("2:30", vm.uiState.value.entranceRadial)
                assertNotNull(vm.uiState.value.nextWaypoint)
            } finally {
                store.clear()
            }
        }

    // --- C10: mapLoadError was dead state — nothing rendered it and there
    // was no retry. These cover the state-transition/retry-gating logic
    // that now backs the on-screen overlay (ui/concepts/PlayaMapPanel.kt's
    // mapLoadErrorOverlay, which is visual-only and unverified by test).

    @Test
    fun failed_map_load_surfaces_as_mapLoadError() =
        runTest {
            val mapRepo = ControllablePlayaMapRepository()
            val store = ViewModelStore()
            try {
                val vm = mapLoadVm(this.backgroundScope, store, mapRepo)
                advanceUntilIdle()

                mapRepo.emitFailed("art.geojson: unexpected token at line 4")
                advanceUntilIdle()

                assertEquals(
                    "art.geojson: unexpected token at line 4",
                    vm.uiState.value.mapLoadError,
                )
                assertNull(vm.uiState.value.playaMap)
            } finally {
                store.clear()
            }
        }

    @Test
    fun successful_map_load_clears_mapLoadError() =
        runTest {
            val mapRepo = ControllablePlayaMapRepository()
            val store = ViewModelStore()
            try {
                val vm = mapLoadVm(this.backgroundScope, store, mapRepo)
                advanceUntilIdle()

                mapRepo.emitFailed("boom")
                advanceUntilIdle()
                assertNotNull(vm.uiState.value.mapLoadError)

                mapRepo.emitLoaded(routableMap())
                advanceUntilIdle()

                assertNull(vm.uiState.value.mapLoadError)
                assertNotNull(vm.uiState.value.playaMap)
            } finally {
                store.clear()
            }
        }

    @Test
    fun retryMapLoad_calls_the_repository_and_a_later_success_clears_the_error() =
        runTest {
            val mapRepo = ControllablePlayaMapRepository()
            val store = ViewModelStore()
            try {
                val vm = mapLoadVm(this.backgroundScope, store, mapRepo)
                advanceUntilIdle()
                // The VM's own init already issued one load() (the cold start);
                // count retries as a delta from that so this test is about
                // retryMapLoad specifically, not the total lifetime call count.
                val baseline = mapRepo.loadCallCount

                mapRepo.emitFailed("malformed street_lines.geojson")
                advanceUntilIdle()
                assertNotNull(vm.uiState.value.mapLoadError)

                vm.retryMapLoad()
                advanceUntilIdle()
                // The ControllablePlayaMapRepository's load() is a bare stub that
                // returns immediately, so retry's observable effect is the load()
                // call — and the guard has already reopened, since it now clears
                // when load() returns (not only on a flow emission).
                assertEquals(baseline + 1, mapRepo.loadCallCount)
                assertFalse(vm.uiState.value.mapLoadRetrying)

                // The repository's load() (real implementation) would now
                // publish the outcome on loadResult; simulate it succeeding.
                mapRepo.emitLoaded(routableMap())
                advanceUntilIdle()

                assertNull(vm.uiState.value.mapLoadError)
                assertNotNull(vm.uiState.value.playaMap)
                assertFalse(vm.uiState.value.mapLoadRetrying)
            } finally {
                store.clear()
            }
        }

    @Test
    fun retryMapLoad_clears_the_guard_even_when_the_reattempt_fails_identically() =
        runTest {
            val mapRepo = ControllablePlayaMapRepository()
            val store = ViewModelStore()
            try {
                // load() re-publishes the SAME Failed(message) each call, exactly
                // like the real repo on a deterministic bad asset.
                mapRepo.failOnLoad = "malformed street_lines.geojson"
                val vm = mapLoadVm(this.backgroundScope, store, mapRepo)
                advanceUntilIdle()
                assertNotNull(vm.uiState.value.mapLoadError)
                assertFalse(vm.uiState.value.mapLoadRetrying)

                vm.retryMapLoad()
                advanceUntilIdle()
                // The identical Failed is conflated (no re-emit), so the
                // loadResult collector never runs; the guard must be reopened by
                // retryMapLoad's own coroutine or the RETRY chip wedges forever.
                assertFalse(
                    "retry guard must clear after a re-attempt that fails identically",
                    vm.uiState.value.mapLoadRetrying,
                )

                // And a subsequent retry is therefore not blocked.
                val n = mapRepo.loadCallCount
                vm.retryMapLoad()
                advanceUntilIdle()
                assertEquals("the reopened guard admits the next retry", n + 1, mapRepo.loadCallCount)
            } finally {
                store.clear()
            }
        }

    @Test
    fun retryMapLoad_while_already_loading_does_not_double_load() =
        runTest {
            val mapRepo = ControllablePlayaMapRepository()
            val gate = CompletableDeferred<Unit>()
            mapRepo.loadGate = gate
            val store = ViewModelStore()
            try {
                val vm = mapLoadVm(this.backgroundScope, store, mapRepo)
                advanceUntilIdle()
                // Init's own load() call is already suspended on the gate at
                // this point — baseline captures that so the assertions below
                // are about retryMapLoad's call count, not the total.
                val baseline = mapRepo.loadCallCount

                mapRepo.emitFailed("timeout reading brc/2026/city_blocks.geojson")
                advanceUntilIdle()

                vm.retryMapLoad()
                runCurrent() // let the first retry's load() call land and start suspending on the gate
                assertEquals(baseline + 1, mapRepo.loadCallCount)
                assertTrue(vm.uiState.value.mapLoadRetrying)

                // Second retry while the first is still in flight must be a no-op.
                vm.retryMapLoad()
                runCurrent()
                assertEquals(baseline + 1, mapRepo.loadCallCount)

                // Release every attempt waiting on the gate (init's + the retry's).
                // Emit a *distinct* result — StateFlow only re-emits on a value
                // change, so re-publishing the identical Failed("...") the flow
                // already holds would silently not notify the collector and
                // this assertion would pass for the wrong reason.
                gate.complete(Unit)
                mapRepo.emitLoaded(routableMap())
                advanceUntilIdle()
                assertFalse(vm.uiState.value.mapLoadRetrying)

                // Now that the in-flight attempt resolved, a fresh retry is allowed.
                vm.retryMapLoad()
                advanceUntilIdle()
                assertEquals(baseline + 2, mapRepo.loadCallCount)
            } finally {
                store.clear()
            }
        }

    @Test
    fun mapLoadError_names_the_underlying_cause_not_a_generic_string() =
        runTest {
            val mapRepo = ControllablePlayaMapRepository()
            val store = ViewModelStore()
            try {
                val vm = mapLoadVm(this.backgroundScope, store, mapRepo)
                advanceUntilIdle()

                val specific = "JSONException: key 'type' not found in city_blocks.geojson feature 12"
                mapRepo.emitFailed(specific)
                advanceUntilIdle()

                // The ViewModel must forward the repository's message verbatim,
                // not paraphrase it into something generic like "map failed" —
                // that's the whole point of C10 for an unattended, kiosk-locked
                // fleet with no way to inspect the failure except this string
                // and the rolling log.
                assertEquals(specific, vm.uiState.value.mapLoadError)
            } finally {
                store.clear()
            }
        }

    @Test
    fun beaconSensors_foldAmbientHealthOdometer_intoState() =
        runTest {
            val sensors = MutableStateFlow(BeaconSensors())
            val store = ViewModelStore()
            try {
                val vm = beaconVm(this.backgroundScope, store, sensors)
                advanceUntilIdle()
                // Defaults: no beacon telemetry yet.
                assertNull(vm.uiState.value.ambientLux)
                assertNull(vm.uiState.value.beaconHealth)
                assertNull(vm.uiState.value.odometer)

                val health = BeaconHealth(batteryPct = 87, fixQuality = 1, satellites = 9, uptimeSec = 3600L)
                val odo = Odometer(tripMeters = 1234.5, totalMeters = 987654.0)
                sensors.value =
                    BeaconSensors(
                        ambientLight = AmbientLight(lux = 315.0),
                        beaconHealth = health,
                        odometer = odo,
                    )
                advanceUntilIdle()

                assertEquals(315.0, vm.uiState.value.ambientLux!!, 0.0)
                assertEquals(health, vm.uiState.value.beaconHealth)
                assertEquals(odo, vm.uiState.value.odometer)
            } finally {
                store.clear()
            }
        }

    @Test
    fun beaconSensors_nullAmbientLight_keepsAmbientLuxNull() =
        runTest {
            val sensors = MutableStateFlow(BeaconSensors())
            val store = ViewModelStore()
            try {
                val vm = beaconVm(this.backgroundScope, store, sensors)
                advanceUntilIdle()

                // Health/odometer present, but no ambient light reading -> lux stays null.
                sensors.value =
                    BeaconSensors(
                        ambientLight = null,
                        beaconHealth = BeaconHealth(batteryPct = 50, fixQuality = 0, satellites = 0, uptimeSec = 1L),
                    )
                advanceUntilIdle()

                assertNull(vm.uiState.value.ambientLux)
                assertNotNull(vm.uiState.value.beaconHealth)
            } finally {
                store.clear()
            }
        }

    @Test
    fun shock_setsAlertG_thenClearsAfterTimeout() =
        runTest {
            val sensors = MutableStateFlow(BeaconSensors())
            val store = ViewModelStore()
            try {
                val vm = beaconVm(this.backgroundScope, store, sensors)
                advanceUntilIdle()
                assertNull(vm.uiState.value.shockAlertG)

                // A new shock (count went up) arms the transient alert banner.
                sensors.value = BeaconSensors(lastShockG = 2.4, shockCount = 1)
                runCurrent()
                assertEquals(2.4, vm.uiState.value.shockAlertG!!, 0.0)

                // It clears itself after SHOCK_ALERT_MS (2000 ms).
                advanceTimeBy(SHOCK_ALERT_MS)
                runCurrent()
                assertNull(vm.uiState.value.shockAlertG)
            } finally {
                store.clear()
            }
        }

    @Test
    fun shock_secondShockBeforeTimeout_reArmsTheTimer() =
        runTest {
            val sensors = MutableStateFlow(BeaconSensors())
            val store = ViewModelStore()
            try {
                val vm = beaconVm(this.backgroundScope, store, sensors)
                advanceUntilIdle()

                // First shock arms the banner.
                sensors.value = BeaconSensors(lastShockG = 2.0, shockCount = 1)
                runCurrent()
                assertEquals(2.0, vm.uiState.value.shockAlertG!!, 0.0)

                // A second shock 500 ms later cancels the first timer and re-arms;
                // the banner shows the new g and does NOT clear when the *first*
                // timer would have fired.
                advanceTimeBy(HALF_SHOCK_ALERT_MS)
                runCurrent()
                sensors.value = BeaconSensors(lastShockG = 3.1, shockCount = 2)
                runCurrent()
                assertEquals(3.1, vm.uiState.value.shockAlertG!!, 0.0)

                // 1500 ms after the *second* shock is still inside its own window
                // (would have been past the first shock's 2000 ms window).
                advanceTimeBy(SHOCK_ALERT_MS - HALF_SHOCK_ALERT_MS)
                runCurrent()
                assertEquals(3.1, vm.uiState.value.shockAlertG!!, 0.0)

                // The remaining 500 ms clears it.
                advanceTimeBy(HALF_SHOCK_ALERT_MS)
                runCurrent()
                assertNull(vm.uiState.value.shockAlertG)
            } finally {
                store.clear()
            }
        }

    @Test
    fun shock_sameMagnitudeTwice_firesTwice_becauseCountIncrements() =
        runTest {
            val sensors = MutableStateFlow(BeaconSensors())
            val store = ViewModelStore()
            try {
                val vm = beaconVm(this.backgroundScope, store, sensors)
                advanceUntilIdle()

                // First bump at 1.8 g.
                sensors.value = BeaconSensors(lastShockG = 1.8, shockCount = 1)
                runCurrent()
                assertEquals(1.8, vm.uiState.value.shockAlertG!!, 0.0)

                // Let it clear.
                advanceTimeBy(SHOCK_ALERT_MS)
                runCurrent()
                assertNull(vm.uiState.value.shockAlertG)

                // An equal-magnitude second bump still registers because the
                // *count* incremented (the fold compares counts, not g values).
                sensors.value = BeaconSensors(lastShockG = 1.8, shockCount = 2)
                runCurrent()
                assertEquals(1.8, vm.uiState.value.shockAlertG!!, 0.0)

                advanceTimeBy(SHOCK_ALERT_MS)
                runCurrent()
                assertNull(vm.uiState.value.shockAlertG)
            } finally {
                store.clear()
            }
        }

    @Test
    fun visionFeed_defaultsToAbsent_becauseNoVisionSourceMeansNoVision() =
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
                        fakeLocationSource = FakeLocationSource(scope = this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()

                assertEquals(VisionFeed.ABSENT, vm.uiState.value.visionFeed)
            } finally {
                store.clear()
            }
        }

    @Test
    fun brakeAdvisory_firesFromMeasuredGpsSpeed_withoutTouchingTheDebugChip() =
        runTest {
            // The bug this pins: the BRAKE gate read uiState.speedKph, whose only
            // writer is the SPD debug chip. On a real drive that stays 0, so the
            // braking imperative could never fire -- and the rear alert has no
            // speed gate, which is exactly why bench testing against real bus
            // traffic never caught it. Nothing here calls setSpeed().
            val stub = StubLocationSource(LocationSourceType.FAKE)
            val threats =
                MutableStateFlow(
                    listOf(DriverThreat(relAzDeg = 0f, size = 0.8f, collision = true, id = 1)),
                )
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource =
                            RoutedLocationSource(
                                registry = LocationSourceRegistry(sources = listOf(stub)),
                                // Unconfined on the test scheduler: the routed
                                // source's stateIn must propagate synchronously,
                                // otherwise the stub's emission never reaches
                                // the VM under MainDispatcherRule's separate
                                // scheduler.
                                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                                initialType = LocationSourceType.FAKE,
                            ),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource = FakeLocationSource(scope = this.backgroundScope),
                        threatsFlow = threats,
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()
                assertFalse("stationary vehicle must not advise braking", vm.uiState.value.brakeAdvised)

                stub.emit(
                    LocationSourceState.Active(
                        GpsFix(location = LatLon(40.786, -119.203), speedKph = 20.0),
                    ),
                )
                advanceUntilIdle()

                assertEquals(0, vm.uiState.value.speedKph) // debug chip untouched
                assertEquals(20.0, vm.uiState.value.effectiveSpeedKph, 1e-6)
                assertTrue("BRAKE must fire on measured speed", vm.uiState.value.brakeAdvised)
            } finally {
                store.clear()
            }
        }

    @Test
    fun measuredSpeedIsHeldWhenASentenceOmitsIt() =
        runTest {
            // Phones interleave GGA (no speed) with RMC (speed) every epoch. If
            // the fold dropped speed on the GGA fix, the brake gate would blink
            // below threshold on alternate sentences.
            val stub = StubLocationSource(LocationSourceType.FAKE)
            val routed =
                RoutedLocationSource(
                    registry = LocationSourceRegistry(sources = listOf(stub)),
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
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
                        fakeLocationSource = FakeLocationSource(scope = this.backgroundScope),
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()

                stub.emit(LocationSourceState.Active(GpsFix(LatLon(40.786, -119.203), speedKph = 30.0)))
                advanceUntilIdle()
                assertTrue(
                    "precondition: the fix must reach the VM",
                    vm.uiState.value.locationState is LocationSourceState.Active,
                )
                assertEquals(30.0, vm.uiState.value.effectiveSpeedKph, 1e-6)

                stub.emit(LocationSourceState.Active(GpsFix(LatLon(40.786, -119.203), speedKph = null)))
                advanceUntilIdle()
                assertEquals(30.0, vm.uiState.value.effectiveSpeedKph, 1e-6)
            } finally {
                store.clear()
            }
        }

    @Test
    fun visionFeed_followsTheRoutedFeedStateFlow() =
        runTest {
            val feed = MutableStateFlow(VisionFeed.DEMO)
            val store = ViewModelStore()
            try {
                val factory =
                    CockpitViewModelFactory(
                        telemetryRepository = StaticTelemetryRepo(),
                        vehicleGateway = FakeVehicleGateway(),
                        playaMapRepository = NoOpPlayaMapRepository,
                        locationSource = newFakeRoutedLocationSource(this.backgroundScope),
                        preferences = NoOpCockpitPreferences(),
                        fakeLocationSource = FakeLocationSource(scope = this.backgroundScope),
                        visionFeedFlow = feed,
                    )
                val vm = ViewModelProvider(store, factory)[CockpitViewModel::class.java]
                advanceUntilIdle()
                assertEquals(VisionFeed.DEMO, vm.uiState.value.visionFeed)

                // A crashed edge box or dropped WiFi must be observable, not
                // silently swallowed — this is the exact scenario 1.5e exists
                // to fix (a dead feed used to render as "0 CONTACTS CLEAR").
                feed.value = VisionFeed.ABSENT
                runCurrent()
                assertEquals(VisionFeed.ABSENT, vm.uiState.value.visionFeed)
            } finally {
                store.clear()
            }
        }

    // --- $ZNAV send half (Phase 2): an authority device's local sets update
    // state and publish exactly once each, with a monotonically incrementing
    // Lamport seq. The receive half (adopt/yield/no-echo/follower-gating)
    // lands in Phase 3, once NavShareReceiver exists. ---

    @Test
    fun navAuthority_localSet_updatesStateAndPublishesExactlyOneParseableZnavWithIncrementingSeq() =
        runTest {
            val publisher = FakeNavSharePublisher()
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, publisher = publisher)
                advanceUntilIdle()

                vm.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()
                assertEquals(NavTarget.MAN, vm.uiState.value.navTarget)
                assertEquals(1, publisher.published.size)
                val first = NavShareProtocol.parse(publisher.published[0])
                assertNotNull("a published sentence must itself parse", first)
                assertEquals(1, first!!.seq)
                assertEquals(NavSharePayload.Preset(NavTarget.MAN), first.payload)

                vm.setNavTarget(NavTarget.TEMPLE)
                advanceUntilIdle()
                assertEquals(2, publisher.published.size)
                val second = NavShareProtocol.parse(publisher.published[1])
                assertNotNull(second)
                assertEquals(2, second!!.seq)
                assertEquals(NavSharePayload.Preset(NavTarget.TEMPLE), second.payload)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navAuthority_driveToAddress_publishesOnSuccess_andSeqPersists() =
        runTest {
            val publisher = FakeNavSharePublisher()
            val prefs = RecordingCockpitPreferences()
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, publisher = publisher, preferences = prefs)
                advanceUntilIdle()

                val applied = vm.driveToAddress(ClockTime(2, 15), "H")
                advanceUntilIdle()

                assertTrue("a resolvable address must apply", applied)
                assertEquals("2:15 & H", vm.uiState.value.customTarget?.label)
                assertEquals(1, publisher.published.size)
                assertEquals(listOf(1), prefs.navShareSeqs)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navAuthority_driveToAddress_unknownRing_doesNotPublish() =
        runTest {
            val publisher = FakeNavSharePublisher()
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, publisher = publisher)
                advanceUntilIdle()

                val applied = vm.driveToAddress(ClockTime(4, 0), "Z")
                advanceUntilIdle()

                assertFalse("an unresolvable ring must not apply", applied)
                assertTrue("an unresolvable ring must not publish either", publisher.published.isEmpty())
            } finally {
                store.clear()
            }
        }

    @Test
    fun navAuthority_bath_publishesBathPayload() =
        runTest {
            val publisher = FakeNavSharePublisher()
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, publisher = publisher)
                advanceUntilIdle()

                vm.driveToNearestToilet()
                advanceUntilIdle()

                assertTrue(vm.uiState.value.driveToBath)
                assertEquals(1, publisher.published.size)
                assertEquals(NavSharePayload.Bath, NavShareProtocol.parse(publisher.published[0])!!.payload)
            } finally {
                store.clear()
            }
        }

    // --- $ZNAV receive half (Phase 3): adoption applies via the same
    // NavigationController methods a local set uses, never publishes
    // (no-echo), yields ownership on a higher remote seq, and follower
    // devices are gated at every one of the four entry points. ---

    @Test
    fun navShare_receivedMessage_appliesLikeALocalCall_andNeverPublishes_noEcho() =
        runTest {
            val publisher = FakeNavSharePublisher()
            val navShareFlow = MutableStateFlow<NavShareMessage?>(null)
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, publisher = publisher, navShareFlow = navShareFlow)
                advanceUntilIdle()

                navShareFlow.value = NavShareMessage(seq = 1, src = "OTHER", payload = NavSharePayload.Address(ClockTime(2, 15), "H"))
                advanceUntilIdle()

                assertEquals("2:15 & H", vm.uiState.value.customTarget?.label)
                assertEquals("adoption must never publish", 0, publisher.published.size)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navShare_receivedBath_setsDriveToBath() =
        runTest {
            val navShareFlow = MutableStateFlow<NavShareMessage?>(null)
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, navShareFlow = navShareFlow)
                advanceUntilIdle()

                navShareFlow.value = NavShareMessage(seq = 1, src = "OTHER", payload = NavSharePayload.Bath)
                advanceUntilIdle()

                assertTrue(vm.uiState.value.driveToBath)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navShare_receivedClear_setsTargetToHome() =
        runTest {
            val navShareFlow = MutableStateFlow<NavShareMessage?>(null)
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, navShareFlow = navShareFlow)
                advanceUntilIdle()
                // Start somewhere other than HOME so CLEAR is an observable change.
                vm.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()
                assertEquals(NavTarget.MAN, vm.uiState.value.navTarget)

                navShareFlow.value = NavShareMessage(seq = 2, src = "OTHER", payload = NavSharePayload.Clear)
                advanceUntilIdle()

                assertEquals(NavTarget.HOME, vm.uiState.value.navTarget)
                assertFalse(vm.uiState.value.driveToBath)
                assertNull(vm.uiState.value.customTarget)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navShare_receivedUnknownRingAddress_adoptsNothing_andDoesNotCrash() =
        runTest {
            val navShareFlow = MutableStateFlow<NavShareMessage?>(null)
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, navShareFlow = navShareFlow)
                advanceUntilIdle()
                val before = vm.uiState.value.customTarget

                navShareFlow.value = NavShareMessage(seq = 1, src = "OTHER", payload = NavSharePayload.Address(ClockTime(4, 0), "Z"))
                advanceUntilIdle()

                assertEquals("an unresolvable ring must not change the target", before, vm.uiState.value.customTarget)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navShare_ownSrcEcho_isIgnored() =
        runTest {
            val publisher = FakeNavSharePublisher()
            val navShareFlow = MutableStateFlow<NavShareMessage?>(null)
            val store = ViewModelStore()
            try {
                val vm =
                    navShareVm(
                        this.backgroundScope,
                        store,
                        navAuthority = true,
                        publisher = publisher,
                        navShareFlow = navShareFlow,
                        navSrcId = "ME",
                    )
                advanceUntilIdle()
                val before = vm.uiState.value.navTarget

                // A high-seq message that claims to be from this very device --
                // the receiver hearing its own broadcast reflected back.
                navShareFlow.value = NavShareMessage(seq = 999, src = "ME", payload = NavSharePayload.Preset(NavTarget.TEMPLE))
                advanceUntilIdle()

                assertEquals("own-echo must never be adopted", before, vm.uiState.value.navTarget)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navShare_higherSeqRemote_yieldsOwnership_stateFollowsRemote_andPublisherStops() =
        runTest {
            val publisher = FakeNavSharePublisher()
            val navShareFlow = MutableStateFlow<NavShareMessage?>(null)
            val store = ViewModelStore()
            try {
                val vm =
                    navShareVm(
                        this.backgroundScope,
                        store,
                        navAuthority = true,
                        publisher = publisher,
                        navShareFlow = navShareFlow,
                        navSrcId = "ME",
                    )
                advanceUntilIdle()

                // This device sets locally first -- it becomes the owner (seq=1).
                vm.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()
                assertEquals(NavTarget.MAN, vm.uiState.value.navTarget)
                assertEquals(0, publisher.stopCalls)

                // The other authority sets a moment later with a higher seq.
                navShareFlow.value = NavShareMessage(seq = 5, src = "OTHER", payload = NavSharePayload.Preset(NavTarget.TEMPLE))
                advanceUntilIdle()

                assertEquals("state must follow the higher-seq remote target", NavTarget.TEMPLE, vm.uiState.value.navTarget)
                assertEquals("yielding ownership must stop the periodic re-broadcast", 1, publisher.stopCalls)
            } finally {
                store.clear()
            }
        }

    // --- Follower gating: a device without nav authority must leave every
    // one of the four entry points a genuine no-op -- no state change, no
    // publish -- with a positive (navAuthority = true) control beside each. ---

    @Test
    fun follower_setNavTarget_isNoOp_authorityPositiveControlChanges() =
        runTest {
            val followerPublisher = FakeNavSharePublisher()
            val followerStore = ViewModelStore()
            val authorityPublisher = FakeNavSharePublisher()
            val authorityStore = ViewModelStore()
            try {
                val follower = navShareVm(this.backgroundScope, followerStore, navAuthority = false, publisher = followerPublisher)
                val authority = navShareVm(this.backgroundScope, authorityStore, navAuthority = true, publisher = authorityPublisher)
                advanceUntilIdle()

                follower.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()
                assertEquals("a follower's set must be a genuine no-op", NavTarget.HOME, follower.uiState.value.navTarget)
                assertTrue(followerPublisher.published.isEmpty())

                authority.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()
                assertEquals("positive control: an authority's set applies", NavTarget.MAN, authority.uiState.value.navTarget)
                assertEquals(1, authorityPublisher.published.size)
            } finally {
                followerStore.clear()
                authorityStore.clear()
            }
        }

    @Test
    fun follower_driveToNearestToilet_isNoOp_authorityPositiveControlChanges() =
        runTest {
            val followerPublisher = FakeNavSharePublisher()
            val followerStore = ViewModelStore()
            val authorityPublisher = FakeNavSharePublisher()
            val authorityStore = ViewModelStore()
            try {
                val follower = navShareVm(this.backgroundScope, followerStore, navAuthority = false, publisher = followerPublisher)
                val authority = navShareVm(this.backgroundScope, authorityStore, navAuthority = true, publisher = authorityPublisher)
                advanceUntilIdle()

                follower.driveToNearestToilet()
                advanceUntilIdle()
                assertFalse("a follower's BATH tap must be a genuine no-op", follower.uiState.value.driveToBath)
                assertTrue(followerPublisher.published.isEmpty())

                authority.driveToNearestToilet()
                advanceUntilIdle()
                assertTrue("positive control: an authority's BATH tap applies", authority.uiState.value.driveToBath)
                assertEquals(1, authorityPublisher.published.size)
            } finally {
                followerStore.clear()
                authorityStore.clear()
            }
        }

    @Test
    fun follower_driveToAddress_isNoOp_authorityPositiveControlChanges() =
        runTest {
            val followerPublisher = FakeNavSharePublisher()
            val followerStore = ViewModelStore()
            val authorityPublisher = FakeNavSharePublisher()
            val authorityStore = ViewModelStore()
            try {
                val follower = navShareVm(this.backgroundScope, followerStore, navAuthority = false, publisher = followerPublisher)
                val authority = navShareVm(this.backgroundScope, authorityStore, navAuthority = true, publisher = authorityPublisher)
                advanceUntilIdle()

                val followerApplied = follower.driveToAddress(ClockTime(2, 15), "H")
                advanceUntilIdle()
                assertFalse("a follower's address entry must be a genuine no-op", followerApplied)
                assertNull(follower.uiState.value.customTarget)
                assertTrue(followerPublisher.published.isEmpty())

                val authorityApplied = authority.driveToAddress(ClockTime(2, 15), "H")
                advanceUntilIdle()
                assertTrue("positive control: an authority's address entry applies", authorityApplied)
                assertEquals("2:15 & H", authority.uiState.value.customTarget?.label)
                assertEquals(1, authorityPublisher.published.size)
            } finally {
                followerStore.clear()
                authorityStore.clear()
            }
        }

    @Test
    fun follower_setAddressEntryOpenTrue_staysClosed_authorityPositiveControlOpens() =
        runTest {
            val followerStore = ViewModelStore()
            val authorityStore = ViewModelStore()
            try {
                val follower = navShareVm(this.backgroundScope, followerStore, navAuthority = false)
                val authority = navShareVm(this.backgroundScope, authorityStore, navAuthority = true)
                advanceUntilIdle()

                follower.setAddressEntryOpen(true)
                advanceUntilIdle()
                assertFalse("a follower must not be able to open the address keypad", follower.uiState.value.addressEntryOpen)

                authority.setAddressEntryOpen(true)
                advanceUntilIdle()
                assertTrue("positive control: an authority can open it", authority.uiState.value.addressEntryOpen)

                // Closing is always allowed, even for a follower.
                follower.setAddressEntryOpen(false)
                advanceUntilIdle()
                assertFalse(follower.uiState.value.addressEntryOpen)
            } finally {
                followerStore.clear()
                authorityStore.clear()
            }
        }

    @Test
    fun navShareSeq_persistsOnUserSet_andOnAdoptingAHigherSeq() =
        runTest {
            val navShareFlow = MutableStateFlow<NavShareMessage?>(null)
            val prefs = RecordingCockpitPreferences()
            val store = ViewModelStore()
            try {
                val vm =
                    navShareVm(
                        this.backgroundScope,
                        store,
                        navAuthority = true,
                        navShareFlow = navShareFlow,
                        preferences = prefs,
                        navSrcId = "ME",
                    )
                advanceUntilIdle()

                vm.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()
                assertEquals(listOf(1), prefs.navShareSeqs)

                navShareFlow.value = NavShareMessage(seq = 7, src = "OTHER", payload = NavSharePayload.Preset(NavTarget.TEMPLE))
                advanceUntilIdle()

                assertEquals("adopting a higher seq must persist it too", listOf(1, 7), prefs.navShareSeqs)
            } finally {
                store.clear()
            }
        }

    @Test
    fun navShareSeq_seededFromPersistedValueOnInit_soARebootedAuthorityOutbidsHistory() =
        runTest {
            // A rebooted authority: the DataStore already holds seq=41 from
            // before the power cycle. The VM must seed its arbiter from that on
            // init, so the very next local set mints 42 -- not 1, which every
            // follower still holding maxSeen=41 would reject forever. Guards the
            // `arbiter.seed(preferences.readNavShareSeq())` init call itself:
            // with it removed (or seeded from 0) this device would restart at 1.
            val publisher = FakeNavSharePublisher()
            val prefs = RecordingCockpitPreferences(initialNavShareSeq = 41)
            val store = ViewModelStore()
            try {
                val vm = navShareVm(this.backgroundScope, store, navAuthority = true, publisher = publisher, preferences = prefs)
                advanceUntilIdle()

                vm.setNavTarget(NavTarget.MAN)
                advanceUntilIdle()

                val published = NavShareProtocol.parse(publisher.published.single())
                assertNotNull("a published sentence must itself parse", published)
                assertEquals(
                    "a rebooted authority must resume above its persisted history, not restart at 1",
                    42,
                    published!!.seq,
                )
                assertEquals(listOf(42), prefs.navShareSeqs)
            } finally {
                store.clear()
            }
        }
}

/** How long a shock/impact alert banner stays before it clears (mirrors the VM constant). */
private const val SHOCK_ALERT_MS: Long = 2_000L
private const val HALF_SHOCK_ALERT_MS: Long = 500L

/** A VM wired with no-op deps plus a caller-supplied [beaconSensors] flow for the sensor-fold tests. */
private fun beaconVm(
    scope: CoroutineScope,
    store: ViewModelStore,
    beaconSensors: StateFlow<BeaconSensors>,
): CockpitViewModel {
    val factory =
        CockpitViewModelFactory(
            telemetryRepository = StaticTelemetryRepo(),
            vehicleGateway = FakeVehicleGateway(),
            playaMapRepository = NoOpPlayaMapRepository,
            locationSource = newFakeRoutedLocationSource(scope),
            preferences = NoOpCockpitPreferences(),
            fakeLocationSource = FakeLocationSource(scope = scope),
            beaconSensors = beaconSensors,
        )
    return ViewModelProvider(store, factory)[CockpitViewModel::class.java]
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

/** A VM wired with all no-op deps (no map, stub GPS) for the drive-to selection tests. */
private fun driveToVm(
    scope: CoroutineScope,
    store: ViewModelStore,
): CockpitViewModel {
    val factory =
        CockpitViewModelFactory(
            telemetryRepository = StaticTelemetryRepo(),
            vehicleGateway = FakeVehicleGateway(),
            playaMapRepository = NoOpPlayaMapRepository,
            locationSource = newFakeRoutedLocationSource(scope),
            preferences = NoOpCockpitPreferences(),
            fakeLocationSource = FakeLocationSource(scope = scope),
        )
    return ViewModelProvider(store, factory)[CockpitViewModel::class.java]
}

/** Recording [NavSharePublisher] fake: every [publish] call and every [stop] call is captured, in order, for the send/no-echo/yield assertions. */
private class FakeNavSharePublisher : NavSharePublisher {
    val published = mutableListOf<String>()
    var stopCalls = 0
        private set

    override fun publish(sentence: String) {
        published += sentence
    }

    override fun stop() {
        stopCalls++
    }
}

/**
 * A VM wired for `$ZNAV` scenarios: a fixed [navAuthority] flow, the given
 * [publisher] (defaulting to a fresh recording fake), an optional inbound
 * [navShareFlow] for adoption tests, and [preferences] (defaulting to a fresh
 * [RecordingCockpitPreferences], which tracks `navShareSeqs`).
 */
private fun navShareVm(
    scope: CoroutineScope,
    store: ViewModelStore,
    navAuthority: Boolean,
    publisher: NavSharePublisher = FakeNavSharePublisher(),
    navShareFlow: StateFlow<NavShareMessage?> = MutableStateFlow(null),
    preferences: CockpitPreferences = RecordingCockpitPreferences(),
    navSrcId: String = "ME",
): CockpitViewModel {
    val factory =
        CockpitViewModelFactory(
            telemetryRepository = StaticTelemetryRepo(),
            vehicleGateway = FakeVehicleGateway(),
            playaMapRepository = NoOpPlayaMapRepository,
            locationSource = newFakeRoutedLocationSource(scope),
            preferences = preferences,
            fakeLocationSource = FakeLocationSource(scope = scope),
            navAuthorityFlow = MutableStateFlow(navAuthority),
            navShareFlow = navShareFlow,
            navPublisher = publisher,
            navSrcId = navSrcId,
        )
    return ViewModelProvider(store, factory)[CockpitViewModel::class.java]
}

/** A VM wired with a caller-supplied [ControllablePlayaMapRepository] for the C10 map-load-error tests. */
private fun mapLoadVm(
    scope: CoroutineScope,
    store: ViewModelStore,
    mapRepo: ControllablePlayaMapRepository,
): CockpitViewModel {
    val factory =
        CockpitViewModelFactory(
            telemetryRepository = StaticTelemetryRepo(),
            vehicleGateway = FakeVehicleGateway(),
            playaMapRepository = mapRepo,
            locationSource = newFakeRoutedLocationSource(scope),
            preferences = NoOpCockpitPreferences(),
            fakeLocationSource = FakeLocationSource(scope = scope),
        )
    return ViewModelProvider(store, factory)[CockpitViewModel::class.java]
}

/**
 * PlayaMapRepository whose load result the test drives on demand, and whose
 * [load] calls are counted/gate-able — the C10 retry tests need to see
 * "did retryMapLoad() actually call the repository" and "did it call it
 * exactly once while a previous call was still in flight", which the real
 * [org.pureagave.zodiac.control.data.playa.AssetsPlayaMapRepository] can't
 * demonstrate from a plain JVM test without touching real asset I/O.
 */
private class ControllablePlayaMapRepository : PlayaMapRepository {
    private val flow = MutableStateFlow<MapLoadResult>(MapLoadResult.Loading)
    override val loadResult: StateFlow<MapLoadResult> = flow.asStateFlow()
    override val map: Flow<PlayaMap> = emptyFlow()

    /** How many times [load] has actually been invoked. */
    var loadCallCount = 0
        private set

    /**
     * When set, [load] suspends on this deferred instead of returning
     * immediately — models an in-flight load so a test can assert a second
     * concurrent call is refused rather than piling up.
     */
    var loadGate: CompletableDeferred<Unit>? = null

    /**
     * When set, every [load] re-publishes this same `Failed(message)` — modelling
     * the real repository re-failing identically on a deterministic bad asset,
     * which the `StateFlow` conflates (value-equal, no re-emit).
     */
    var failOnLoad: String? = null

    override suspend fun load() {
        loadCallCount++
        failOnLoad?.let { flow.value = MapLoadResult.Failed(it) }
        loadGate?.await()
    }

    fun emitLoaded(map: PlayaMap) {
        flow.value = MapLoadResult.Loaded(map)
    }

    fun emitFailed(message: String) {
        flow.value = MapLoadResult.Failed(message)
    }
}

/**
 * A minimal but routable BRC map: three entrance radials (2:00/2:30/3:00) and
 * three ring arcs (Esplanade/H/K), built in LatLon by unprojecting the polar
 * grid. toCityModel re-projects them, yielding the same city the PlayaRouter
 * tests use — enough for routeTo to produce a real in-city route.
 */
private fun routableMap(): PlayaMap {
    val projection = PlayaProjection(GoldenSpike.ACTIVE)

    fun polarLatLon(
        radiusM: Double,
        bearingDeg: Double,
    ): LatLon {
        val rad = Math.toRadians(bearingDeg)
        return projection.unproject(PlayaPoint(eastM = radiusM * kotlin.math.sin(rad), northM = radiusM * kotlin.math.cos(rad)))
    }

    fun radial(
        name: String,
        clock: ClockTime,
    ): StreetLine {
        val b = clockToBearing(clock)
        return StreetLine(name, StreetKind.Radial, null, (0..10).map { polarLatLon(752.0 + it * 100.0, b) })
    }

    fun arc(
        name: String,
        radiusM: Double,
    ): StreetLine = StreetLine(name, StreetKind.Arc, null, (105..345 step 5).map { polarLatLon(radiusM, it.toDouble()) })
    return PlayaMap(
        year = "2025",
        trashFence = emptyList(),
        streetLines =
            listOf(
                radial("2:00", ClockTime(2, 0)),
                radial("2:30", ClockTime(2, 30)),
                radial("3:00", ClockTime(3, 0)),
                arc("Esplanade", 752.0),
                arc("H", 1555.0),
                arc("K", 1753.0),
            ),
        streetOutlines = emptyList(),
        cityBlocks = emptyList(),
        plazas = emptyList(),
        toilets = emptyList(),
        cpns = emptyList(),
        art = emptyList(),
    )
}

private class NoOpCockpitPreferences : CockpitPreferences {
    // Tests below build their RoutedLocationSource fixtures with FAKE as the
    // registered/initial source (matching the pre-B1 production default), not
    // NET (the corrupt-prefs recovery default post-B1, see CockpitPreferences.kt)
    // — most of these fixture registries never register a NET stub at all.
    // This stub's whole point is "sane, VM-decoupled-from-production defaults",
    // so it pins locationSource explicitly rather than following DEFAULT.
    override suspend fun read(): CockpitPrefsSnapshot = CockpitPrefsSnapshot.DEFAULT.copy(locationSource = LocationSourceType.FAKE)

    override suspend fun setLocationSource(type: LocationSourceType) = Unit

    override suspend fun setMapMode(mode: MapMode) = Unit

    override suspend fun setTiltDeg(deg: Int) = Unit

    override suspend fun setPixelsPerMeter(zoom: Double) = Unit

    override suspend fun setConcept(concept: CockpitConcept) = Unit

    override suspend fun setPassengerMode(enabled: Boolean) = Unit

    override suspend fun readBurnInConfig(): BurnInConfig = BurnInConfig()

    override suspend fun setBurnInConfig(config: BurnInConfig) = Unit

    override suspend fun readNavShareSeq(): Int = 0

    override suspend fun setNavShareSeq(seq: Int) = Unit
}

private class RecordingCockpitPreferences(
    // See NoOpCockpitPreferences: pinned to FAKE, independent of the production
    // DEFAULT, because most call sites' registry fixtures don't register NET.
    private val snapshot: CockpitPrefsSnapshot = CockpitPrefsSnapshot.DEFAULT.copy(locationSource = LocationSourceType.FAKE),
    // Simulates a persisted Lamport seq surviving a reboot: the VM seeds its
    // arbiter from readNavShareSeq() on init, so a non-zero value here proves
    // the seed actually happened (the next userSet must outbid it).
    initialNavShareSeq: Int = 0,
) : CockpitPreferences {
    val locationSources = mutableListOf<LocationSourceType>()
    val mapModes = mutableListOf<MapMode>()
    val tiltDegs = mutableListOf<Int>()
    val zooms = mutableListOf<Double>()
    val concepts = mutableListOf<CockpitConcept>()
    val navShareSeqs = mutableListOf<Int>()
    private var storedNavShareSeq = initialNavShareSeq

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

    override suspend fun setPassengerMode(enabled: Boolean) = Unit

    override suspend fun readBurnInConfig(): BurnInConfig = BurnInConfig()

    override suspend fun setBurnInConfig(config: BurnInConfig) = Unit

    override suspend fun readNavShareSeq(): Int = storedNavShareSeq

    override suspend fun setNavShareSeq(seq: Int) {
        storedNavShareSeq = seq
        navShareSeqs += seq
    }
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
