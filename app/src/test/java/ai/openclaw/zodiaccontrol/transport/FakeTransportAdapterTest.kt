package ai.openclaw.zodiaccontrol.transport

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.transport.FakeTransportAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTransportAdapterTest {
    @Test
    fun connect_then_send_records_command() {
        runTest {
            val adapter = FakeTransportAdapter(TransportType.BLE)

            adapter.connect()
            adapter.send(VehicleCommand.SetSpeed(33))

            assertEquals(ConnectionPhase.CONNECTED, adapter.state.value.phase)
            assertTrue(adapter.commandHistory().contains(VehicleCommand.SetSpeed(33)))
        }
    }

    @Test
    fun connect_settles_in_connected_phase() =
        runTest {
            val adapter = FakeTransportAdapter(TransportType.USB)

            adapter.connect()

            assertEquals(ConnectionPhase.CONNECTED, adapter.state.value.phase)
            assertEquals(TransportType.USB, adapter.state.value.transport)
        }

    @Test
    fun disconnect_returns_state_to_disconnected() =
        runTest {
            val adapter = FakeTransportAdapter(TransportType.WIFI)

            adapter.connect()
            adapter.disconnect()

            assertEquals(ConnectionPhase.DISCONNECTED, adapter.state.value.phase)
        }

    @Test
    fun send_while_disconnected_flips_to_error_and_records_nothing() =
        runTest {
            val adapter = FakeTransportAdapter(TransportType.BLE)

            adapter.send(VehicleCommand.SetHeading(90))

            assertEquals(ConnectionPhase.ERROR, adapter.state.value.phase)
            assertEquals("Send attempted while disconnected", adapter.state.value.detail)
            assertTrue(adapter.commandHistory().isEmpty())
        }

    @Test
    fun command_history_preserves_send_order_after_connect() =
        runTest {
            val adapter = FakeTransportAdapter(TransportType.BLE)

            adapter.connect()
            adapter.send(VehicleCommand.SetHeading(120))
            adapter.send(VehicleCommand.SetSpeed(55))
            adapter.send(VehicleCommand.SetHeading(15))

            assertEquals(
                listOf(
                    VehicleCommand.SetHeading(120),
                    VehicleCommand.SetSpeed(55),
                    VehicleCommand.SetHeading(15),
                ),
                adapter.commandHistory(),
            )
        }
}
