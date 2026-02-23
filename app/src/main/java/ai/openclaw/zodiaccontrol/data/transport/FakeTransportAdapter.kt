package ai.openclaw.zodiaccontrol.data.transport

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.ConnectionState
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTransportAdapter(
    override val type: TransportType,
) : TransportAdapter {
    private val _state =
        MutableStateFlow(
            ConnectionState(
                transport = type,
                phase = ConnectionPhase.DISCONNECTED,
            ),
        )

    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val sentCommands = mutableListOf<VehicleCommand>()

    override suspend fun connect() {
        _state.value = ConnectionState(transport = type, phase = ConnectionPhase.CONNECTING)
        delay(120)
        _state.value = ConnectionState(transport = type, phase = ConnectionPhase.CONNECTED)
    }

    override suspend fun disconnect() {
        _state.value = ConnectionState(transport = type, phase = ConnectionPhase.DISCONNECTED)
    }

    override suspend fun send(command: VehicleCommand) {
        if (_state.value.phase != ConnectionPhase.CONNECTED) {
            _state.value =
                ConnectionState(
                    transport = type,
                    phase = ConnectionPhase.ERROR,
                    detail = "Send attempted while disconnected",
                )
            return
        }
        sentCommands += command
    }

    fun commandHistory(): List<VehicleCommand> = sentCommands.toList()
}
