package org.pureagave.zodiac.control.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.FollowMode
import org.pureagave.zodiac.control.core.model.MapLoadResult
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.model.VehicleCommand
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.ops.NavShareArbiter
import org.pureagave.zodiac.control.core.ops.NavShareMessage
import org.pureagave.zodiac.control.core.ops.NavSharePayload
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.core.telemetry.BeaconSensors
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.VisionFeed
import org.pureagave.zodiac.control.core.vision.driverAlerts
import org.pureagave.zodiac.control.data.TelemetryRepository
import org.pureagave.zodiac.control.data.VehicleConnectionGateway
import org.pureagave.zodiac.control.data.nav.NavSharePublisher
import org.pureagave.zodiac.control.data.nav.NoOpNavSharePublisher
import org.pureagave.zodiac.control.data.playa.PlayaMapRepository
import org.pureagave.zodiac.control.data.prefs.CockpitPreferences
import org.pureagave.zodiac.control.data.sensor.FakeLocationSource
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource
import org.pureagave.zodiac.control.ui.state.CockpitUiState
import timber.log.Timber

/**
 * The cockpit's single state orchestrator: it subscribes to every source the
 * vehicle has (telemetry, the routed GPS source, the playa map, discovery
 * POIs, the Jetson threat feed, the beacon's low-rate sensor channels), folds
 * them into one immutable [CockpitUiState], and dispatches commands back out
 * through the gateway.
 *
 * **One StateFlow, not many.** All three concepts render from the same value,
 * so a concept switch is purely presentational and can never show a different
 * world than its neighbour. The cost is that a per-frame field change copies
 * the whole state — see A5 in `tasks/open.md` for the split that would fix it.
 *
 * **Three delegates, one facade.** The mechanism for three cohesive groups of
 * operations lives in plain classes this ViewModel constructs and forwards to:
 * [MapCameraController] (mode / tilt / zoom / pan / rotate / auto-recenter),
 * [NavigationController] (drive-to target, route, nav cue, announcements) and
 * [GpsController] (source selection, lifecycle, synthetic-GPS steering). They
 * share this class's [_uiState] and `viewModelScope`, so there is still
 * exactly one state holder and one coroutine lifetime; what changed is that
 * each group's fields and timers now live next to the code that uses them
 * instead of as loose members of a god-object. Everything the UI calls is
 * still a method on this class.
 *
 * Input validation lives here rather than in the UI (heading 0–359, speed
 * 0–160), so every entry point — chip, gesture, synthetic GPS — is bounded by
 * the same rules.
 *
 * Dependencies arrive as plain flows wherever possible rather than whole
 * repositories: the ViewModel then depends only on what it renders and stays
 * constructible in a test without any of the real sources.
 */
