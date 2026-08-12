package org.pureagave.zodiac.control.data.prefs

import org.pureagave.zodiac.control.burnin.BurnInConfig
import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.ui.state.CockpitUiState

/**
 * Snapshot of every user-facing setting the cockpit persists across launches.
 * Pan offset and ego/connection state are intentionally excluded — those are
 * session state, not preferences.
 */
data class CockpitPrefsSnapshot(
    val locationSource: LocationSourceType,
    val mapMode: MapMode,
    val tiltDeg: Int,
    val pixelsPerMeter: Double,
    val concept: CockpitConcept,
    /**
     * This tablet is a passenger display: it shows the passenger carousel and
     * hides the concept switcher entirely. Persisted per device (rather than
     * being a runtime toggle) because the whole point is that a rider poking
     * the screen can't wander into the driver's HUD — and because a passenger
     * tablet that forgets its role after a power cycle is useless in a vehicle
     * where nobody is going to reconfigure it.
     */
    val passengerMode: Boolean,
    /**
     * This tablet may set + broadcast the shared nav target (`$ZNAV`) — the
     * S9+ and the A54 HUD, not the Fires. Persisted per device, same reasoning
     * as [passengerMode]: it is a property of which physical tablet this is,
     * not of the drive in progress. Defaults **false** — authority is opt-in,
     * provisioned deliberately on the two tablets that should have it, rather
     * than every fresh install defaulting to "can broadcast."
     */
    val navAuthority: Boolean,
) {
    companion object {
        val DEFAULT =
            CockpitPrefsSnapshot(
                // NET (real fleet GPS / beacon), not FAKE. Recovery from a
                // corrupt prefs file lands here (see DataStoreCockpitPreferences'
                // corruptionHandler): a kiosked tablet that silently renders a
                // plausible synthetic map parked at Golden Spike is worse than
                // one that comes back on real GPS, or visibly SEARCHING /
                // failed-over to the S9+'s own GNSS. FAKE is one chip tap away.
                locationSource = LocationSourceType.NET,
                mapMode = MapMode.TOP,
                tiltDeg = CockpitUiState.DEFAULT_TILT_DEG,
                pixelsPerMeter = DEFAULT_PIXELS_PER_METER,
                concept = CockpitConcept.RADAR,
                passengerMode = false,
                navAuthority = false,
            )

        const val DEFAULT_PIXELS_PER_METER: Double = 0.18
    }
}

/**
 * One-shot read at startup + fire-and-forget setters per user action. The VM
 * does not subscribe — there's no external mutation path, so the snapshot is
 * only re-read when the process restarts.
 */
interface CockpitPreferences {
    suspend fun read(): CockpitPrefsSnapshot

    suspend fun setLocationSource(type: LocationSourceType)

    suspend fun setMapMode(mode: MapMode)

    suspend fun setTiltDeg(deg: Int)

    suspend fun setPixelsPerMeter(zoom: Double)

    suspend fun setConcept(concept: CockpitConcept)

    suspend fun setPassengerMode(enabled: Boolean)

    suspend fun setNavAuthority(enabled: Boolean)

    /**
     * Burn-in mitigation tuning, persisted as individual keys so each timeout /
     * modulation parameter is independently adjustable from the on-device tuning
     * panel and survives a relaunch. Returns coerced defaults for any key the
     * store hasn't seen yet.
     */
    suspend fun readBurnInConfig(): BurnInConfig

    suspend fun setBurnInConfig(config: BurnInConfig)

    /**
     * The last Lamport `$ZNAV` seq this device has seen or set, so a reboot
     * doesn't restart the counter at 0 and get silently outbid by every
     * follower still holding a higher seq from before the reboot. Wire state,
     * not a user preference — kept separate from [CockpitPrefsSnapshot] the
     * same way [readBurnInConfig] is. Defaults to 0 (a fresh install with no
     * history yet).
     */
    suspend fun readNavShareSeq(): Int

    suspend fun setNavShareSeq(seq: Int)
}
