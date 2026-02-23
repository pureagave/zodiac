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
}
