package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import android.location.LocationListener
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLocationSourceTest {
    @Test
    fun start_called_twice_registers_listener_only_once() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source = SystemLocationSource(managerHandle = handle)

            source.start()
            source.start()

            assertEquals(1, handle.requestCount)
            assertEquals(0, handle.removeCount)
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    @Test
    fun stop_then_start_re_registers_listener() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source = SystemLocationSource(managerHandle = handle)

            source.start()
            source.stop()
            source.start()

            assertEquals(2, handle.requestCount)
            assertEquals(1, handle.removeCount)
        }

    @Test
    fun start_without_permission_emits_error_and_does_not_register() =
        runTest {
            val handle = RecordingHandle(grantPermission = false)
            val source = SystemLocationSource(managerHandle = handle)

            source.start()

            assertEquals(0, handle.requestCount)
            assertTrue(source.state.value is LocationSourceState.Error)
        }

    @Test
    fun stop_without_prior_start_is_safe() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source = SystemLocationSource(managerHandle = handle)

            source.stop()

            assertEquals(0, handle.removeCount)
            assertSame(LocationSourceState.Disconnected, source.state.value)
        }

    private class RecordingHandle(
        private val grantPermission: Boolean,
    ) : SystemLocationManagerHandle {
        var requestCount: Int = 0
        var removeCount: Int = 0

        override fun hasFineLocationPermission(): Boolean = grantPermission

        override fun requestGpsUpdates(
            intervalMs: Long,
            distanceM: Float,
            listener: LocationListener,
        ) {
            requestCount += 1
        }

        override fun removeUpdates(listener: LocationListener) {
            removeCount += 1
        }
    }
}
