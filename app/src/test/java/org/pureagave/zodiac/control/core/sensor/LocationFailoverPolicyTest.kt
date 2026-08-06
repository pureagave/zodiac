package org.pureagave.zodiac.control.core.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The timing rules for abandoning the beacon and trusting it again. Pure and
 * clock-driven, so every one of these is exact rather than a sleep-and-hope.
 */
class LocationFailoverPolicyTest {
    private val drop = 3_000L
    private val recover = 10_000L

    private fun policy() = LocationFailoverPolicy(dropAfterMs = drop, recoverAfterMs = recover)

    @Test
    fun starts_on_the_beacon() {
        assertEquals(LocationRoute.PRIMARY, policy().route)
    }

    @Test
    fun a_healthy_beacon_is_never_abandoned() {
        val p = policy()
        var t = 0L
        repeat(100) {
            t += 1_000
            p.update(t, primaryHealthy = true)
        }
        assertEquals(LocationRoute.PRIMARY, p.route)
    }

    @Test
    fun a_brief_dropout_does_not_cost_a_source_swap() {
        // Switching stops and restarts sources; a flapping position source is
        // worse than a momentarily frozen one.
        val p = policy()
        p.update(0, primaryHealthy = true)
        p.update(1_000, primaryHealthy = false)
        p.update(2_500, primaryHealthy = false) // still inside the grace window
        assertEquals(LocationRoute.PRIMARY, p.route)
        p.update(3_000, primaryHealthy = true) // beacon came back
        assertEquals(LocationRoute.PRIMARY, p.route)
    }

    @Test
    fun a_sustained_outage_fails_over() {
        val p = policy()
        p.update(0, primaryHealthy = true)
        p.update(1_000, primaryHealthy = false)
        assertEquals(LocationRoute.PRIMARY, p.route)
        p.update(1_000 + drop, primaryHealthy = false)
        assertEquals(LocationRoute.FALLBACK, p.route)
    }

    @Test
    fun the_drop_boundary_is_inclusive() {
        val p = policy()
        p.update(0, primaryHealthy = false)
        p.update(drop - 1, primaryHealthy = false)
        assertEquals(LocationRoute.PRIMARY, p.route)
        p.update(drop, primaryHealthy = false)
        assertEquals(LocationRoute.FALLBACK, p.route)
    }

    @Test
    fun recovery_needs_the_beacon_to_prove_itself() {
        val p = policy()
        p.update(0, primaryHealthy = false)
        p.update(drop, primaryHealthy = false)
        assertEquals(LocationRoute.FALLBACK, p.route)

        p.update(drop + 1_000, primaryHealthy = true) // back, but unproven
        assertEquals(LocationRoute.FALLBACK, p.route)
        p.update(drop + 1_000 + recover - 1, primaryHealthy = true)
        assertEquals(LocationRoute.FALLBACK, p.route)
        p.update(drop + 1_000 + recover, primaryHealthy = true)
        assertEquals(LocationRoute.PRIMARY, p.route)
    }

    @Test
    fun a_half_alive_beacon_does_not_bounce_the_cockpit() {
        // The failure this rule exists for: a beacon flickering in and out
        // would otherwise swap the cockpit between two sources that disagree
        // slightly about where the vehicle is.
        val p = policy()
        p.update(0, primaryHealthy = false)
        p.update(drop, primaryHealthy = false)
        assertEquals(LocationRoute.FALLBACK, p.route)

        var t = drop
        repeat(20) {
            t += 2_000
            p.update(t, primaryHealthy = true) // briefly back...
            t += 2_000
            p.update(t, primaryHealthy = false) // ...and gone again
        }
        assertEquals(LocationRoute.FALLBACK, p.route)
    }

    @Test
    fun recovery_timer_restarts_when_the_beacon_lapses_again() {
        val p = policy()
        p.update(0, primaryHealthy = false)
        p.update(drop, primaryHealthy = false)

        p.update(10_000, primaryHealthy = true)
        p.update(10_000 + recover - 500, primaryHealthy = true) // nearly there
        p.update(10_000 + recover - 400, primaryHealthy = false) // lapse resets it
        p.update(10_000 + recover + 5_000, primaryHealthy = true)
        assertEquals(LocationRoute.FALLBACK, p.route) // not yet re-proven
    }

    @Test
    fun reset_returns_to_the_beacon() {
        val p = policy()
        p.update(0, primaryHealthy = false)
        p.update(drop, primaryHealthy = false)
        assertEquals(LocationRoute.FALLBACK, p.route)
        p.reset()
        assertEquals(LocationRoute.PRIMARY, p.route)
    }
}
