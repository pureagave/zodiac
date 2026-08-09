package org.pureagave.zodiac.beacon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TickLoopTest {
    @Test
    fun a_throwing_tick_does_not_stop_subsequent_ticks() =
        runTest {
            val invocations = mutableListOf<Long>()
            val errors = mutableListOf<Throwable>()
            val loop =
                TickLoop(
                    body = { tick ->
                        invocations += tick
                        if (tick == 3L) error("simulated tick failure")
                    },
                    onError = { errors += it },
                )
            // Infinite loop by design — must run on backgroundScope so runTest's
            // end-of-test leak check doesn't fail on an intentionally-never-completing job.
            backgroundScope.launch { loop.run(250L) }
            advanceTimeBy(2_100)
            runCurrent()

            assertTrue("expected at least 9 ticks by 2100ms, got ${invocations.size}", invocations.size >= 9)
            assertEquals(1, errors.size)
        }

    @Test
    fun cancellation_ends_the_loop_without_reporting_an_error() =
        runTest {
            val invocations = mutableListOf<Long>()
            val errors = mutableListOf<Throwable>()
            val loop =
                TickLoop(
                    body = { tick ->
                        invocations += tick
                        if (tick == 2L) throw CancellationException("stop")
                    },
                    onError = { errors += it },
                )
            backgroundScope.launch { loop.run(100L) }
            advanceTimeBy(1_000)
            runCurrent()

            // The loop must stop dead at the cancellation, not keep ticking, and
            // must never route a CancellationException through onError.
            assertEquals(listOf(0L, 1L, 2L), invocations)
            assertTrue(errors.isEmpty())
        }
}
