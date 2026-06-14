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

    @Test
    fun start_when_request_throws_illegal_argument_emits_error_and_stays_unregistered() =
        runTest {
            // IllegalArgumentException mirrors a missing GPS_PROVIDER on Fire tablets.
            val handle =
                RecordingHandle(
                    grantPermission = true,
                    throwOnRequest = IllegalArgumentException("no GPS_PROVIDER"),
                )
            val source = SystemLocationSource(managerHandle = handle)

            source.start()

            val state = source.state.value
            assertTrue(state is LocationSourceState.Error)
            assertTrue((state as LocationSourceState.Error).detail.startsWith("GPS unavailable:"))
            assertEquals(1, handle.requestCount)

            // A failed start must leave listenerRegistered false so a later start() retries.
            handle.throwOnRequest = null
            source.start()
            assertEquals(2, handle.requestCount)
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    @Test
    fun start_when_request_throws_security_exception_emits_error() =
        runTest {
            val handle =
                RecordingHandle(
                    grantPermission = true,
                    throwOnRequest = SecurityException("revoked"),
                )
            val source = SystemLocationSource(managerHandle = handle)

            source.start()

            val state = source.state.value
            assertTrue(state is LocationSourceState.Error)
            assertTrue((state as LocationSourceState.Error).detail.startsWith("GPS unavailable:"))
            assertEquals(1, handle.requestCount)
        }

    @Test
    fun start_without_permission_does_not_call_request_and_reports_missing_permission() =
        runTest {
            val handle = RecordingHandle(grantPermission = false)
            val source = SystemLocationSource(managerHandle = handle)

            source.start()

            assertEquals(0, handle.requestCount)
            val state = source.state.value
            assertTrue(state is LocationSourceState.Error)
            assertEquals("ACCESS_FINE_LOCATION not granted", (state as LocationSourceState.Error).detail)
        }

    @Test
    fun stop_then_start_after_idempotent_start_re_registers() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source = SystemLocationSource(managerHandle = handle)

            // Double-start is idempotent: only one registration.
            source.start()
            source.start()
            assertEquals(1, handle.requestCount)

            // stop() unregisters, then a fresh start() registers again.
            source.stop()
            assertEquals(1, handle.removeCount)
            source.start()
            assertEquals(2, handle.requestCount)
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    private class RecordingHandle(
        private val grantPermission: Boolean,
        var throwOnRequest: RuntimeException? = null,
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
            throwOnRequest?.let { throw it }
        }

        override fun removeUpdates(listener: LocationListener) {
            removeCount += 1
        }
    }
}
