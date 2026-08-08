package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.pureagave.zodiac.control.core.sensor.LocationFailoverPolicy
import org.pureagave.zodiac.control.core.sensor.LocationRoute
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import timber.log.Timber

/**
 * Wraps the beacon in this tablet's own GNSS as an automatic backup.
 *
 * The beacon stays the fleet's single source of truth — every tablet agreeing
 * on one position matters more than any individual tablet's accuracy, and the
 * roof antenna sees more sky than a tablet buried in a dashboard. This exists
 * only so that a dead beacon degrades the hero display to *slightly worse
 * navigation* rather than to *no navigation*.
 *
 * It presents itself as [primary]'s own [type], so from the registry, the
 * selector chip and the persisted preference it simply *is* the network
 * source. Nothing upstream has to know failover exists, and the user's saved
 * choice is never quietly rewritten to SYSTEM.
 *
 * **Both sources run at once.** That's the crux: [RoutedLocationSource.select]
 * stops whichever source it switches away from, so a failover built on top of
 * routing could never see the beacon come back — it would have switched off the
 * very thing it was waiting for. Keeping both alive also means the fallback
 * already holds a fix at the moment it's needed, instead of starting a cold
 * acquisition exactly when navigation has just been lost.
 *
 * @param fallbackArmed false on hardware with no GNSS (the Fire tablets), where
 *   falling back would trade a dead beacon for nothing at all. The wrapper then
 *   behaves as a transparent pass-through.
 */
class FailoverLocationSource(
    private val primary: LocationSource,
    private val fallback: LocationSource,
    private val scope: CoroutineScope,
    private val fallbackArmed: Boolean = true,
    private val policy: LocationFailoverPolicy = LocationFailoverPolicy(),
    private val tickMs: Long = TICK_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) : LocationSource {
    override val type: LocationSourceType = primary.type

    private val _usingFallback = MutableStateFlow(false)

    /**
     * Whether the cockpit is currently steering off this tablet's own GNSS
     * instead of the beacon. Surfaced to the UI because a driver navigating
     * from the backup needs to know that's what they're looking at — silently
     * substituting a different position source would be the wrong kind of
     * clever.
     */
    val usingFallback: StateFlow<Boolean> = _usingFallback.asStateFlow()

    private val ticker =
        flow {
            while (true) {
                emit(Unit)
                delay(tickMs)
            }
        }

    override val state: StateFlow<LocationSourceState> =
        combine(primary.state, fallback.state, ticker) { primaryState, fallbackState, _ ->
            val primaryHealthy = primaryState is LocationSourceState.Active
            val intent = policy.update(nowMs(), primaryHealthy)
            // Intent is one thing; a fallback that has no fix of its own is
            // another. Swapping to a source that also can't say where we are
            // buys nothing and just makes the readout jump around, so the
            // fallback only takes over when it can actually answer.
            val fallbackUsable = fallbackArmed && fallbackState is LocationSourceState.Active
            val useFallback = intent == LocationRoute.FALLBACK && fallbackUsable
            if (useFallback != _usingFallback.value) {
                // Deliberately invisible on screen (Rob: "we just want things
                // to keep working"), so the log is the only record that the
                // beacon dropped and this tablet's own GNSS took over.
                Timber.i(
                    "gps: %s (primary %s, fallback %s)",
                    if (useFallback) "NET -> SYSTEM failover" else "back on NET",
                    primaryState::class.simpleName,
                    fallbackState::class.simpleName,
                )
            }
            _usingFallback.value = useFallback
            if (useFallback) fallbackState else primaryState
        }.stateIn(scope, SharingStarted.Eagerly, LocationSourceState.Disconnected)

    override suspend fun start() {
        policy.reset()
        primary.start()
        // Kept warm deliberately: a backup that needs a cold GNSS acquisition
        // at the moment the beacon dies isn't a backup. Costs battery, which is
        // an easy trade on a tablet wired to vehicle power.
        if (fallbackArmed) fallback.start()
    }

    override suspend fun stop() {
        primary.stop()
        if (fallbackArmed) fallback.stop()
        _usingFallback.value = false
        policy.reset()
    }

    companion object {
        /**
         * The policy is time-based, so it needs a pulse even when neither
         * source is emitting — a beacon that has gone silent produces no state
         * changes at all, which is precisely the case we must detect.
         */
        const val TICK_MS: Long = 1_000L
        const val NANOS_PER_MS: Long = 1_000_000L
    }
}
