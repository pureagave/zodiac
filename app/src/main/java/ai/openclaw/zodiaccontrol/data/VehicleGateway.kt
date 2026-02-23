package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.model.VehicleCommand

interface VehicleGateway {
    suspend fun send(command: VehicleCommand)
}
