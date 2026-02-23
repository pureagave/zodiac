package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.connection.ConnectionState
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import kotlinx.coroutines.flow.StateFlow

interface VehicleConnectionGateway : VehicleGateway {
    val selectedTransport: StateFlow<TransportType>
    val connectionState: StateFlow<ConnectionState>

    suspend fun selectTransport(transportType: TransportType)

    suspend fun connect()

    suspend fun disconnect()
}
