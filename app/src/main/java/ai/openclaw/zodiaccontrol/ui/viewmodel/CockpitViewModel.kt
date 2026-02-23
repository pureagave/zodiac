package ai.openclaw.zodiaccontrol.ui.viewmodel

import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.TelemetryRepository
import ai.openclaw.zodiaccontrol.data.VehicleGateway
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
    private val vehicleGateway: VehicleGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CockpitUiState())
    val uiState: StateFlow<CockpitUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            telemetryRepository.stream().collect { telemetry ->
                _uiState.value =
                    CockpitUiState(
                        headingDeg = telemetry.headingDeg,
                        speedKph = telemetry.speedKph,
                        thermalC = telemetry.thermalC,
                        mode = telemetry.mode,
                        linkStable = telemetry.linkStable,
                    )
            }
        }
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

    fun emergencyStop() {
        _uiState.update { it.copy(speedKph = 0) }
        viewModelScope.launch { vehicleGateway.send(VehicleCommand.EmergencyStop) }
    }
}

class CockpitViewModelFactory(
    private val telemetryRepository: TelemetryRepository,
    private val vehicleGateway: VehicleGateway,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CockpitViewModel::class.java)) {
            return CockpitViewModel(
                telemetryRepository = telemetryRepository,
                vehicleGateway = vehicleGateway,
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
