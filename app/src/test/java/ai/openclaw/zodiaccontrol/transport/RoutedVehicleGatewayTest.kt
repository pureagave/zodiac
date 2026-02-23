package ai.openclaw.zodiaccontrol.transport

import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.RoutedVehicleGateway
import ai.openclaw.zodiaccontrol.data.transport.FakeTransportAdapter
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutedVehicleGatewayTest {
    @Test
    fun routes_command_to_selected_transport() {
        runTest {
            val ble = FakeTransportAdapter(TransportType.BLE)
            val wifi = FakeTransportAdapter(TransportType.WIFI)
            ble.connect()
            wifi.connect()

            val registry = TransportRegistry(listOf(ble, wifi))
            val gateway = RoutedVehicleGateway(registry, TransportType.WIFI)

            gateway.send(VehicleCommand.SetHeading(90))

            assertTrue(wifi.commandHistory().contains(VehicleCommand.SetHeading(90)))
        }
    }
}
