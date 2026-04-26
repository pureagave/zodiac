package ai.openclaw.zodiaccontrol.ui.viewmodel

import ai.openclaw.zodiaccontrol.core.connection.TransportType
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
        viewModelScope.launch {
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

        viewModelScope.launch {
            vehicleGateway.selectedTransport.collect { transport ->
                _uiState.update { it.copy(selectedTransport = transport) }
            }
        }

        viewModelScope.launch {
            vehicleGateway.connectionState.collect { connection ->
                _uiState.update {
                    it.copy(
                        connectionPhase = connection.phase,
                        connectionDetail = connection.detail,
                    )
                }
            }
        }

        viewModelScope.launch { playaMapRepository.load() }
        viewModelScope.launch {
            playaMapRepository.map.collect { map ->
                _uiState.update { it.copy(playaMap = map) }
            }
        }

        viewModelScope.launch { locationSource.start() }
        viewModelScope.launch {
            locationSource.selected.collect { type ->
                _uiState.update { it.copy(selectedLocationSource = type) }
            }
        }
        viewModelScope.launch {
            locationSource.state.collect { state ->
                _uiState.update { it.copy(locationState = state) }
            }
        }
    }

    fun selectLocationSource(type: LocationSourceType) {
        viewModelScope.launch { locationSource.select(type) }
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
        _uiState.update { it.copy(panEastM = it.panEastM + dEastM, panNorthM = it.panNorthM + dNorthM) }
    }

    fun recenterPan() {
        _uiState.update { it.copy(panEastM = 0.0, panNorthM = 0.0) }
    }

    fun setHeading(headingDeg: Int) {
        val clamped = headingDeg.coerceIn(0, 359)
        _uiState.update { it.copy(headingDeg = clamped) }
        viewModelScope.launch { vehicleGateway.send(VehicleCommand.SetHeading(clamped)) }
    }

    fun setSpeed(speedKph: Int) {
        val clamped = speedKph.coerceIn(0, 160)
        _uiState.update { it.copy(speedKph = clamped) }
        viewModelScope.launch { vehicleGateway.send(VehicleCommand.SetSpeed(clamped)) }
    }

    fun selectTransport(type: TransportType) {
        viewModelScope.launch { vehicleGateway.selectTransport(type) }
    }

    fun connectTransport() {
        viewModelScope.launch { vehicleGateway.connect() }
    }

    fun disconnectTransport() {
        viewModelScope.launch { vehicleGateway.disconnect() }
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
