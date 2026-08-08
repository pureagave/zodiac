package org.pureagave.zodiac.control.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.model.CockpitMode

@OptIn(ExperimentalCoroutinesApi::class)
class FakeTelemetryRepositoryTest {
    @Test
    fun the_stream_repeats_exactly_once_per_cycle() =
        runTest {
            // The wrap has to be behaviour-preserving: emission N and N+360
            // must be identical, or "bounded" came at the cost of the sweep.
            val emitted = FakeTelemetryRepository().stream().take(CYCLE_TICKS + SAMPLE_TICKS).toList()

            repeat(SAMPLE_TICKS) { i ->
                assertEquals("emission $i vs ${i + CYCLE_TICKS}", emitted[i], emitted[i + CYCLE_TICKS])
            }
        }

    @Test
    fun three_sixty_is_the_least_period_not_merely_a_period() =
        runTest {
            // Guards the other direction: a wrap chosen too small would still
            // pass the repeat test while quietly shortening the sweep. Every
            // proper divisor of 360 must fail to be a period.
            val emitted = FakeTelemetryRepository().stream().take(CYCLE_TICKS * 2).toList()

            val properDivisors = (1 until CYCLE_TICKS).filter { CYCLE_TICKS % it == 0 }
            properDivisors.forEach { d ->
                val repeats = (0 until CYCLE_TICKS).all { i -> emitted[i] == emitted[i + d] }
                assertTrue("$d must not be a period of the sweep", !repeats)
            }
        }

    @Test
    fun every_emission_stays_inside_the_cockpit_input_ranges() =
        runTest {
            // The ViewModel validates heading 0-359 and speed 0-160; a fake
            // that drifts outside them tests nothing real.
            val emitted = FakeTelemetryRepository().stream().take(CYCLE_TICKS).toList()

            emitted.forEach {
                assertTrue("heading ${it.headingDeg}", it.headingDeg in 0..MAX_HEADING)
                assertTrue("speed ${it.speedKph}", it.speedKph in 0..MAX_SPEED)
            }
        }

    @Test
    fun all_three_modes_appear_within_one_cycle() =
        runTest {
            val modes = FakeTelemetryRepository().stream().take(CYCLE_TICKS).toList().map { it.mode }.toSet()

            assertEquals(CockpitMode.entries.toSet(), modes)
        }

    private companion object {
        const val CYCLE_TICKS = 360
        const val SAMPLE_TICKS = 8
        const val MAX_HEADING = 359
        const val MAX_SPEED = 160
    }
}
