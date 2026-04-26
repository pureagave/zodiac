package ai.openclaw.zodiaccontrol.transport

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.RoutedVehicleGateway
import ai.openclaw.zodiaccontrol.data.transport.FakeTransportAdapter
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedVehicleGatewayTest {
    @Test
    fun routes_command_to_selected_transport() =
        runTest(UnconfinedTestDispatcher()) {
            val ble = FakeTransportAdapter(TransportType.BLE)
            val wifi = FakeTransportAdapter(TransportType.WIFI)
            ble.connect()
            wifi.connect()

            val registry = TransportRegistry(listOf(ble, wifi))
            val gateway = RoutedVehicleGateway(registry, TransportType.WIFI, this.backgroundScope)

            gateway.send(VehicleCommand.SetHeading(90))

            assertTrue(wifi.commandHistory().contains(VehicleCommand.SetHeading(90)))
        }

    @Test
    fun connection_state_forwards_active_adapter_state() =
        runTest(UnconfinedTestDispatcher()) {
            val ble = FakeTransportAdapter(TransportType.BLE)
            val wifi = FakeTransportAdapter(TransportType.WIFI)
            val registry = TransportRegistry(listOf(ble, wifi))
            val gateway = RoutedVehicleGateway(registry, TransportType.BLE, this.backgroundScope)

            // BLE comes up — gateway must forward CONNECTED without any extra call.
            ble.connect()
            assertEquals(ConnectionPhase.CONNECTED, gateway.connectionState.value.phase)
            assertEquals(TransportType.BLE, gateway.connectionState.value.transport)

            // Switching transport reroutes the forwarded state to WIFI's adapter.
            gateway.selectTransport(TransportType.WIFI)
            assertEquals(ConnectionPhase.DISCONNECTED, gateway.connectionState.value.phase)
            assertEquals(TransportType.WIFI, gateway.connectionState.value.transport)

            wifi.connect()
            assertEquals(ConnectionPhase.CONNECTED, gateway.connectionState.value.phase)
        }

    @Test
    fun switching_transport_while_connected_leaves_old_adapter_connected() =
        runTest(UnconfinedTestDispatcher()) {
            val ble = FakeTransportAdapter(TransportType.BLE)
            val wifi = FakeTransportAdapter(TransportType.WIFI)
            val registry = TransportRegistry(listOf(ble, wifi))
            val gateway = RoutedVehicleGateway(registry, TransportType.BLE, this.backgroundScope)

            ble.connect()
            assertEquals(ConnectionPhase.CONNECTED, gateway.connectionState.value.phase)

            // The gateway routes — it does not own adapter lifecycle. Switching
            // transports must not silently disconnect the previously-active link;
            // a user re-selecting BLE later should find it still up.
            gateway.selectTransport(TransportType.WIFI)
            assertEquals(ConnectionPhase.CONNECTED, ble.state.value.phase)
            assertEquals(ConnectionPhase.DISCONNECTED, gateway.connectionState.value.phase)

            gateway.selectTransport(TransportType.BLE)
            assertEquals(ConnectionPhase.CONNECTED, gateway.connectionState.value.phase)
        }

    @Test
    fun double_connect_is_idempotent_for_observers() =
        runTest(UnconfinedTestDispatcher()) {
            val ble = FakeTransportAdapter(TransportType.BLE)
            val registry = TransportRegistry(listOf(ble))
            val gateway = RoutedVehicleGateway(registry, TransportType.BLE, this.backgroundScope)

            gateway.connect()
            assertEquals(ConnectionPhase.CONNECTED, gateway.connectionState.value.phase)

            gateway.connect()
            // Still CONNECTED, still routed to the same transport — no flapping.
            assertEquals(ConnectionPhase.CONNECTED, gateway.connectionState.value.phase)
            assertEquals(TransportType.BLE, gateway.connectionState.value.transport)
        }

    @Test
    fun send_after_disconnect_propagates_adapter_error_state() =
        runTest(UnconfinedTestDispatcher()) {
            val ble = FakeTransportAdapter(TransportType.BLE)
            val registry = TransportRegistry(listOf(ble))
            val gateway = RoutedVehicleGateway(registry, TransportType.BLE, this.backgroundScope)

            ble.connect()
            gateway.disconnect()
            assertEquals(ConnectionPhase.DISCONNECTED, gateway.connectionState.value.phase)

            gateway.send(VehicleCommand.SetSpeed(50))

            // FakeTransportAdapter flips to ERROR on send-while-disconnected; the
            // gateway must surface that without the caller having to ask.
            assertEquals(ConnectionPhase.ERROR, gateway.connectionState.value.phase)
            assertTrue(ble.commandHistory().isEmpty())
        }
}
