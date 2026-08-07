package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this exists to stop is a display that flashes at a driver who is
 * supposed to be looking at the road. Comparing only against the *previous*
 * announcement lets any two-way alternation re-announce forever.
 */
class AnnouncementCooldownTest {
    private class Clock(var t: Long = 0L) : () -> Long {
        override fun invoke(): Long = t
    }

    @Test
    fun a_key_never_seen_before_announces() {
        val c = AnnouncementCooldown(1_000, Clock())
        assertTrue(c.shouldAnnounce("ESPLANADE"))
    }

    @Test
    fun the_same_key_again_immediately_is_suppressed() {
        val c = AnnouncementCooldown(1_000, Clock())
        assertTrue(c.shouldAnnounce("ESPLANADE"))
        assertFalse(c.shouldAnnounce("ESPLANADE"))
    }

    @Test
    fun a_different_key_is_not_blocked_by_another_keys_cooldown() {
        val c = AnnouncementCooldown(1_000, Clock())
        assertTrue(c.shouldAnnounce("ESPLANADE"))
        assertTrue(c.shouldAnnounce("AWE"))
    }

    @Test
    fun the_key_announces_again_once_the_cooldown_expires() {
        // A genuine second pass hours later should still call out.
        val clock = Clock()
        val c = AnnouncementCooldown(1_000, clock)
        assertTrue(c.shouldAnnounce("TEMPLE"))
        clock.t = 1_001
        assertTrue(c.shouldAnnounce("TEMPLE"))
    }

    @Test
    fun the_boundary_is_inclusive_of_expiry() {
        val clock = Clock()
        val c = AnnouncementCooldown(1_000, clock)
        assertTrue(c.shouldAnnounce("TEMPLE"))
        clock.t = 999
        assertFalse(c.shouldAnnounce("TEMPLE"))
        clock.t = 1_000
        assertTrue("exactly at the window the cooldown is over", c.shouldAnnounce("TEMPLE"))
    }

    @Test
    fun a_suppressed_call_does_not_extend_the_cooldown() {
        // Otherwise a contact that stays nearest — polled every fix — would
        // keep pushing its own expiry out and never announce again.
        val clock = Clock()
        val c = AnnouncementCooldown(1_000, clock)
        assertTrue(c.shouldAnnounce("MAN"))
        for (t in 100L..900L step 100L) {
            clock.t = t
            assertFalse(c.shouldAnnounce("MAN"))
        }
        clock.t = 1_000
        assertTrue(c.shouldAnnounce("MAN"))
    }

    // -- the actual field failures --------------------------------------------

    @Test
    fun two_art_pieces_alternating_as_nearest_announce_once_each() {
        // Two pieces within PASS_RADIUS_M at similar range: which one is
        // "nearest" flips on every fix as the ego jitters. The old
        // "differs from last" test re-announced on every single flip.
        val clock = Clock()
        val c = AnnouncementCooldown(60_000, clock)
        var announcements = 0
        repeat(40) { i ->
            clock.t = i * 1_000L
            if (c.shouldAnnounce(if (i % 2 == 0) "art:A" else "art:B")) announcements++
        }
        assertEquals("one callout per piece, not one per flip", 2, announcements)
    }

    @Test
    fun a_street_label_flickering_to_null_and_back_flashes_once() {
        // At a block edge the nearest-street cue drops out and returns. The
        // caller only consults the cooldown when it has a label, so the gap
        // itself is invisible here — what matters is that the return is quiet.
        val clock = Clock()
        val c = AnnouncementCooldown(60_000, clock)
        val labels = listOf("ESPLANADE", null, "ESPLANADE", null, "ESPLANADE")
        var flashes = 0
        labels.forEachIndexed { i, label ->
            clock.t = i * 500L
            if (label != null && c.shouldAnnounce(label)) flashes++
        }
        assertEquals(1, flashes)
    }

    // -- bounded growth -------------------------------------------------------

    @Test
    fun expired_entries_are_pruned_rather_than_accumulating() {
        // A night driving past hundreds of pieces must not grow this map
        // without limit.
        val clock = Clock()
        val c = AnnouncementCooldown(1_000, clock)
        repeat(500) { i ->
            clock.t = i * 100L
            c.shouldAnnounce("art:$i")
        }
        assertTrue("only the last cooldown window is retained", c.trackedCount <= 11)
    }

    @Test
    fun keys_still_inside_the_window_survive_pruning() {
        val clock = Clock()
        val c = AnnouncementCooldown(1_000, clock)
        c.shouldAnnounce("A")
        clock.t = 500
        c.shouldAnnounce("B")
        clock.t = 1_000
        c.shouldAnnounce("C") // prunes A, keeps B
        assertTrue(c.shouldAnnounce("A"))
        assertFalse("B is only 500 ms old", c.shouldAnnounce("B"))
    }

    @Test
    fun clear_forgets_everything() {
        val c = AnnouncementCooldown(60_000, Clock())
        c.shouldAnnounce("A")
        assertFalse(c.shouldAnnounce("A"))
        c.clear()
        assertEquals(0, c.trackedCount)
        assertTrue(c.shouldAnnounce("A"))
    }

    @Test
    fun a_zero_cooldown_never_suppresses() {
        val c = AnnouncementCooldown(0, Clock())
        repeat(100) { i -> assertTrue(c.shouldAnnounce("art:$i")) }
        // Each call prunes everything before stamping, so only the key just
        // announced is ever held.
        assertEquals(1, c.trackedCount)
    }
}
