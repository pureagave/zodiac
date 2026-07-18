package org.pureagave.zodiac.control.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.AUTO_RECENTER_MS
import org.pureagave.zodiac.control.core.model.FollowMode
import org.pureagave.zodiac.control.core.model.MapLoadResult
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.model.VehicleCommand
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.NavigationCue
import org.pureagave.zodiac.control.core.navigation.PlayaCityModel
import org.pureagave.zodiac.control.core.navigation.computeNavigationCue
import org.pureagave.zodiac.control.core.navigation.nextWaypoint
import org.pureagave.zodiac.control.core.navigation.routeTo
import org.pureagave.zodiac.control.core.navigation.streetLabel
import org.pureagave.zodiac.control.core.navigation.toCityModel
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import org.pureagave.zodiac.control.core.ops.addressTarget
import org.pureagave.zodiac.control.core.ops.contactsWithinRange
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.TelemetryRepository
import org.pureagave.zodiac.control.data.VehicleConnectionGateway
import org.pureagave.zodiac.control.data.playa.PlayaMapRepository
import org.pureagave.zodiac.control.data.prefs.CockpitPreferences
import org.pureagave.zodiac.control.data.sensor.FakeLocationSource
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource
import org.pureagave.zodiac.control.ui.state.CockpitUiState

