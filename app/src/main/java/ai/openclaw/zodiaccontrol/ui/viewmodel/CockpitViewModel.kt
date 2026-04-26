package ai.openclaw.zodiaccontrol.ui.viewmodel

import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.MapLoadResult
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.TelemetryRepository
import ai.openclaw.zodiaccontrol.data.VehicleConnectionGateway
import ai.openclaw.zodiaccontrol.data.playa.PlayaMapRepository
import ai.openclaw.zodiaccontrol.data.sensor.RoutedLocationSource
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CockpitViewModel(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleGateway: VehicleConnectionGateway,
    private val playaMapRepository: PlayaMapRepository,
    private val locationSource: RoutedLocationSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CockpitUiState())
    val uiState: StateFlow<CockpitUiState> = _uiState.asStateFlow()

    init {
        // One outer launch with sequential child launches: the collectors all
        // subscribe before locationSource.start() runs, so any transitions the
        // source emits land on a hot subscriber. (StateFlow still conflates
        // intermediate emissions — `Searching → Active` may be observed as a
        // single jump to `Active`. That's intentional; the UI cares about the
        // latest state, not the path.)
        viewModelScope.launch {
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
                }
            }
            launch {
                locationSource.selected.collect { type ->
                    _uiState.update { it.copy(selectedLocationSource = type) }
                }
            }
            launch {
                locationSource.state.collect { state ->
                    _uiState.update { it.copy(locationState = state) }
                }
            }
            locationSource.start()
        }
    }

    fun selectLocationSource(type: LocationSourceType) {
        viewModelScope.launch { locationSource.select(type) }
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
    }

    fun setTiltDeg(deg: Int) {
        val clamped = deg.coerceIn(CockpitUiState.MIN_TILT_DEG, CockpitUiState.MAX_TILT_DEG)
        _uiState.update { it.copy(tiltDeg = clamped) }
    }

    fun panBy(
        dEastM: Double,
        dNorthM: Double,
    ) {
        val cap = CockpitUiState.MAX_PAN_M
        _uiState.update {
            it.copy(
                panEastM = (it.panEastM + dEastM).coerceIn(-cap, cap),
                panNorthM = (it.panNorthM + dNorthM).coerceIn(-cap, cap),
            )
        }
    }

    fun recenterPan() {
        _uiState.update { it.copy(panEastM = 0.0, panNorthM = 0.0) }
    }

    fun setHeading(headingDeg: Int) {
        val clamped = headingDeg.coerceIn(CockpitUiState.MIN_HEADING_DEG, CockpitUiState.MAX_HEADING_DEG)
        _uiState.update { it.copy(headingDeg = clamped) }
        viewModelScope.launch { vehicleGateway.send(VehicleCommand.SetHeading(clamped)) }
    }

    fun setSpeed(speedKph: Int) {
        val clamped = speedKph.coerceIn(CockpitUiState.MIN_SPEED_KPH, CockpitUiState.MAX_SPEED_KPH)
        _uiState.update { it.copy(speedKph = clamped) }
        viewModelScope.launch { vehicleGateway.send(VehicleCommand.SetSpeed(clamped)) }
    }

    fun selectTransport(type: TransportType) {
        viewModelScope.launch { vehicleGateway.selectTransport(type) }
    }

    fun setTransportConnected(connected: Boolean) {
        viewModelScope.launch {
            if (connected) vehicleGateway.connect() else vehicleGateway.disconnect()
        }
    }
}

class CockpitViewModelFactory(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleGateway: VehicleConnectionGateway,
    private val playaMapRepository: PlayaMapRepository,
    private val locationSource: RoutedLocationSource,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CockpitViewModel::class.java)) {
            return CockpitViewModel(
                telemetryRepository = telemetryRepository,
                vehicleGateway = vehicleGateway,
                playaMapRepository = playaMapRepository,
                locationSource = locationSource,
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
