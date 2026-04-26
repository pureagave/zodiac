package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.connection.ConnectionState
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import kotlinx.coroutines.flow.StateFlow

interface VehicleConnectionGateway {
    val selectedTransport: StateFlow<TransportType>
    val connectionState: StateFlow<ConnectionState>

    suspend fun selectTransport(transportType: TransportType)

    suspend fun connect()

    suspend fun disconnect()

    suspend fun send(command: VehicleCommand)
}
