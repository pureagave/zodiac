package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedLocationSourceTest {
    @Test
    fun start_subscribes_to_initial_source() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = StubLocationSource(LocationSourceType.FAKE)
            val routed = newRouted(listOf(fake), LocationSourceType.FAKE, this.backgroundScope)

            routed.start()
            fake.emit(LocationSourceState.Active(SAMPLE_FIX))

            assertEquals(LocationSourceState.Active(SAMPLE_FIX), routed.state.value)
            assertEquals(1, fake.startCalls)
        }

    @Test
    fun select_stops_previous_and_forwards_new_state() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = StubLocationSource(LocationSourceType.FAKE)
            val system = StubLocationSource(LocationSourceType.SYSTEM)
            val routed = newRouted(listOf(fake, system), LocationSourceType.FAKE, this.backgroundScope)

            routed.start()
            routed.select(LocationSourceType.SYSTEM)
            system.emit(LocationSourceState.Active(SAMPLE_FIX))

            assertEquals(LocationSourceType.SYSTEM, routed.selected.value)
            assertEquals(1, fake.stopCalls)
            assertEquals(1, system.startCalls)
            assertEquals(LocationSourceState.Active(SAMPLE_FIX), routed.state.value)
        }

    @Test
    fun selecting_same_type_does_not_double_start() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = StubLocationSource(LocationSourceType.FAKE)
            val routed = newRouted(listOf(fake), LocationSourceType.FAKE, this.backgroundScope)

            routed.start()
            routed.select(LocationSourceType.FAKE)

            assertEquals(1, fake.startCalls)
            assertEquals(0, fake.stopCalls)
        }

    @Test
    fun concurrent_selects_serialize_through_mutex() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = StubLocationSource(LocationSourceType.FAKE)
            val system = StubLocationSource(LocationSourceType.SYSTEM)
            val ble = StubLocationSource(LocationSourceType.BLE)
            val routed = newRouted(listOf(fake, system, ble), LocationSourceType.FAKE, this.backgroundScope)

            routed.start()
            // Two concurrent select() calls — without the Mutex, both could pass
            // the `type == _selected.value` guard against the initial FAKE and
            // race on the start/stop sequencing. With the Mutex, they serialize:
            // FAKE → SYSTEM → BLE, with each transport stopped exactly once.
            val a = launch { routed.select(LocationSourceType.SYSTEM) }
            val b = launch { routed.select(LocationSourceType.BLE) }
            a.join()
            b.join()

            assertEquals(LocationSourceType.BLE, routed.selected.value)
            assertEquals(1, fake.startCalls)
            assertEquals(1, fake.stopCalls)
            assertEquals(1, system.startCalls)
            assertEquals(1, system.stopCalls)
            assertEquals(1, ble.startCalls)
        }

    @Test
    fun stop_calls_stop_on_active_source() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = StubLocationSource(LocationSourceType.FAKE)
            val routed = newRouted(listOf(fake), LocationSourceType.FAKE, this.backgroundScope)

            routed.start()
            routed.stop()

            assertEquals(1, fake.stopCalls)
        }

    private fun newRouted(
        sources: List<LocationSource>,
        initial: LocationSourceType,
        scope: CoroutineScope,
    ): RoutedLocationSource =
        RoutedLocationSource(
            registry = LocationSourceRegistry(sources),
            scope = scope,
            initialType = initial,
        )

    private companion object {
        val SAMPLE_FIX = GpsFix(LatLon(lon = -119.2, lat = 40.78))
    }
}
