package ai.openclaw.zodiaccontrol.data.transport

import ai.openclaw.zodiaccontrol.core.connection.TransportType
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportRegistryTest {
    @Test
    fun adapterFor_returns_adapter_whose_type_matches() {
        val ble = FakeTransportAdapter(TransportType.BLE)
        val usb = FakeTransportAdapter(TransportType.USB)
        val registry = TransportRegistry(listOf(ble, usb))

        assertSame(ble, registry.adapterFor(TransportType.BLE))
        assertSame(usb, registry.adapterFor(TransportType.USB))
    }

    @Test
    fun adapterFor_unregistered_type_throws_illegal_state() {
        val registry = TransportRegistry(listOf(FakeTransportAdapter(TransportType.BLE)))

        val error =
            assertThrows(IllegalStateException::class.java) {
                registry.adapterFor(TransportType.WIFI)
            }
        assertTrue(error.message!!.contains("WIFI"))
    }

    @Test
    fun adapterFor_returns_first_matching_when_duplicate_types_present() {
        val firstBle = FakeTransportAdapter(TransportType.BLE)
        val secondBle = FakeTransportAdapter(TransportType.BLE)
        val registry = TransportRegistry(listOf(firstBle, secondBle))

        val resolved = registry.adapterFor(TransportType.BLE)
        assertSame(firstBle, resolved)
        assertNotEquals(secondBle, resolved)
    }

    @Test
    fun registry_resolves_every_provided_adapter() {
        val adapters = TransportType.entries.map { FakeTransportAdapter(it) }
        val registry = TransportRegistry(adapters)

        adapters.forEach { adapter ->
            assertSame(adapter, registry.adapterFor(adapter.type))
        }
    }
}