class CockpitViewModel(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleGateway: VehicleConnectionGateway,
    private val playaMapRepository: PlayaMapRepository,
    private val locationSource: RoutedLocationSource,
    private val preferences: CockpitPreferences,
    fakeLocationSource: FakeLocationSource,
    /**
     * Offline-first discovery POIs (art + camps). A plain flow rather than the
     * whole repository so the ViewModel depends only on what it renders and
     * stays trivially testable; defaults to empty for tests / pre-wiring.
     */
    private val poisFlow: StateFlow<List<PlayaPoi>> = MutableStateFlow(emptyList()),
    /**
     * Thermal contacts for the DRIVER HUD (routed threat source → real network
     * feed or fake demo). Same plain-flow pattern as [poisFlow]; empty for
     * tests / pre-wiring.
     */
    private val threatsFlow: StateFlow<List<DriverThreat>> = MutableStateFlow(emptyList()),
    /**
     * Health of [threatsFlow] — LIVE / DEMO / ABSENT (see [VisionFeed]).
     * Defaults to ABSENT: a ViewModel with no vision source wired up
     * genuinely has no vision, and must not render as an all-clear.
     */
    private val visionFeedFlow: StateFlow<VisionFeed> = MutableStateFlow(VisionFeed.ABSENT),
    /**
     * Low-rate Sensor Hub channels (ambient light, health, odometer, shock) from
     * the [NetworkLocationSource]. One bundled flow keeps the ViewModel to a
     * single new dependency; empty default for tests / pre-wiring.
     */
    private val beaconSensors: StateFlow<BeaconSensors> = MutableStateFlow(BeaconSensors()),
    /**
     * Whether *this device* currently holds nav authority — automatic and
     * device-derived: production wires in a constant flow of
     * [org.pureagave.zodiac.control.burnin.BurnInDeviceProfile.visualModulationSupported]
     * (true on the OLED Samsungs, false on the LCD Fires), computed once at
     * `MainActivity` startup — there is no runtime toggle. Defaults **true**
     * here so ~10 existing `CockpitViewModelTest` scenarios that call
     * `setNavTarget`/`driveToAddress` without wiring this flow explicitly
     * don't silently no-op; the follower-gating tests inject `false` directly.
     * Mirrors the `demoEnabled`-style comment on `RoutedThreatSource`.
     */
    navAuthorityFlow: StateFlow<Boolean> = MutableStateFlow(true),
    /** Inbound `$ZNAV` messages from [org.pureagave.zodiac.control.data.nav.NavShareReceiver]; null default for tests/pre-wiring. */
    private val navShareFlow: StateFlow<NavShareMessage?> = MutableStateFlow(null),
    /** Where `$ZNAV` is transmitted; [NoOpNavSharePublisher] for tests/followers that must never touch the network. */
    navPublisher: NavSharePublisher = NoOpNavSharePublisher,
    /** This device's stable short id ([org.pureagave.zodiac.control.core.ops.NavShareProtocol.sanitizeSrc] applied to `Settings.Secure.ANDROID_ID`) — the arbiter's tie-break key. */
    navSrcId: String = "0",
) : ViewModel() {
    private val _uiState = MutableStateFlow(CockpitUiState())
    val uiState: StateFlow<CockpitUiState> = _uiState.asStateFlow()

    /** Shared by the camera and navigation delegates — one projection per cockpit. */
    private val projection = PlayaProjection(GoldenSpike.ACTIVE)

    private val camera = MapCameraController(_uiState, viewModelScope, preferences, projection)
    private val navigation = NavigationController(_uiState, viewModelScope, projection)
    private val gps = GpsController(locationSource, fakeLocationSource, preferences, viewModelScope)
    private val navShareArbiter = NavShareArbiter(mySrc = navSrcId)
    private val navShare =
        NavShareController(
            navigation = navigation,
            publisher = navPublisher,
            isAuthority = { _uiState.value.navAuthority },
            arbiter = navShareArbiter,
            persistSeq = { seq -> preferences.setNavShareSeq(seq) },
            scope = viewModelScope,
        )

    /** Last shock count folded into the UI, and the timer that clears its alert banner. */
    private var lastShockCount: Long = 0
    private var shockAlertJob: Job? = null

    init {
        // One outer launch with sequential child launches: persisted prefs are
        // applied first so the UI doesn't flash defaults; then collectors all
        // subscribe before locationSource.start() runs, so any transitions the
        // source emits land on a hot subscriber. (StateFlow still conflates
        // intermediate emissions — `Searching → Active` may be observed as a
        // single jump to `Active`. That's intentional; the UI cares about the
        // latest state, not the path.)
        viewModelScope.launch {
            val saved = preferences.read()
            // The Lamport counter must survive a reboot (decision 3): without
            // this, a rebooted authority mints seq=1 and every follower still
            // holding a higher persisted seq rejects it forever.
            navShareArbiter.seed(preferences.readNavShareSeq())
            _uiState.update {
                it.copy(
                    selectedLocationSource = saved.locationSource,
                    camera =
                        it.camera.copy(
                            mapMode = saved.mapMode,
                            tiltDeg = saved.tiltDeg,
                            pixelsPerMeter = saved.pixelsPerMeter,
                        ),
                    concept = saved.concept,
                )
            }
            launch {
                telemetryRepository.stream().collect { telemetry ->
                    // Heading and speed are user-owned (touch / debug chips) — do not let
                    // telemetry overwrite them. Telemetry still drives thermal / mode /
                    // link state for the cockpit-status feel.
                    _uiState.update {
                        it.copy(
                            thermalC = telemetry.thermalC,
                            mode = telemetry.mode,
                            linkStable = telemetry.linkStable,
                        )
                    }
                }
            }
            launch {
                vehicleGateway.selectedTransport.collect { transport ->
                    _uiState.update { it.copy(selectedTransport = transport) }
                }
            }
            launch {
                vehicleGateway.connectionState.collect { connection ->
                    _uiState.update {
                        it.copy(
                            connectionPhase = connection.phase,
                            connectionDetail = connection.detail,
                        )
                    }
                }
            }
            launch { playaMapRepository.load() }
            launch {
                playaMapRepository.loadResult.collect { result ->
                    _uiState.update {
                        when (result) {
                            is MapLoadResult.Loading -> it.copy(mapLoadError = null)
                            is MapLoadResult.Loaded ->
                                it.copy(playaMap = result.map, mapLoadError = null, mapLoadRetrying = false)
                            is MapLoadResult.Failed ->
                                it.copy(mapLoadError = result.message, mapLoadRetrying = false)
                        }
                    }
                    if (result is MapLoadResult.Loaded) navigation.onMapLoaded(result.map)
                }
            }
            viewModelScope.launch {
                locationSource.selected.collect { type ->
                    _uiState.update { it.copy(selectedLocationSource = type) }
                }
            }
            launch {
                locationSource.state.collect { state ->
                    _uiState.update { current ->
                        // Heading and view rotation are physical properties
                        // of the moving ego — fold the GPS-reported heading
                        // into state on every fix. In TRACK_UP we also keep
                        // the display rotation aligned so the ego stays
                        // pointing up. In FREE the user has rotated the
                        // display manually; leave their rotation alone.
                        val activeFix = (state as? LocationSourceState.Active)?.fix
                        val gpsHeading = activeFix?.headingDeg?.toInt()
                        val newHeading = gpsHeading ?: current.headingDeg
                        // Speed is folded the same way heading is. Without this
                        // the collision BRAKE gate read the debug chip and saw
                        // 0 on every real drive. Hold the last known speed when
                        // a sentence omits it (GGA carries no speed, and phones
                        // interleave GGA with RMC every epoch) so the gate does
                        // not drop out on alternate fixes.
                        val newGpsSpeed = activeFix?.speedKph ?: current.gpsSpeedKph
                        val newRotation =
                            if (current.followMode == FollowMode.TRACK_UP) {
                                newHeading.toDouble()
                            } else {
                                current.viewRotationDeg
                            }
                        current.copy(
                            locationState = state,
                            headingDeg = newHeading,
                            gpsSpeedKph = newGpsSpeed,
                            camera = current.camera.copy(viewRotationDeg = newRotation),
                        )
                    }
                    navigation.recomputeNavCue()
                    navigation.recomputeRoute()
                }
            }
            launch {
                // Offline-first discovery POIs (art + camps). Serves the disk
                // cache instantly and re-emits after each background sync; the
                // cockpit renders them as RADAR contacts / MAP markers.
                poisFlow.collect { pois ->
                    _uiState.update { it.copy(pois = pois) }
                }
            }
            launch {
                // Thermal contacts for the DRIVER HUD (network feed or fake demo).
                threatsFlow.collect { threats ->
                    _uiState.update { it.copy(threats = threats) }
                }
            }
            launch {
                // Braking advice: sector- and speed-gated, then latched so a
                // collision flag chattering at frame rate can't strobe the
                // warning. See brakeAdvisory — the timer to clear it lives in
                // the operator, since a passed hazard produces no more frames.
                // Re-evaluate when EITHER the threats or the speed change.
                // `driverAlerts` only recomputes per upstream frame, and
                // `threatsFlow` is a conflating StateFlow, so a vehicle
                // accelerating past the brake threshold while the contact list
                // is unchanged would never have re-run the gate. Combining with
                // the speed makes crossing the threshold its own trigger.
                combine(
                    threatsFlow,
                    _uiState.map { it.effectiveSpeedKph }.distinctUntilChanged(),
                ) { threats, _ -> threats }
                    .driverAlerts({ _uiState.value.effectiveSpeedKph.toFloat() })
                    .collect { alerts ->
                        _uiState.update { it.copy(brakeAdvised = alerts.brake, checkRear = alerts.checkRear) }
                    }
            }
            launch {
                // Tri-state health of the threat feed — see VisionFeed's doc for
                // why this can't just be folded into "threats is empty".
                visionFeedFlow.collect { feed ->
                    _uiState.update { it.copy(visionFeed = feed) }
                }
            }
            launch {
                // This device's nav authority — automatic and device-derived
                // (see CockpitUiState.navAuthority's kdoc); folded into
                // CockpitUiState because the ViewModel needs it synchronously
                // to gate every send.
                navAuthorityFlow.collect { authority ->
                    _uiState.update { it.copy(navAuthority = authority) }
                }
            }
            launch {
                // Adoption path for a shared nav target arriving from another
                // authority tablet. NavShareController.onReceived is the only
                // thing that touches the arbiter/NavigationController here —
                // it never calls publisher.publish (the no-echo guarantee).
                navShareFlow.collect { msg -> msg?.let(navShare::onReceived) }
            }
            launch {
                // Low-rate Sensor Hub telemetry: ambient lux (auto-dim), health +
                // odometer (footer), and shock events (transient alert per new bump).
                beaconSensors.collect { sensors ->
                    _uiState.update {
                        it.copy(
                            ambientLux = sensors.ambientLight?.lux,
                            beaconHealth = sensors.beaconHealth,
                            odometer = sensors.odometer,
                        )
                    }
                    if (sensors.shockCount > lastShockCount) {
                        lastShockCount = sensors.shockCount
                        _uiState.update { it.copy(shockAlertG = sensors.lastShockG) }
                        shockAlertJob?.cancel()
                        shockAlertJob =
                            viewModelScope.launch {
                                delay(SHOCK_ALERT_MS)
                                _uiState.update { it.copy(shockAlertG = null) }
                            }
                    }
                }
            }
            gps.applySavedSource(saved.locationSource)
        }
    }

    // --- GPS (delegated to GpsController) ---

    fun selectLocationSource(type: LocationSourceType) = gps.selectLocationSource(type)

    fun restartLocationSource() = gps.restartLocationSource()

    fun nudgeFakeGps(
        dEastM: Double,
        dNorthM: Double,
    ) = gps.nudgeFakeGps(dEastM, dNorthM)

    fun resetFakeGps() = gps.resetFakeGps()

    // --- Map camera (delegated to MapCameraController) ---

    fun setMapMode(mode: MapMode) = camera.setMapMode(mode)

    fun setTiltDeg(deg: Int) = camera.setTiltDeg(deg)

    fun setPixelsPerMeter(zoom: Double) = camera.setPixelsPerMeter(zoom)

    fun panBy(
        dEastM: Double,
        dNorthM: Double,
    ) = camera.panBy(dEastM, dNorthM)

    fun nudgeViewRotation(deltaDeg: Float) = camera.nudgeViewRotation(deltaDeg)

    fun recenterPan() = camera.recenterPan()

    // --- Drive-to / navigation (delegated to NavigationController via
    // NavShareController, which is the fleet-share authority gate — see
    // NavShareController.userSet's kdoc. A follower's tap is a genuine no-op:
    // no state change, no publish.) ---

    fun setNavTarget(target: NavTarget) {
        navShare.userSet(NavSharePayload.Preset(target))
    }

    fun driveToNearestToilet() {
        navShare.userSet(NavSharePayload.Bath)
    }

    /**
     * Opening the address keypad is itself an entry point a follower must not
     * get: closing (dismissing the overlay) is always allowed, but a
     * follower's tap to *open* it stays a no-op, same central gate as the
     * other three entry points.
     */
    fun setAddressEntryOpen(open: Boolean) {
        if (open && !_uiState.value.navAuthority) return
        navigation.setAddressEntryOpen(open)
    }

    fun driveToAddress(
        clock: ClockTime,
        ringName: String,
    ): Boolean = navShare.userSet(NavSharePayload.Address(clock, ringName))

    // --- Owned here: map load, vehicle commands, transport, concept ---

    /**
     * Re-attempts the bundled BRC map load without an Activity restart —
     * the only way back from [MapLoadResult.Failed] (corrupt asset, a
     * renamed GeoJSON field, ...) short of killing the app. [PlayaMapRepository.load]
     * is one-shot per call and idempotent once [MapLoadResult.Loaded], so
     * calling it again after a failure is exactly a retry.
     *
     * Guarded by [CockpitUiState.mapLoadRetrying] so a driver mashing the
     * on-screen RETRY chip can't pile up concurrent parses. The flag is cleared
     * when `load()` returns — **not** only by the `loadResult` collector above:
     * a re-attempt that fails with the *same* message publishes a value-equal
     * `Failed`, which the `StateFlow` conflates and never re-emits, so relying on
     * the collector alone wedged the chip on "RETRYING…" forever and blocked
     * every future retry (a blank map with no recovery but an app restart).
     */
    fun retryMapLoad() {
        if (_uiState.value.mapLoadRetrying) return
        Timber.w("map: retry requested (previous error: %s)", _uiState.value.mapLoadError)
        _uiState.update { it.copy(mapLoadRetrying = true) }
        viewModelScope.launch {
            playaMapRepository.load()
            // load() has returned, so this attempt is finished whatever it
            // published; reopen the guard here so a conflated identical-Failed
            // can't leave it stuck true.
            _uiState.update { it.copy(mapLoadRetrying = false) }
        }
    }

    fun setHeading(headingDeg: Int) {
        val clamped = headingDeg.coerceIn(CockpitUiState.MIN_HEADING_DEG, CockpitUiState.MAX_HEADING_DEG)
        _uiState.update { current ->
            current.copy(
                headingDeg = clamped,
                // In TRACK_UP we keep the display rotation glued to the ego's
                // heading; in FREE the user has explicitly rotated the
                // display, so leave that alone even if heading changes.
                camera =
                    if (current.camera.isFree) {
                        current.camera
                    } else {
                        current.camera.copy(viewRotationDeg = clamped.toDouble())
                    },
            )
        }
        gps.steerFakeGps(clamped)
        navigation.recomputeNavCue()
        sendCommand(VehicleCommand.SetHeading(clamped))
    }

    fun setSpeed(speedKph: Int) {
        val clamped = speedKph.coerceIn(CockpitUiState.MIN_SPEED_KPH, CockpitUiState.MAX_SPEED_KPH)
        _uiState.update { it.copy(speedKph = clamped) }
        gps.throttleFakeGps(clamped)
        sendCommand(VehicleCommand.SetSpeed(clamped))
    }

    /**
     * Fire-and-forget dispatch of a vehicle command. Every transport is fake
     * (see CLAUDE.md — real vehicle data never traverses the gateway), so a
     * send does not fail in production; [runCatching] is a cheap IO-boundary
     * guard against a hypothetical throw, logged rather than surfaced in UI
     * state so a dropped command is at least observable in the on-device log
     * viewer (the Fire's only readout).
     */
    private fun sendCommand(command: VehicleCommand) {
        viewModelScope.launch {
            runCatching { vehicleGateway.send(command) }
                .onFailure { e -> Timber.w(e, "vehicle command send failed: %s", command) }
        }
    }

    fun selectTransport(type: TransportType) {
        viewModelScope.launch { vehicleGateway.selectTransport(type) }
    }

    fun setTransportConnected(connected: Boolean) {
        viewModelScope.launch {
            if (connected) vehicleGateway.connect() else vehicleGateway.disconnect()
        }
    }

    fun cycleConcept() {
        val next = _uiState.value.concept.next()
        _uiState.update { it.copy(concept = next) }
        viewModelScope.launch { preferences.setConcept(next) }
    }
}

