package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.NavigationCue
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import org.pureagave.zodiac.control.core.telemetry.Odometer

class PassengerDataTest {
    @Test
    fun a_tablet_hearing_nothing_still_has_the_sun_card() {
        // Sunrise/sunset is a local calculation — no beacon, no network. It's
        // the floor that keeps a lone tablet from showing a blank screen.
        val cards = availablePassengerCards(PassengerInputs())

        assertEquals(listOf(PassengerCard.SUN), cards)
    }

    @Test
    fun cards_without_data_are_dropped_rather_than_shown_empty() {
        val cards = availablePassengerCards(PassengerInputs(hasFix = true, hasAudio = true))

        assertTrue(cards.contains(PassengerCard.WHERE))
        assertTrue(cards.contains(PassengerCard.AUDIO))
        assertFalse("no odometer -> no trip card", cards.contains(PassengerCard.TRIP))
        assertFalse("art embargoed -> no art card", cards.contains(PassengerCard.ART))
    }

    @Test
    fun souls_needs_a_genuinely_live_vision_feed() {
        // The demo threat source exists so the driver's HUD is never blank.
        // Showing that synthetic crowd to passengers as "souls detected" would
        // be inventing people, which is the one thing this project won't do.
        val demoOnly = availablePassengerCards(PassengerInputs(visionLive = false))
        assertFalse(demoOnly.contains(PassengerCard.SOULS))

        val live = availablePassengerCards(PassengerInputs(visionLive = true))
        assertTrue(live.contains(PassengerCard.SOULS))
    }

    @Test
    fun the_full_bus_lights_up_every_card() {
        val cards =
            availablePassengerCards(
                PassengerInputs(
                    hasFix = true,
                    hasAudio = true,
                    visionLive = true,
                    hasShock = true,
                    odometer = Odometer(tripMeters = 1.0, totalMeters = 2.0),
                    artAhead = 3,
                ),
            )

        assertEquals(PassengerCard.entries.toList(), cards)
    }

    @Test
    fun card_order_is_stable_regardless_of_which_data_arrives() {
        // The rotation indexes into this list; a set that reordered itself as
        // sources came and went would make the display jump around.
        val cards =
            availablePassengerCards(
                PassengerInputs(hasFix = true, visionLive = true, odometer = Odometer(1.0, 2.0)),
            )

        assertEquals(listOf(PassengerCard.WHERE, PassengerCard.SOULS, PassengerCard.TRIP, PassengerCard.SUN), cards)
    }

    @Test
    fun the_location_line_prefers_a_real_street_name() {
        assertEquals("ESPLANADE", passengerLocationLine(NavigationCue.OnArc("Esplanade", ClockTime(3, 0))))
    }

    @Test
    fun off_street_says_something_a_passenger_can_use() {
        assertEquals("OPEN PLAYA", passengerLocationLine(NavigationCue.Unknown))
        assertEquals(
            "HEADING FOR 4:30",
            passengerLocationLine(NavigationCue.TowardClock(ClockTime(4, 30), distanceM = 900.0)),
        )
        assertEquals(
            "DEEP PLAYA OFF 2:00",
            passengerLocationLine(NavigationCue.AwayFromClock(ClockTime(2, 0), distanceM = 400.0)),
        )
    }

    // --- art proximity -----------------------------------------------------

    @Test
    fun art_without_coordinates_is_not_counted_as_nearby() {
        // Real case, 2026-08-08: the BM API is serving 2026 art names with no
        // placements yet. Counting them would invent proximity we cannot know.
        val unplaced = listOf(art("Electric Dandelion", point = null), art("Nova", point = null))

        assertTrue(artNearby(unplaced, ego = PlayaPoint(0.0, 0.0)).isEmpty())
    }

    @Test
    fun theme_camps_are_not_counted_as_art() {
        val mixed = listOf(art("Nova", PlayaPoint(10.0, 0.0)), camp("Galactic Relay", PlayaPoint(20.0, 0.0)))

        val nearby = artNearby(mixed, ego = PlayaPoint(0.0, 0.0))

        assertEquals(listOf("Nova"), nearby.map { it.name })
    }

    @Test
    fun nearby_means_nearby_and_is_ordered_by_distance() {
        val pieces =
            listOf(
                art("Far", PlayaPoint(ART_NEARBY_RADIUS_M + 1.0, 0.0)),
                art("Middle", PlayaPoint(500.0, 0.0)),
                art("Close", PlayaPoint(50.0, 0.0)),
            )

        val nearby = artNearby(pieces, ego = PlayaPoint(0.0, 0.0))

        assertEquals(listOf("Close", "Middle"), nearby.map { it.name })
    }

    @Test
    fun without_a_fix_nothing_is_nearby() {
        val pieces = listOf(art("Nova", PlayaPoint(10.0, 0.0)))

        assertTrue(artNearby(pieces, ego = null).isEmpty())
    }

    private fun art(
        name: String,
        point: PlayaPoint?,
    ) = PlayaPoi(uid = name, name = name, kind = PoiKind.ART, point = point, subtitle = "")

    private fun camp(
        name: String,
        point: PlayaPoint?,
    ) = PlayaPoi(uid = name, name = name, kind = PoiKind.CAMP, point = point, subtitle = "")
}
