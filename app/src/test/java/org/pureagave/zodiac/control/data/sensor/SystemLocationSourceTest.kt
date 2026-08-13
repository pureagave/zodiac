package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceError
import org.pureagave.zodiac.control.core.sensor.LocationSourceState

@OptIn(ExperimentalCoroutinesApi::class)
class SystemLocationSourceTest {
    /**
     * Advance virtual time *and* run what lands on the final instant. The
     * watchdog is an infinite loop (see [SystemLocationSource.start]), so
     * `advanceUntilIdle` never returns — same reasoning as
     * `FailoverLocationSourceTest.settle`.
     */
    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun start_called_twice_registers_listener_only_once() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

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
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

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
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

            source.start()

            assertEquals(0, handle.requestCount)
            assertTrue(source.state.value is LocationSourceState.Error)
        }

    @Test
    fun stop_without_prior_start_is_safe() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

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
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

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
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

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
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

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
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

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

    // --- A3: staleness demotion + provider-disabled/enabled -------------------

    private fun fix(lat: Double): GpsFix = GpsFix(location = LatLon(lon = 0.0, lat = lat))

    @Test
    fun a_delivered_fix_makes_the_source_active() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source =
                SystemLocationSource(
                    managerHandle = handle,
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                )
            source.start()

            handle.deliverFix(fix(1.0))

            val state = source.state.value
            assertTrue(state is LocationSourceState.Active)
            assertEquals(1.0, (state as LocationSourceState.Active).fix.location.lat, 1e-9)
        }

    @Test
    fun a_fix_older_than_five_seconds_demotes_to_searching() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source =
                SystemLocationSource(
                    managerHandle = handle,
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                )
            source.start()
            handle.deliverFix(fix(1.0))

            // Assert the literal five-second requirement, not FixFreshness.STALE_MS.
            settle(5_000 + 2_600)

            assertSame(LocationSourceState.Searching, source.state.value)
        }

    @Test
    fun fixes_arriving_every_second_never_demote() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source =
                SystemLocationSource(
                    managerHandle = handle,
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                )
            source.start()

            // Assert after *every* iteration, not just the last: an inverted
            // isStale() demotes-then-reactivates on roughly every other
            // watchdog tick, and a final-state-only assertion can land on an
            // iteration where the demotion already got overwritten by the
            // next fix — passing even though the source spent real time
            // reporting Searching with a receiver that never stopped talking.
            repeat(12) { i ->
                handle.deliverFix(fix(1.0))
                settle(1_000)
                assertTrue(
                    "expected Active after fix #$i, was ${source.state.value}",
                    source.state.value is LocationSourceState.Active,
                )
            }
        }

    @Test
    fun a_fresh_fix_after_demotion_reactivates() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source =
                SystemLocationSource(
                    managerHandle = handle,
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                )
            source.start()
            handle.deliverFix(fix(1.0))
            settle(5_000 + 2_600)
            assertSame(LocationSourceState.Searching, source.state.value)

            handle.deliverFix(fix(2.0))

            val state = source.state.value
            assertTrue(state is LocationSourceState.Active)
            assertEquals(2.0, (state as LocationSourceState.Active).fix.location.lat, 1e-9)
        }

    @Test
    fun provider_disabled_surfaces_error_not_frozen_active() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source =
                SystemLocationSource(
                    managerHandle = handle,
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                )
            source.start()
            handle.deliverFix(fix(1.0))

            handle.disableProvider()

            val disabled = source.state.value
            assertTrue(disabled is LocationSourceState.Error)
            assertEquals(LocationSourceError.ADAPTER_UNAVAILABLE, (disabled as LocationSourceState.Error).kind)

            handle.enableProvider()
            assertSame(LocationSourceState.Searching, source.state.value)
        }

    @Test
    fun stop_cancels_the_watchdog() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source =
                SystemLocationSource(
                    managerHandle = handle,
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                )
            source.start()
            handle.deliverFix(fix(1.0))

            source.stop()
            settle(20_000)
            assertSame(LocationSourceState.Disconnected, source.state.value)

            // The assertion above alone cannot kill "forget to cancel the
            // watchdog": demoteIfStale only ever touches an Active state, and
            // stop()'s last line already sets Disconnected, so a leaked
            // watchdog has nothing to act on there — it would pass even with
            // the cancel() call deleted. Give it something to act on: a
            // location callback already in flight when removeUpdates() was
            // called can still land after stop() returns (a real Android
            // race), which is exactly the moment a live-vs-dead watchdog
            // becomes observable.
            handle.deliverFix(fix(3.0))
            settle(20_000)
            val state = source.state.value
            assertTrue(
                "a still-running watchdog demoted this late fix, so stop() did not " +
                    "actually cancel it: $state",
                state is LocationSourceState.Active,
            )
        }

    @Test
    fun stop_when_remove_updates_throws_still_reaches_disconnected() =
        runTest {
            val handle = RecordingHandle(grantPermission = true)
            val source = SystemLocationSource(managerHandle = handle, scope = backgroundScope)

            source.start()
            handle.deliverFix(fix(1.0))
            assertTrue(source.state.value is LocationSourceState.Active)

            handle.throwOnRemove = IllegalStateException("listener gone")

            // Must not propagate: a throwing platform teardown call is exactly
            // the case that must still land on Disconnected, not crash the
            // caller mid-stop().
            source.stop()

            assertSame(LocationSourceState.Disconnected, source.state.value)
            assertEquals(1, handle.removeCount)

            // listenerRegistered must have been cleared despite the throw, so
            // a following start() re-registers rather than staying idempotent
            // on a listener the platform already dropped.
            handle.throwOnRemove = null
            source.start()
            assertEquals(2, handle.requestCount)
        }

    private class RecordingHandle(
        private val grantPermission: Boolean,
        var throwOnRequest: RuntimeException? = null,
        var throwOnRemove: RuntimeException? = null,
    ) : SystemLocationManagerHandle {
        var requestCount: Int = 0
        var removeCount: Int = 0
        private var callbacks: SystemLocationManagerHandle.GpsCallbacks? = null

        override fun hasFineLocationPermission(): Boolean = grantPermission

        override fun requestGpsUpdates(
            intervalMs: Long,
            distanceM: Float,
            callbacks: SystemLocationManagerHandle.GpsCallbacks,
        ) {
            requestCount += 1
            this.callbacks = callbacks
            throwOnRequest?.let { throw it }
        }

        override fun removeUpdates() {
            removeCount += 1
            throwOnRemove?.let { throw it }
        }

        fun deliverFix(fix: GpsFix) {
            callbacks?.onFix(fix)
        }

        fun disableProvider() {
            callbacks?.onProviderDisabled()
        }

        fun enableProvider() {
            callbacks?.onProviderEnabled()
        }
    }
}