class CockpitViewModel(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleGateway: VehicleConnectionGateway,
    private val playaMapRepository: PlayaMapRepository,
    private val locationSource: RoutedLocationSource,
    private val preferences: CockpitPreferences,
    private val fakeLocationSource: FakeLocationSource,
    /**
     * Offline-first discovery POIs (art + camps). A plain flow rather than the
     * whole repository so the ViewModel depends only on what it renders and
     * stays trivially testable; defaults to empty for tests / pre-wiring.
     */
    private val poisFlow: StateFlow<List<PlayaPoi>> = MutableStateFlow(emptyList()),
) : ViewModel() {
    private val _uiState = MutableStateFlow(CockpitUiState())
    val uiState: StateFlow<CockpitUiState> = _uiState.asStateFlow()

    /**
     * Built once when the BRC GIS finishes loading; used to feed every
     * subsequent [computeNavigationCue] call. Held outside [_uiState]
     * because the model is large and never has to round-trip through the
     * Composable layer — only the resulting [NavigationCue] does.
     */
    private val projection = PlayaProjection(GoldenSpike.Y2025)
    private var cityModel: PlayaCityModel? = null

    /**
     * Coroutine that flips [FollowMode.FREE] back to [FollowMode.TRACK_UP]
     * after [AUTO_RECENTER_MS] of map-gesture inactivity. Restarted on
     * every pan / pinch / rotate; cancelled when the recenter button is
     * tapped. Held as a field so consecutive gestures coalesce into one
     * pending revert rather than stacking.
     */
    private var autoRecenterJob: Job? = null

    /** Last street the ego was on, and the timer that clears its flash popup. */
    private var lastStreetLabel: String? = null
    private var streetPopupJob: Job? = null

    /** Last art whose passing was announced, and the timer that clears its callout. */
    private var lastPassingUid: String? = null
    private var passingJob: Job? = null

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
            _uiState.update {
                it.copy(
                    selectedLocationSource = saved.locationSource,
                    mapMode = saved.mapMode,
                    tiltDeg = saved.tiltDeg,
                    pixelsPerMeter = saved.pixelsPerMeter,
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
                            is MapLoadResult.Loaded -> it.copy(playaMap = result.map, mapLoadError = null)
                            is MapLoadResult.Failed -> it.copy(mapLoadError = result.message)
                        }
                    }
                    if (result is MapLoadResult.Loaded) {
                        cityModel = result.map.toCityModel(projection)
                        recomputeNavCue()
                        recomputeRoute()
                    }
                }
            }
            launch {
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
                        val gpsHeading = (state as? LocationSourceState.Active)?.fix?.headingDeg?.toInt()
                        val newHeading = gpsHeading ?: current.headingDeg
                        val newRotation =
                            if (current.followMode == FollowMode.TRACK_UP) {
                                newHeading.toDouble()
                            } else {
                                current.viewRotationDeg
                            }
                        current.copy(
                            locationState = state,
                            headingDeg = newHeading,
                            viewRotationDeg = newRotation,
                        )
                    }
                    recomputeNavCue()
                    recomputeRoute()
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
            // select() is a no-op when the saved type matches the registry's
            // initialType; otherwise it stops FAKE (never started) and starts
            // the saved source. start() is then a no-op for the saved-source
            // case (re-entry guarded) and the actual cold start for FAKE.
            locationSource.select(saved.locationSource)
            locationSource.start()
        }
    }

    fun selectLocationSource(type: LocationSourceType) {
        viewModelScope.launch {
            locationSource.select(type)
            preferences.setLocationSource(type)
        }
    }

    /**
     * Stop and restart the currently-selected location source. Used after
     * runtime permission grants — sources that emitted Error before the user
     * granted permission need to re-attempt their start path to pick up the
     * new permission state.
     */
    fun restartLocationSource() {
        viewModelScope.launch {
            locationSource.stop()
            locationSource.start()
        }
    }

    fun setMapMode(mode: MapMode) {
        _uiState.update { it.copy(mapMode = mode) }
        viewModelScope.launch { preferences.setMapMode(mode) }
    }

    fun setTiltDeg(deg: Int) {
        val clamped = deg.coerceIn(CockpitUiState.MIN_TILT_DEG, CockpitUiState.MAX_TILT_DEG)
        _uiState.update { it.copy(tiltDeg = clamped) }
        viewModelScope.launch { preferences.setTiltDeg(clamped) }
    }

    fun setPixelsPerMeter(zoom: Double) {
        val clamped = zoom.coerceIn(CockpitUiState.MIN_PIXELS_PER_METER, CockpitUiState.MAX_PIXELS_PER_METER)
        _uiState.update { it.copy(pixelsPerMeter = clamped) }
        // Pinch is a map gesture — counts as user interaction in FREE mode
        // and resets the auto-recenter timer, but doesn't itself switch
        // out of TRACK_UP (the camera still tracks ego, just at new zoom).
        if (_uiState.value.followMode == FollowMode.FREE) scheduleAutoRecenter()
        viewModelScope.launch { preferences.setPixelsPerMeter(clamped) }
    }

    /**
     * One-finger drag delta, already converted to playa metres (heading-
     * aware) by the touch input layer. Switches the cockpit into
     * [FollowMode.FREE] on first call: the camera detaches from the live
     * GPS fix and parks at an absolute world position, so the user sees
     * the ego marker slide on screen as the fix updates instead of the
     * map sliding under a stationary marker.
     */
    fun panBy(
        dEastM: Double,
        dNorthM: Double,
    ) {
        val cap = CockpitUiState.MAX_CAMERA_OFFSET_M
        _uiState.update { current ->
            val ego = current.egoFix?.location?.let(projection::project) ?: PlayaPoint(0.0, 0.0)
            val fromCamera = current.cameraOverride ?: ego
            val newCamera =
                PlayaPoint(
                    eastM = (fromCamera.eastM + dEastM).coerceIn(ego.eastM - cap, ego.eastM + cap),
                    northM = (fromCamera.northM + dNorthM).coerceIn(ego.northM - cap, ego.northM + cap),
                )
            current.copy(cameraOverride = newCamera, followMode = FollowMode.FREE)
        }
        scheduleAutoRecenter()
    }

    /**
     * Two-finger rotate delta in degrees (CW positive on screen). Spins
     * the *display* — what compass direction sits at the top of the
     * viewport — without touching the ego's physical heading. Like the
     * pan gesture, switches to FREE so the camera doesn't immediately
     * snap back to track-up on the next GPS tick.
     */
    fun nudgeViewRotation(deltaDeg: Float) {
        if (deltaDeg == 0f) return
        _uiState.update { current ->
            // Floored modulo keeps the accumulated rotation in [0, 360) so it
            // never grows unbounded and bleeds float precision over a long
            // session of rotate gestures.
            val raw = current.viewRotationDeg + deltaDeg
            val normalized = ((raw % FULL_CIRCLE_DEG) + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG
            current.copy(
                viewRotationDeg = normalized,
                followMode = FollowMode.FREE,
            )
        }
        scheduleAutoRecenter()
    }

    /**
     * Snap back to [FollowMode.TRACK_UP]: clear the camera override so the
     * camera follows the GPS fix again, sync display rotation to the
     * ego's heading so it points up, and cancel any pending auto-recenter.
     * Bound to the on-screen recenter button and to the auto-revert
     * timer when the user is idle for [AUTO_RECENTER_MS].
     */
    fun recenterPan() {
        autoRecenterJob?.cancel()
        autoRecenterJob = null
        _uiState.update { current ->
            current.copy(
                cameraOverride = null,
                viewRotationDeg = current.headingDeg.toDouble(),
                followMode = FollowMode.TRACK_UP,
            )
        }
    }

    private fun scheduleAutoRecenter() {
        autoRecenterJob?.cancel()
        autoRecenterJob =
            viewModelScope.launch {
                delay(AUTO_RECENTER_MS)
                recenterPan()
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
                viewRotationDeg =
                    if (current.followMode == FollowMode.TRACK_UP) clamped.toDouble() else current.viewRotationDeg,
            )
        }
        // Steer the synthetic GPS — the next fix will integrate position
        // along this heading, so the ego "drives" in the new direction.
        // No-op when the active source isn't FAKE.
        fakeLocationSource.setHeading(clamped.toDouble())
        recomputeNavCue()
        sendCommand(VehicleCommand.SetHeading(clamped))
    }

    fun setSpeed(speedKph: Int) {
        val clamped = speedKph.coerceIn(CockpitUiState.MIN_SPEED_KPH, CockpitUiState.MAX_SPEED_KPH)
        _uiState.update { it.copy(speedKph = clamped) }
        // Throttle the synthetic GPS. > 0 makes the fake source advance the
        // ego at every tick along the current heading; 0 parks it.
        fakeLocationSource.setSpeed(clamped.toDouble())
        sendCommand(VehicleCommand.SetSpeed(clamped))
    }

    /**
     * Dispatches a vehicle command, surfacing any transport failure as
     * [CockpitUiState.commandError] rather than silently dropping it; a
     * successful send clears a prior error. [runCatching] keeps a flaky
     * gateway from crashing the cockpit (and, unlike `catch (Exception)`,
     * doesn't need a generic-catch suppression).
     */
    private fun sendCommand(command: VehicleCommand) {
        viewModelScope.launch {
            runCatching { vehicleGateway.send(command) }
                .onSuccess { _uiState.update { it.copy(commandError = null) } }
                .onFailure { e ->
                    _uiState.update { it.copy(commandError = "Command send failed: ${e.message}") }
                }
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

    /** Set the active "drive to" preset (HOME / MAN / TEMPLE); clears a BATH lock. Session state. */
    fun setNavTarget(target: NavTarget) {
        _uiState.update { it.copy(navTarget = target, driveToBath = false, customTarget = null) }
        recomputeRoute()
    }

    /**
     * Drive to the nearest toilet bank. Session state; the target re-resolves
     * from [CockpitUiState.activeDriveTarget] as the ego moves, so it always
     * points at the closest one.
     */
    fun driveToNearestToilet() {
        _uiState.update { it.copy(driveToBath = true, customTarget = null) }
        recomputeRoute()
    }

    /** Show/hide the full-screen address-entry keypad. */
    fun setAddressEntryOpen(open: Boolean) {
        _uiState.update { it.copy(addressEntryOpen = open) }
    }

    /**
     * Drive to a typed-in city address (clock + ring letter, e.g. 2:15 & H).
     * Resolves it to a point on the polar grid and makes it the active custom
     * target so the chevron + route guide there. No-op on an unknown ring.
     */
    fun driveToAddress(
        clock: ClockTime,
        ringName: String,
    ) {
        val target = addressTarget(clock, ringName, projection) ?: return
        _uiState.update { it.copy(customTarget = target, driveToBath = false) }
        recomputeRoute()
    }

    /**
     * Debug-only: shift the FAKE source's parked position by [dEastM] east
     * and [dNorthM] north. The fake source pushes a fresh fix immediately,
     * so the ego marker and nav cue jump on the next state emission. No-op
     * when the active source isn't the fake one (silently ignored).
     */
    fun nudgeFakeGps(
        dEastM: Double,
        dNorthM: Double,
    ) {
        fakeLocationSource.nudgeManualOffset(dEastM, dNorthM)
    }

    /** Debug-only: clear the fake source's parked offset and resume circling. */
    fun resetFakeGps() {
        fakeLocationSource.resetManualOffset()
    }

    /**
     * Re-derive the nav cue from the current (ego, heading, city) triple
     * and stash it in state. Called from every collector / setter that
     * affects an input — keeps the cue cheap to read in the Composable.
     * No-ops when the city model isn't loaded yet or there's no GPS fix.
     */
    private fun recomputeNavCue() {
        val cm = cityModel
        val state = _uiState.value
        val ego = state.egoFix?.location?.let(projection::project)
        val cue =
            if (cm != null && ego != null) {
                computeNavigationCue(ego, state.headingDeg, cm)
            } else {
                NavigationCue.Unknown
            }
        if (cue != state.navCue) _uiState.update { it.copy(navCue = cue) }

        // Flash the street name whenever the ego moves onto a new street (or, out
        // a radial, crosses into a new ring). Inlined here rather than a new VM
        // method to keep the god-object's function count in check.
        val street = cue.streetLabel()
        if (street != null && street != lastStreetLabel) {
            _uiState.update { it.copy(streetPopup = street) }
            streetPopupJob?.cancel()
            streetPopupJob =
                viewModelScope.launch {
                    delay(STREET_POPUP_MS)
                    _uiState.update { it.copy(streetPopup = null) }
                }
        }
        lastStreetLabel = street

        // Passing callout: flash the nearest notable art the ego is within range
        // of (passenger flavour). New art only, and cleared on a timer.
        if (ego != null) {
            val nearest =
                contactsWithinRange(state.pois.filter { it.kind == PoiKind.ART }, ego, PASS_RADIUS_M, max = 1)
                    .firstOrNull()
            val uid = nearest?.poi?.uid
            if (uid != null && uid != lastPassingUid) {
                _uiState.update { it.copy(passingCallout = nearest.poi.name) }
                passingJob?.cancel()
                passingJob =
                    viewModelScope.launch {
                        delay(PASSING_CALLOUT_MS)
                        _uiState.update { it.copy(passingCallout = null) }
                    }
            }
            lastPassingUid = uid
        }
    }

    /**
     * Recompute the street-aware route to the active drive-to target and the
     * next corner to steer toward. Same inputs as the nav cue (fix / city model
     * / target). Clears to an empty route when any input is missing. The router
     * is cheap vector math, so this runs on every fix without a cache.
     */
    private fun recomputeRoute() {
        val cm = cityModel
        val state = _uiState.value
        val ego = state.egoFix?.location?.let(projection::project)
        val target = state.activeDriveTarget?.location?.let(projection::project)
        if (cm == null || ego == null || target == null) {
            if (state.routeWaypointsM.isNotEmpty() || state.nextWaypoint != null) {
                _uiState.update { it.copy(routeWaypointsM = emptyList(), nextWaypoint = null, entranceRadial = null) }
            }
            return
        }
        val route = routeTo(ego, target, cm)
        val next = nextWaypoint(route.waypointsM, ego)?.let(projection::unproject)
        _uiState.update {
            it.copy(routeWaypointsM = route.waypointsM, nextWaypoint = next, entranceRadial = route.entranceRadial)
        }
    }
}

/** Degrees in a full revolution — used to normalize accumulated view rotation. */
private const val FULL_CIRCLE_DEG: Double = 360.0

/** How long a street-crossing name stays flashed before it clears. */
private const val STREET_POPUP_MS: Long = 2_500L

/** Proximity (metres) at which we announce passing a notable art piece, and how long the callout stays. */
private const val PASS_RADIUS_M: Double = 120.0
private const val PASSING_CALLOUT_MS: Long = 3_000L

class CockpitViewModelFactory(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleGateway: VehicleConnectionGateway,
    private val playaMapRepository: PlayaMapRepository,
    private val locationSource: RoutedLocationSource,
    private val preferences: CockpitPreferences,
    private val fakeLocationSource: FakeLocationSource,
    private val poisFlow: StateFlow<List<PlayaPoi>> = MutableStateFlow(emptyList()),
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
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
