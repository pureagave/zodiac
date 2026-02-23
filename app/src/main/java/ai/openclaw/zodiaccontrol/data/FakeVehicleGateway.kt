package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.ConnectionState
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeVehicleGateway : VehicleConnectionGateway {
    private val sentCommands = mutableListOf<VehicleCommand>()

    private val _selectedTransport = MutableStateFlow(TransportType.BLE)
    override val selectedTransport: StateFlow<TransportType> = _selectedTransport.asStateFlow()

    private val _connectionState =
        MutableStateFlow(
            ConnectionState(
                transport = TransportType.BLE,
                phase = ConnectionPhase.DISCONNECTED,
            ),
        )
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override suspend fun selectTransport(transportType: TransportType) {
        _selectedTransport.value = transportType
        _connectionState.value =
            ConnectionState(
                transport = transportType,
                phase = ConnectionPhase.DISCONNECTED,
            )
    }

    override suspend fun connect() {
        _connectionState.value =
            ConnectionState(
                transport = _selectedTransport.value,
                phase = ConnectionPhase.CONNECTED,
            )
    }

    override suspend fun disconnect() {
        _connectionState.value =
            ConnectionState(
                transport = _selectedTransport.value,
                phase = ConnectionPhase.DISCONNECTED,
            )
    }

    override suspend fun send(command: VehicleCommand) {
        sentCommands += command
    }

    fun history(): List<VehicleCommand> = sentCommands.toList()
}
