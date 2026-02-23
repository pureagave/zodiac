package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.model.VehicleCommand

class FakeVehicleGateway : VehicleGateway {
    private val sentCommands = mutableListOf<VehicleCommand>()

    override suspend fun send(command: VehicleCommand) {
        sentCommands += command
    }

    fun history(): List<VehicleCommand> = sentCommands.toList()
}
