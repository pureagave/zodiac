package org.pureagave.zodiac.control.data.transport

import kotlinx.coroutines.flow.StateFlow
import org.pureagave.zodiac.control.core.connection.ConnectionState
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.model.VehicleCommand

interface TransportAdapter {
    val type: TransportType
    val state: StateFlow<ConnectionState>

    suspend fun connect()

    suspend fun disconnect()

    suspend fun send(command: VehicleCommand)
}