/** How long a shock/impact alert banner stays before it clears. */
private const val SHOCK_ALERT_MS: Long = 2_000L

class CockpitViewModelFactory(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleGateway: VehicleConnectionGateway,
    private val playaMapRepository: PlayaMapRepository,
    private val locationSource: RoutedLocationSource,
    private val preferences: CockpitPreferences,
    private val fakeLocationSource: FakeLocationSource,
    private val poisFlow: StateFlow<List<PlayaPoi>> = MutableStateFlow(emptyList()),
    private val threatsFlow: StateFlow<List<DriverThreat>> = MutableStateFlow(emptyList()),
    private val visionFeedFlow: StateFlow<VisionFeed> = MutableStateFlow(VisionFeed.ABSENT),
    private val beaconSensors: StateFlow<BeaconSensors> = MutableStateFlow(BeaconSensors()),
    private val navAuthorityFlow: StateFlow<Boolean> = MutableStateFlow(true),
    private val navShareFlow: StateFlow<NavShareMessage?> = MutableStateFlow(null),
    private val navPublisher: NavSharePublisher = NoOpNavSharePublisher,
    private val navSrcId: String = "0",
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CockpitViewModel::class.java)) {
            return CockpitViewModel(
                telemetryRepository = telemetryRepository,
                vehicleGateway = vehicleGateway,
                playaMapRepository = playaMapRepository,
                locationSource = locationSource,
                preferences = preferences,
                fakeLocationSource = fakeLocationSource,
                poisFlow = poisFlow,
                threatsFlow = threatsFlow,
                visionFeedFlow = visionFeedFlow,
                beaconSensors = beaconSensors,
                navAuthorityFlow = navAuthorityFlow,
                navShareFlow = navShareFlow,
                navPublisher = navPublisher,
                navSrcId = navSrcId,
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
