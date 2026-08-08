package org.pureagave.zodiac.control.core.passenger

/**
 * Decides which passenger card is on screen: a timed rotation that events can
 * barge into.
 *
 * Nobody touches a passenger display, so it has to be interesting without
 * input — hence the rotation. But a rotation alone makes the screen feel
 * disconnected from the ride, so the things that *just happened* (crossing a
 * street, hitting a bad patch of playa) interrupt it. The interrupt then
 * expires and the rotation resumes from where it was, rather than restarting:
 * a bump shouldn't cost you the card you were reading.
 *
 * Pure and clock-injected, because everything interesting here is about time
 * and precedence — both miserable to verify by watching a tablet.
 *
 * @param available cards that currently have data worth showing. A card with
 *   nothing behind it (no art released yet, no beacon for the odometer) is
 *   excluded by the caller rather than shown empty — a passenger display that
 *   cycles through blank cards reads as broken.
 */
class CardRotation(
    private val dwellMs: Long = DEFAULT_DWELL_MS,
    private val interruptMs: Long = DEFAULT_INTERRUPT_MS,
) {
    private var index = 0
    private var lastAdvanceMs = Long.MIN_VALUE
    private var interrupt: PassengerCard? = null
    private var interruptUntilMs = 0L

    /**
     * Raise [card] over the rotation until [interruptMs] has passed. A second
     * interrupt of the same card re-arms the timer (a run of bumps holds the
     * gauge up); a different one takes over immediately, because the newest
     * event is the one the passenger just felt.
     */
    fun interruptWith(
        card: PassengerCard,
        nowMs: Long,
    ) {
        interrupt = card
        interruptUntilMs = nowMs + interruptMs
    }

    /**
     * What to show at [nowMs], given the cards that currently have data.
     * Returns null only when nothing at all is available.
     */
    fun view(
        nowMs: Long,
        available: List<PassengerCard>,
    ): PassengerView? {
        if (available.isEmpty()) {
            interrupt = null
            return null
        }

        val pending = interrupt
        if (pending != null) {
            // An interrupt for a card with no data is dropped rather than shown
            // empty — the event still happened, but we've nothing to draw.
            if (nowMs < interruptUntilMs && pending in available) {
                return PassengerView(pending, CardReason.INTERRUPT)
            }
            interrupt = null
        }

        if (lastAdvanceMs == Long.MIN_VALUE) lastAdvanceMs = nowMs
        // A loop, not an if: a long interrupt can span several dwell periods,
        // and the rotation should land where it would have been rather than
        // creep forward one card per query.
        while (nowMs - lastAdvanceMs >= dwellMs) {
            lastAdvanceMs += dwellMs
            index++
        }
        // Reduce in place rather than letting `index` grow forever. Same
        // reasoning as the fake telemetry tick: a display that runs for months
        // shouldn't have an overflow horizon at all, and a bounded index means
        // a plain `%` is provably safe here instead of needing a floorMod whose
        // negative branch nothing could ever reach.
        index %= available.size
        return PassengerView(available[index], CardReason.ROTATION)
    }

    companion object {
        /** Long enough to read a card, short enough that the screen never feels static. */
        const val DEFAULT_DWELL_MS: Long = 25_000L

        /** How long an event holds the screen before the rotation resumes. */
        const val DEFAULT_INTERRUPT_MS: Long = 8_000L
    }
}
