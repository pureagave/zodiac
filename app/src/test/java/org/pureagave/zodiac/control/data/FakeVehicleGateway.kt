package org.pureagave.zodiac.control.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pureagave.zodiac.control.core.connection.ConnectionPhase
import org.pureagave.zodiac.control.core.connection.ConnectionState
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.model.VehicleCommand

class FakeVehicleGateway : VehicleConnectionGateway {
    private val sentCommands = mutableListOf<VehicleCommand>()

    /**
     * Opt-in failure injection (default off so existing tests are unaffected):
     * when true, [send] throws instead of recording the command, exercising the
     * ViewModel's command-error surfacing path. Flip back to false to restore
     * the normal recording behaviour mid-test.
     */
    var failSend: Boolean = false

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
        if (failSend) error("fake send failure")
        sentCommands += command
    }

    fun history(): List<VehicleCommand> = sentCommands.toList()
}
