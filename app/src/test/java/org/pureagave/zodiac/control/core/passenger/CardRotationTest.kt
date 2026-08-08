package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardRotationTest {
    private val all = PassengerCard.entries.toList()

    private fun rotation() = CardRotation(dwellMs = DWELL, interruptMs = INTERRUPT)

    @Test
    fun it_holds_a_card_for_the_dwell_then_advances() {
        val r = rotation()

        val first = r.view(0, all)!!.card
        assertEquals(first, r.view(DWELL - 1, all)!!.card)
        assertEquals(all[1], r.view(DWELL, all)!!.card)
    }

    @Test
    fun the_rotation_wraps() {
        val r = rotation()
        r.view(0, all)

        val afterFullLoop = r.view(DWELL * all.size, all)!!.card

        assertEquals(all[0], afterFullLoop)
    }

    @Test
    fun an_event_barges_in_and_then_gives_the_screen_back() {
        val r = rotation()
        r.view(0, all)

        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)

        val during = r.view(2_000, all)!!
        assertEquals(PassengerCard.BUMP, during.card)
        assertEquals(CardReason.INTERRUPT, during.reason)

        val after = r.view(1_000 + INTERRUPT, all)!!
        assertEquals(CardReason.ROTATION, after.reason)
    }

    @Test
    fun a_repeat_of_the_same_event_re_arms_rather_than_stacking() {
        // A run of bumps should hold the gauge up, not queue seven of them.
        val r = rotation()
        r.interruptWith(PassengerCard.BUMP, nowMs = 0)
        r.interruptWith(PassengerCard.BUMP, nowMs = INTERRUPT - 1)

        assertEquals(PassengerCard.BUMP, r.view(INTERRUPT + 1, all)!!.card)
        assertEquals(CardReason.ROTATION, r.view(INTERRUPT * 2, all)!!.reason)
    }

    @Test
    fun a_newer_event_replaces_an_older_one_immediately() {
        // The passenger just felt the newest thing; showing the previous one
        // would be describing the wrong moment.
        val r = rotation()
        r.interruptWith(PassengerCard.BUMP, nowMs = 0)

        r.interruptWith(PassengerCard.WHERE, nowMs = 1_000)

        assertEquals(PassengerCard.WHERE, r.view(1_500, all)!!.card)
    }

    @Test
    fun the_rotation_resumes_where_it_left_off_after_a_short_interrupt() {
        // A bump must not cost the passenger the card they were mid-way
        // through reading.
        val r = rotation()
        r.view(0, all)
        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)

        val resumed = r.view(1_000 + INTERRUPT, all)!!

        assertEquals(all[0], resumed.card)
    }

    @Test
    fun a_long_interrupt_lands_the_rotation_where_it_would_have_been() {
        // Not one card per query: a 3-dwell interrupt should skip 3 cards, or
        // the rotation crawls after every long event.
        val r = rotation()
        r.view(0, all)
        val longInterrupt = CardRotation(dwellMs = DWELL, interruptMs = DWELL * 3)
        longInterrupt.view(0, all)
        longInterrupt.interruptWith(PassengerCard.BUMP, nowMs = 0)

        val resumed = longInterrupt.view(DWELL * 3, all)!!

        assertEquals(all[3], resumed.card)
    }

    @Test
    fun only_cards_with_data_are_shown() {
        // Art is embargoed until BM releases 2026 locations; a passenger
        // display cycling through blank cards reads as broken.
        val r = rotation()
        val subset = listOf(PassengerCard.WHERE, PassengerCard.AUDIO)

        assertEquals(PassengerCard.WHERE, r.view(0, subset)!!.card)
        assertEquals(PassengerCard.AUDIO, r.view(DWELL, subset)!!.card)
        assertEquals(PassengerCard.WHERE, r.view(DWELL * 2, subset)!!.card)
    }

    @Test
    fun an_interrupt_for_a_card_with_no_data_is_dropped_not_shown_empty() {
        val r = rotation()
        val subset = listOf(PassengerCard.WHERE)

        r.interruptWith(PassengerCard.BUMP, nowMs = 0)

        val view = r.view(100, subset)!!
        assertEquals(PassengerCard.WHERE, view.card)
        assertEquals(CardReason.ROTATION, view.reason)
    }

    @Test
    fun nothing_available_shows_nothing_rather_than_inventing_a_card() {
        assertNull(rotation().view(0, emptyList()))
    }

    @Test
    fun the_available_set_shrinking_never_indexes_out_of_bounds() {
        // The beacon dropping mid-rotation removes cards underneath us.
        val r = rotation()
        repeat(all.size) { r.view(DWELL * it.toLong(), all) }

        val shrunk = r.view(DWELL * all.size, listOf(PassengerCard.WHERE))

        assertEquals(PassengerCard.WHERE, shrunk!!.card)
    }

    private companion object {
        const val DWELL = 25_000L
        const val INTERRUPT = 8_000L
    }
}
