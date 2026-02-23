package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.ConnectionState
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.transport.TransportAdapter
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class RoutedVehicleGateway(
    private val transportRegistry: TransportRegistry,
    initialTransport: TransportType,
) : VehicleConnectionGateway {
    private val _selectedTransport = MutableStateFlow(initialTransport)
    override val selectedTransport: StateFlow<TransportType> = _selectedTransport.asStateFlow()

    private val _connectionState =
        MutableStateFlow(
            ConnectionState(
                transport = initialTransport,
                phase = ConnectionPhase.DISCONNECTED,
            ),
        )
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private fun currentAdapter(): TransportAdapter = transportRegistry.adapterFor(_selectedTransport.value)

    override suspend fun selectTransport(transportType: TransportType) {
        _selectedTransport.value = transportType
        _connectionState.value = currentAdapter().state.value
    }

    override suspend fun connect() {
        currentAdapter().connect()
        _connectionState.value = currentAdapter().state.value
    }

    override suspend fun disconnect() {
        currentAdapter().disconnect()
        _connectionState.value = currentAdapter().state.value
    }

    override suspend fun send(command: VehicleCommand) {
        currentAdapter().send(command)
        _connectionState.update { currentAdapter().state.value }
    }
}
