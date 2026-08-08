package org.pureagave.zodiac.control.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this prevents is a `! BRAKE !` that strobes. The edge box decides
 * `collision` per frame from a noisy size estimate at ~9 fps, so the flag
 * chatters while the actual hazard — someone walking into the vehicle's path —
 * is continuous. A driver reads a flashing alert as a glitch, and a glitch gets
 * ignored.
 */
class AlarmLatchTest {
    private class Clock(var t: Long = 0L) : () -> Long {
        override fun invoke(): Long = t
    }

    /** A latch plus the clock driving it, so a test can move time explicitly. */
    private fun latchAt(holdMs: Long): Pair<AlarmLatch, Clock> {
        val clock = Clock()
        return AlarmLatch(holdMs = holdMs, nowMs = clock) to clock
    }

    @Test
    fun an_alarm_raises_the_instant_it_is_triggered() {
        // Never delay the warning to confirm it — that costs exactly the
        // moment the warning exists for.
        assertTrue(latchAt(1_000).first.update(active = true))
    }

    @Test
    fun nothing_is_raised_before_anything_ever_triggers() {
        val (latch, clock) = latchAt(1_000)
        assertFalse(
            run {
                clock.t = 0
                latch.update(active = false)
            },
        )
        assertFalse(
            run {
                clock.t = 10_000
                latch.update(active = false)
            },
        )
    }

    @Test
    fun the_alarm_holds_through_a_gap_in_the_trigger() {
        val (latch, clock) = latchAt(1_000)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        assertTrue(
            "still within the hold",
            run {
                clock.t = 500
                latch.update(active = false)
            },
        )
    }

    @Test
    fun the_alarm_clears_once_the_hold_expires() {
        val (latch, clock) = latchAt(1_000)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        assertFalse(
            run {
                clock.t = 1_000
                latch.update(active = false)
            },
        )
    }

    @Test
    fun the_hold_boundary_is_exclusive() {
        val (latch, clock) = latchAt(1_000)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        assertTrue(
            run {
                clock.t = 999
                latch.update(active = false)
            },
        )
        assertFalse(
            run {
                clock.t = 1_000
                latch.update(active = false)
            },
        )
    }

    @Test
    fun each_new_trigger_restarts_the_hold() {
        val (latch, clock) = latchAt(1_000)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        run {
            clock.t = 900
            latch.update(active = true)
        }
        assertTrue(
            "the second trigger pushed the expiry out",
            run {
                clock.t = 1_500
                latch.update(active = false)
            },
        )
        assertFalse(
            run {
                clock.t = 1_900
                latch.update(active = false)
            },
        )
    }

    // -- the actual field failure ---------------------------------------------

    @Test
    fun a_flag_chattering_at_frame_rate_reads_as_one_continuous_alarm() {
        // Ten seconds of an 8 fps feed where the edge box flips the flag on
        // alternate frames. Without the latch this is 40 on/off transitions in
        // the driver's eyeline; with it, one alarm.
        val (latch, clock) = latchAt(1_500)
        var transitions = 0
        var previous = false
        for (frame in 0 until 80) {
            clock.t = frame * 125L
            val latched = latch.update(active = frame % 2 == 0)
            if (latched != previous) transitions++
            previous = latched
        }
        assertEquals("one rise, and it never drops while the hazard chatters", 1, transitions)
        assertTrue(previous)
    }

    @Test
    fun a_hazard_that_truly_clears_does_eventually_stop_warning() {
        // The latch must not become a stuck alarm — that is the same
        // credibility failure in the other direction.
        val (latch, clock) = latchAt(1_500)
        for (frame in 0 until 20) {
            clock.t = frame * 125L
            latch.update(active = frame % 2 == 0)
        }
        assertFalse(
            run {
                clock.t = 20 * 125L + 1_500
                latch.update(active = false)
            },
        )
    }

    @Test
    fun a_single_stray_frame_still_warns_for_the_full_hold() {
        // One frame of collision at 8 fps is 125 ms — far too brief to see.
        // Holding it is the point: a real contact glimpsed once still reaches
        // the driver.
        val (latch, clock) = latchAt(1_500)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        assertTrue(
            run {
                clock.t = 1_400
                latch.update(active = false)
            },
        )
        assertFalse(
            run {
                clock.t = 1_500
                latch.update(active = false)
            },
        )
    }

    // -- scheduling the clear -------------------------------------------------

    @Test
    fun the_caller_is_told_how_long_to_wait_before_rechecking() {
        // Nothing else will wake the cockpit: when the threat feed goes quiet
        // there are no further frames to re-evaluate on, so the clear has to be
        // scheduled.
        val (latch, clock) = latchAt(1_000)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        assertEquals(
            1_000L,
            run {
                clock.t = 0
                latch.holdRemainingMs()
            },
        )
        assertEquals(
            400L,
            run {
                clock.t = 600
                latch.holdRemainingMs()
            },
        )
    }

    @Test
    fun the_remaining_hold_never_goes_negative() {
        val (latch, clock) = latchAt(1_000)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        assertEquals(
            0L,
            run {
                clock.t = 5_000
                latch.holdRemainingMs()
            },
        )
    }

    @Test
    fun a_latch_that_never_fired_asks_for_no_wakeup() {
        assertEquals(0L, latchAt(1_000).first.holdRemainingMs())
    }

    @Test
    fun the_scheduled_wait_is_exactly_when_it_clears() {
        // If these disagree the cockpit either wakes early and re-sleeps, or
        // wakes late and leaves a stale warning on screen.
        val (latch, clock) = latchAt(1_000)
        run {
            clock.t = 250
            latch.update(active = true)
        }
        val wake =
            250 +
                run {
                    clock.t = 250
                    latch.holdRemainingMs()
                }
        assertTrue(
            run {
                clock.t = wake - 1
                latch.update(active = false)
            },
        )
        assertFalse(
            run {
                clock.t = wake
                latch.update(active = false)
            },
        )
    }

    // -- housekeeping ---------------------------------------------------------

    @Test
    fun reset_drops_the_alarm_immediately() {
        val (latch, clock) = latchAt(10_000)
        run {
            clock.t = 0
            latch.update(active = true)
        }
        latch.reset()
        assertFalse(
            run {
                clock.t = 1
                latch.update(active = false)
            },
        )
        assertEquals(
            0L,
            run {
                clock.t = 1
                latch.holdRemainingMs()
            },
        )
    }

    @Test
    fun a_zero_hold_latches_nothing() {
        val (latch, clock) = latchAt(0)
        assertTrue(
            run {
                clock.t = 0
                latch.update(active = true)
            },
        )
        assertFalse(
            run {
                clock.t = 0
                latch.update(active = false)
            },
        )
    }

    @Test
    fun the_default_hold_bridges_several_frames_at_the_edge_boxs_rate() {
        // ~8 fps means 125 ms per frame; the default must cover a run of
        // dropped frames, not just one.
        assertTrue(AlarmLatch.DEFAULT_HOLD_MS >= 8 * 125L)
    }
}
