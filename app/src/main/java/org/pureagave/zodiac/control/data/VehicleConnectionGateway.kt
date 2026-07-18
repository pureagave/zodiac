package org.pureagave.zodiac.control.data

import kotlinx.coroutines.flow.StateFlow
import org.pureagave.zodiac.control.core.connection.ConnectionState
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.model.VehicleCommand

interface VehicleConnectionGateway {
    val selectedTransport: StateFlow<TransportType>
    val connectionState: StateFlow<ConnectionState>

    suspend fun selectTransport(transportType: TransportType)

    suspend fun connect()

    suspend fun disconnect()

    suspend fun send(command: VehicleCommand)
}
