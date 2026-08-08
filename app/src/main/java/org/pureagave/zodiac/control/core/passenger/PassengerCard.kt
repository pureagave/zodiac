package org.pureagave.zodiac.control.core.passenger

/**
 * The cards a passenger display cycles through. Ordered as the rotation runs.
 *
 * Everything here is driven by data already broadcasting on the fleet bus —
 * the beacon's NMEA channels and the Jetson's threat frames — so a passenger
 * tablet is a pure consumer: no new hardware, no new plumbing, and any number
 * of them can listen to the same multicast without the senders knowing.
 */
enum class PassengerCard {
    /** Big playa address: clock + street, updating as the vehicle drives. */
    WHERE,

    /** Oscilloscope driven by the beacon mic's `$ZAUD` level. */
    AUDIO,

    /** Count + sweep of the people the Jetson can see around the vehicle. */
    SOULS,

    /** `$ZSHK` impact peaks as a playa-washboard turbulence gauge. */
    BUMP,

    /** `$ZODO` trip + lifetime distance for this burn. */
    TRIP,

    /** Countdown to the next sunrise or sunset. */
    SUN,

    /** Notable art ahead, once the BM API releases locations. */
    ART,
}

/**
 * Why the display is showing what it's showing. Kept explicit so the screen
 * can style an interrupt differently from the steady rotation — a card that
 * barged in because something *just happened* should not look identical to one
 * that came up on a timer.
 */
enum class CardReason { ROTATION, INTERRUPT }

/** What the passenger screen should draw right now. */
data class PassengerView(
    val card: PassengerCard,
    val reason: CardReason,
)
