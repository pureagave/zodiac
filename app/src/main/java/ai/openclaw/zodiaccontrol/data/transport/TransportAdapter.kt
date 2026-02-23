package ai.openclaw.zodiaccontrol.data.transport

import ai.openclaw.zodiaccontrol.core.connection.ConnectionState
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import kotlinx.coroutines.flow.StateFlow

interface TransportAdapter {
    val type: TransportType
    val state: StateFlow<ConnectionState>

    suspend fun connect()

    suspend fun disconnect()

    suspend fun send(command: VehicleCommand)
}
