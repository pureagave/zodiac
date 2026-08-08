package org.pureagave.zodiac.control.core.passenger

import org.pureagave.zodiac.control.core.navigation.NavigationCue
import org.pureagave.zodiac.control.core.navigation.streetLabel
import org.pureagave.zodiac.control.core.telemetry.Odometer

/**
 * Which passenger cards currently have something real behind them.
 *
 * The rule this encodes: **a card with no data is removed from the rotation,
 * never shown empty.** A passenger display that cycles through blank panels
 * reads as broken hardware, and the whole fleet's credibility with the people
 * riding on it is worth more than a fixed card order. It also means a tablet
 * that can't hear the beacon degrades to a smaller, still-honest rotation
 * rather than a wall of dashes.
 *
 * Pure so the availability rules are testable without a bus.
 */
fun availablePassengerCards(inputs: PassengerInputs): List<PassengerCard> =
    PassengerCard.entries.filter { card ->
        when (card) {
            // Needs a real street or clock position — "OPEN PLAYA" alone is a
            // fine thing to say, so an ego fix is enough.
            PassengerCard.WHERE -> inputs.hasFix
            // Silence is data (the beat just dropped out); no beacon is not.
            PassengerCard.AUDIO -> inputs.hasAudio
            // The Jetson feed has to be genuinely live — a demo crowd shown to
            // passengers as "souls detected" is exactly the lie this project
            // keeps refusing to tell.
            PassengerCard.SOULS -> inputs.visionLive
            PassengerCard.BUMP -> inputs.hasShock
            PassengerCard.TRIP -> inputs.odometer != null
            // Local calculation, no network, no beacon — always available.
            PassengerCard.SUN -> true
            PassengerCard.ART -> inputs.artAhead > 0
        }
    }

/** The minimum the availability rules need to know. */
data class PassengerInputs(
    val hasFix: Boolean = false,
    val hasAudio: Boolean = false,
    val visionLive: Boolean = false,
    val hasShock: Boolean = false,
    val odometer: Odometer? = null,
    val artAhead: Int = 0,
)

/**
 * The big line for the WHERE card: the street you're on, or the open playa.
 * Passengers ask "where are we" more than anything else, and the answer they
 * want is a place name, not a coordinate.
 */
fun passengerLocationLine(cue: NavigationCue): String =
    cue.streetLabel()?.uppercase()
        ?: when (cue) {
            is NavigationCue.TowardClock -> "HEADING FOR ${cue.clock.format()}"
            is NavigationCue.AwayFromClock -> "DEEP PLAYA OFF ${cue.clock.format()}"
            else -> "OPEN PLAYA"
        }
