package org.pureagave.zodiac.control.core.sensor

/**
 * Decides when a location source should stop claiming a fix is current.
 *
 * A GPS that loses sky does not announce it. Depending on the receiver it
 * either falls silent or keeps emitting sentences marked "no fix" — which
 * [org.pureagave.zodiac.control.data.sensor.nmea.NmeaParser] correctly refuses
 * to turn into a position. Either way the source simply stops *receiving*
 * fixes, and a source that only ever publishes on success will hold its last
 * [LocationSourceState.Active] forever: the ego marker sits frozen at the last
 * known place, presented to the driver as where the vehicle is now.
 *
 * That is worse than showing nothing. `Searching` tells the cockpit the truth
 * and lets a routed source fail over; a frozen position quietly steers.
 *
 * [org.pureagave.zodiac.control.data.sensor.NetworkLocationSource] grew this
 * behaviour inline; the USB and BLE sources never had it. Sharing it here keeps
 * one definition of "stale" and makes it testable without a receiver, which
 * neither of those sources is (both talk straight to Android system services).
 */
class FixFreshness(
    private val staleMs: Long = STALE_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private var lastFixMs: Long = 0L
    private var everHadFix: Boolean = false

    /** Record that a position just arrived. */
    fun onFix() {
        lastFixMs = nowMs()
        everHadFix = true
    }

    /**
     * True once a fix has been seen and none has arrived for [staleMs].
     *
     * Deliberately false before the *first* fix: a source that has never had
     * one is already `Searching`, and reporting it stale would only churn the
     * state. It is also the state a source starts in after [reset].
     */
    fun isStale(): Boolean = everHadFix && (nowMs() - lastFixMs) > staleMs

    /**
     * The state a source should now be in, given what it currently publishes.
     *
     * Only demotes an [LocationSourceState.Active]: an `Error` carries a
     * reason worth keeping on screen, and `Disconnected`/`Searching` are
     * already honest. Returns [current] unchanged when nothing needs to
     * change, so callers can assign unconditionally without churning a
     * StateFlow.
     */
    fun demoteIfStale(current: LocationSourceState): LocationSourceState =
        if (current is LocationSourceState.Active && isStale()) LocationSourceState.Searching else current

    /** Forget the last fix — for a reconnect, so the old one can't look fresh. */
    fun reset() {
        lastFixMs = 0L
        everHadFix = false
    }

    companion object {
        /**
         * Matches `NetworkLocationSource.STALE_MS`. A 1 Hz receiver missing
         * five updates is not a dropped packet, it is a receiver that has lost
         * the sky.
         */
        const val STALE_MS: Long = 5_000L
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
