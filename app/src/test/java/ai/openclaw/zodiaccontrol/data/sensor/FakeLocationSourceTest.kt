package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

@OptIn(ExperimentalCoroutinesApi::class)
class FakeLocationSourceTest {
    private val projection = PlayaProjection(GoldenSpike.Y2025)

    @Test
    fun start_emits_active_within_one_tick() =
        runTest {
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)

            source.start()
            advanceTimeBy(TICK_MS + 1)

            val state = source.state.value
            assertTrue("expected Active, got $state", state is LocationSourceState.Active)
        }

    @Test
    fun stationary_mode_returns_origin() =
        runTest {
            val source =
                FakeLocationSource(
                    scope = backgroundScope,
                    pathRadiusMeters = 0.0,
                    tickMillis = TICK_MS,
                )

            source.start()
            advanceTimeBy(TICK_MS + 1)

            val fix = (source.state.value as LocationSourceState.Active).fix
            val p = projection.project(fix.location)
            assertEquals(0.0, p.eastM, ORIGIN_EPSILON)
            assertEquals(0.0, p.northM, ORIGIN_EPSILON)
        }

    @Test
    fun circular_mode_keeps_radius_within_tolerance() =
        runTest {
            val radiusM = 200.0
            val source =
                FakeLocationSource(
                    scope = backgroundScope,
                    pathRadiusMeters = radiusM,
                    periodSeconds = PATH_PERIOD_S,
                    tickMillis = TICK_MS,
                )

            source.start()
            advanceTimeBy(TICK_MS * SAMPLES)

            val fix = (source.state.value as LocationSourceState.Active).fix
            val p = projection.project(fix.location)
            val r = hypot(p.eastM, p.northM)
            assertEquals(radiusM, r, RADIUS_TOLERANCE_M)
        }

    @Test
    fun stop_returns_state_to_disconnected() =
        runTest {
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)

            source.start()
            advanceTimeBy(TICK_MS + 1)
            source.stop()

            assertEquals(LocationSourceState.Disconnected, source.state.value)
        }

    @Test
    fun start_re_entry_is_safe() =
        runTest {
            // The mutex + `if (job?.isActive == true) return` guard makes a second
            // start() a no-op while the loop is running; the state stays Active
            // and no second job is created.
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)

            source.start()
            advanceTimeBy(TICK_MS + 1)
            source.start()
            advanceTimeBy(TICK_MS + 1)

            assertTrue(source.state.value is LocationSourceState.Active)
        }

    @Test
    fun type_is_fake() {
        val source = FakeLocationSource(scope = kotlinx.coroutines.MainScope())
        assertEquals(LocationSourceType.FAKE, source.type)
    }

    private companion object {
        const val TICK_MS = 100L
        const val PATH_PERIOD_S = 10.0
        const val SAMPLES = 5L
        const val RADIUS_TOLERANCE_M = 1.0
        const val ORIGIN_EPSILON = 0.01
    }
}
