package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationFailoverPolicy
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType

private const val DROP_MS = 3_000L
private const val RECOVER_MS = 10_000L

private class FakeSource(
    override val type: LocationSourceType,
) : LocationSource {
    private val flow = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = flow
    var started = false
        private set
    var stopped = false
        private set

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        stopped = true
    }

    fun active(lat: Double) {
        flow.value = LocationSourceState.Active(GpsFix(location = LatLon(lon = 0.0, lat = lat)))
    }

    fun searching() {
        flow.value = LocationSourceState.Searching
    }
}

private class Rig(val net: FakeSource, val sys: FakeSource, val failover: FailoverLocationSource)

@OptIn(ExperimentalCoroutinesApi::class)
class FailoverLocationSourceTest {
    /**
     * The failover clock is the *test* clock, so [settle] advances the policy's
     * notion of time as well as the scheduler's. Driving it from a separate
     * mutable variable looks equivalent and isn't — elapsed time then never
     * accumulates between ticks, and every timing assertion silently passes for
     * the wrong reason.
     */
    private fun TestScope.rig(armed: Boolean = true): Rig {
        val net = FakeSource(LocationSourceType.NET)
        val sys = FakeSource(LocationSourceType.SYSTEM)
        val scheduler = testScheduler
        return Rig(
            net,
            sys,
            FailoverLocationSource(
                primary = net,
                fallback = sys,
                scope = backgroundScope,
                fallbackArmed = armed,
                policy = LocationFailoverPolicy(dropAfterMs = DROP_MS, recoverAfterMs = RECOVER_MS),
                tickMs = 500,
                nowMs = { scheduler.currentTime },
            ),
        )
    }

    /**
     * Advance virtual time *and* run what lands on the final instant.
     * `advanceUntilIdle` can't be used here at all — the failover ticker is an
     * infinite flow, so idle never arrives.
     */
    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun lat(state: LocationSourceState): Double = (state as LocationSourceState.Active).fix.location.lat

    @Test
    fun presents_itself_as_the_network_source() =
        runTest {
            // Registry, selector chip and persisted preference must all keep
            // seeing NET; the saved choice is never rewritten to SYSTEM.
            assertEquals(LocationSourceType.NET, rig().failover.type)
        }

    @Test
    fun starts_both_so_the_backup_is_already_warm() =
        runTest {
            val r = rig()
            r.failover.start()
            assertTrue(r.net.started)
            assertTrue(r.sys.started)
        }

    @Test
    fun a_healthy_beacon_is_what_gets_reported() =
        runTest {
            val r = rig()
            r.failover.start()
            r.net.active(1.0)
            r.sys.active(2.0)
            settle(1_000)
            assertFalse(r.failover.usingFallback.value)
            assertEquals(1.0, lat(r.failover.state.value), 1e-9)
        }

    @Test
    fun a_dead_beacon_hands_over_to_this_tablets_gnss() =
        runTest {
            val r = rig()
            r.failover.start()
            r.net.active(1.0)
            r.sys.active(2.0)
            settle(1_000)

            r.net.searching()
            settle(DROP_MS + 1_000)

            assertTrue(r.failover.usingFallback.value)
            assertEquals(2.0, lat(r.failover.state.value), 1e-9)
        }

    @Test
    fun a_brief_beacon_stumble_does_not_swap_sources() =
        runTest {
            val r = rig()
            r.failover.start()
            r.net.active(1.0)
            r.sys.active(2.0)
            settle(1_000)

            r.net.searching()
            settle(DROP_MS - 1_500) // inside the grace window
            r.net.active(1.0)
            settle(1_000)

            assertFalse(r.failover.usingFallback.value)
            assertEquals(1.0, lat(r.failover.state.value), 1e-9)
        }

    @Test
    fun a_fire_tablet_with_no_gnss_never_hands_over() =
        runTest {
            // Falling back to a receiver that doesn't exist would trade a dead
            // beacon for nothing at all, and would raise an "on backup" badge
            // that isn't true.
            val r = rig(armed = false)
            r.failover.start()
            r.net.searching()
            r.sys.active(2.0)
            settle(60_000)
            assertFalse(r.failover.usingFallback.value)
            assertFalse(r.sys.started)
        }

    @Test
    fun does_not_hand_over_to_a_backup_that_has_no_fix_either() =
        runTest {
            val r = rig()
            r.failover.start()
            r.net.searching()
            r.sys.searching() // backup is equally lost
            settle(60_000)
            assertFalse(r.failover.usingFallback.value)
        }

    @Test
    fun returns_to_the_beacon_once_it_proves_itself() =
        runTest {
            val r = rig()
            r.failover.start()
            r.net.active(1.0)
            r.sys.active(2.0)
            settle(1_000)

            r.net.searching()
            settle(DROP_MS + 1_000)
            assertTrue(r.failover.usingFallback.value)

            r.net.active(1.0)
            settle(RECOVER_MS - 2_000)
            assertTrue(r.failover.usingFallback.value) // back, but not yet proven

            settle(3_000)
            assertFalse(r.failover.usingFallback.value)
            assertEquals(1.0, lat(r.failover.state.value), 1e-9)
        }

    @Test
    fun a_fallback_that_goes_stale_stops_being_reported() =
        runTest {
            // A3: SYSTEM (the fallback here) now demotes a frozen fix to
            // Searching on its own. The failover must react to that honestly
            // instead of continuing to report a fallback that no longer has
            // a real position — see SystemLocationSourceTest for the source
            // half of this fix.
            val r = rig()
            r.failover.start()
            r.net.active(1.0)
            r.sys.active(2.0)
            settle(1_000)

            r.net.searching()
            settle(DROP_MS + 1_000)
            assertTrue(r.failover.usingFallback.value)

            r.sys.searching()
            settle(1_500)

            assertFalse(r.failover.usingFallback.value)
            assertEquals(LocationSourceState.Searching, r.failover.state.value)
        }

    @Test
    fun stop_stops_both_and_clears_the_badge() =
        runTest {
            val r = rig()
            r.failover.start()
            r.net.searching()
            r.sys.active(2.0)
            settle(DROP_MS + 1_000)
            assertTrue(r.failover.usingFallback.value)

            r.failover.stop()
            assertTrue(r.net.stopped)
            assertTrue(r.sys.stopped)
            assertFalse(r.failover.usingFallback.value)
        }

    @Test
    fun stopped_source_never_re_presents_a_frozen_fallback_as_net() =
        runTest {
            // FakeSource.stop() sets `stopped = true` but its flow keeps
            // whatever value it last held — exactly what an unguarded
            // concrete stop() (e.g. a throwing removeUpdates()) would look
            // like from here. If FailoverLocationSource relied on primary/
            // fallback reaching Disconnected on their own, the free-running
            // combine ticker would re-arm on the very next tick after stop()
            // and keep reporting the fallback's last fix as a live NET
            // position.
            val r = rig()
            r.failover.start()
            r.net.searching()
            r.sys.active(2.0)
            settle(DROP_MS + 1_000)
            assertTrue(r.failover.usingFallback.value)

            r.failover.stop()
            // Advance well past stop(), through several more ticker beats,
            // instead of asserting only at the instant of stop().
            settle(DROP_MS + 2_000)

            assertEquals(LocationSourceState.Disconnected, r.failover.state.value)
            assertFalse(r.failover.usingFallback.value)
        }
}
