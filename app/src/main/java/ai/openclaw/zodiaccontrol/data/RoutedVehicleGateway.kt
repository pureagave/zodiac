package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry

class RoutedVehicleGateway(
    private val transportRegistry: TransportRegistry,
    private val selectedTransport: TransportType,
) : VehicleGateway {
    override suspend fun send(command: VehicleCommand) {
        transportRegistry.adapterFor(selectedTransport).send(command)
    }
}
