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
    fun parked_at_origin_until_speed_set() =
        runTest {
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)

            source.start()
            advanceTimeBy(TICK_MS * STILL_TICKS)

            val fix = (source.state.value as LocationSourceState.Active).fix
            val p = projection.project(fix.location)
            assertEquals(0.0, p.eastM, ORIGIN_EPSILON)
            assertEquals(0.0, p.northM, ORIGIN_EPSILON)
            assertEquals(0.0, fix.speedKph ?: -1.0, ORIGIN_EPSILON)
        }

    @Test
    fun heading_and_speed_drive_position_north_then_east() =
        runTest {
            // 36 km/h = 10 m/s; over four 100 ms ticks → 4 m of motion.
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)
            source.start()
            advanceTimeBy(TICK_MS + 1)

            // Drive north (heading = 0) at 36 km/h for 4 ticks.
            source.setHeading(0.0)
            source.setSpeed(36.0)
            advanceTimeBy(TICK_MS * DRIVE_TICKS)
            val northFix = (source.state.value as LocationSourceState.Active).fix
            val northPos = projection.project(northFix.location)
            // Allow ±1 m for one-tick rounding either way.
            assertEquals(EXPECTED_DRIVE_M.toDouble(), northPos.northM, DRIVE_TOLERANCE_M)
            assertEquals(0.0, northPos.eastM, ORIGIN_EPSILON)

            // Pivot east (heading = 90) and run another 4 ticks.
            source.setHeading(90.0)
            advanceTimeBy(TICK_MS * DRIVE_TICKS)
            val eastFix = (source.state.value as LocationSourceState.Active).fix
            val eastPos = projection.project(eastFix.location)
            assertEquals(EXPECTED_DRIVE_M.toDouble(), eastPos.northM, DRIVE_TOLERANCE_M)
            assertEquals(EXPECTED_DRIVE_M.toDouble(), eastPos.eastM, DRIVE_TOLERANCE_M)
        }

    @Test
    fun setSpeed_zero_parks_ego_in_place() =
        runTest {
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)
            source.start()
            advanceTimeBy(TICK_MS + 1)

            source.setSpeed(36.0)
            advanceTimeBy(TICK_MS * DRIVE_TICKS)
            val movingPos = projection.project((source.state.value as LocationSourceState.Active).fix.location)

            source.setSpeed(0.0)
            advanceTimeBy(TICK_MS * DRIVE_TICKS)
            val parkedPos = projection.project((source.state.value as LocationSourceState.Active).fix.location)

            assertEquals(movingPos.eastM, parkedPos.eastM, ORIGIN_EPSILON)
            assertEquals(movingPos.northM, parkedPos.northM, ORIGIN_EPSILON)
        }

    @Test
    fun nudgeManualOffset_teleports_position_immediately() =
        runTest {
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)
            source.start()
            advanceTimeBy(TICK_MS + 1)

            source.nudgeManualOffset(deastM = NUDGE_M.toDouble(), dnorthM = -NUDGE_M.toDouble())

            val fix = (source.state.value as LocationSourceState.Active).fix
            val p = projection.project(fix.location)
            assertEquals(NUDGE_M.toDouble(), p.eastM, NUDGE_TOLERANCE_M)
            assertEquals(-NUDGE_M.toDouble(), p.northM, NUDGE_TOLERANCE_M)
        }

    @Test
    fun resetManualOffset_zeroes_position_heading_and_speed() =
        runTest {
            val source = FakeLocationSource(scope = backgroundScope, tickMillis = TICK_MS)
            source.start()
            advanceTimeBy(TICK_MS + 1)

            source.setHeading(123.0)
            source.setSpeed(50.0)
            source.nudgeManualOffset(NUDGE_M.toDouble(), NUDGE_M.toDouble())
            advanceTimeBy(TICK_MS * DRIVE_TICKS)

            source.resetManualOffset()
            val fix = (source.state.value as LocationSourceState.Active).fix
            val p = projection.project(fix.location)
            assertEquals(0.0, p.eastM, ORIGIN_EPSILON)
            assertEquals(0.0, p.northM, ORIGIN_EPSILON)
            assertEquals(0.0, fix.headingDeg ?: -1.0, ORIGIN_EPSILON)
            assertEquals(0.0, fix.speedKph ?: -1.0, ORIGIN_EPSILON)
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
        const val STILL_TICKS = 5L
        const val DRIVE_TICKS = 4L
        const val EXPECTED_DRIVE_M = 4 // 10 m/s × 4 × 0.1 s
        const val DRIVE_TOLERANCE_M = 1.0
        const val NUDGE_M = 250
        const val NUDGE_TOLERANCE_M = 0.5
        const val ORIGIN_EPSILON = 0.01
    }
}
