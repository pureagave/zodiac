package org.pureagave.zodiac.control.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.prefs.CockpitPreferences
import org.pureagave.zodiac.control.data.sensor.FakeLocationSource
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource

/**
 * Commands to the GPS layer: which source is live (FAKE / SYSTEM / BLE / USB /
 * NET), its lifecycle, and the debug steering of the synthetic source.
 *
 * Split out of [CockpitViewModel] (which owns it and forwards to it) so the
 * ViewModel no longer holds two location sources for two unrelated reasons.
 * The *observation* side — the `state` / `selected` / `usingFallback` flows —
 * deliberately stays in the ViewModel, because folding a fix into
 * [org.pureagave.zodiac.control.ui.state.CockpitUiState] also moves the map
 * camera and re-derives navigation; that's orchestration, not GPS. This class
 * is the write side only.
 *
 * [scope] is the owning ViewModel's `viewModelScope`.
 */
internal class GpsController(
    private val locationSource: RoutedLocationSource,
    private val fakeLocationSource: FakeLocationSource,
    private val preferences: CockpitPreferences,
    private val scope: CoroutineScope,
) {
    /**
     * Cold start with the persisted choice.
     *
     * `select()` is a no-op when the saved type matches the registry's
     * `initialType`; otherwise it stops FAKE (never started) and starts the
     * saved source. `start()` is then a no-op for the saved-source case
     * (re-entry guarded — true for FAKE, SYSTEM, and NET as of AUDIT-2026-08-09
     * C5; BLE/USB do not yet guard re-entry) and the actual cold start for FAKE.
     */
    suspend fun applySavedSource(type: LocationSourceType) {
        locationSource.select(type)
        locationSource.start()
    }

    fun selectLocationSource(type: LocationSourceType) {
        scope.launch {
            locationSource.select(type)
            preferences.setLocationSource(type)
        }
    }

    /**
     * Stop and restart the currently-selected location source. Used after
     * runtime permission grants — sources that emitted Error before the user
     * granted permission need to re-attempt their start path to pick up the
     * new permission state.
     */
    fun restartLocationSource() {
        scope.launch {
            locationSource.stop()
            locationSource.start()
        }
    }

    /**
     * Steer the synthetic GPS — the next fix will integrate position along
     * this heading, so the ego "drives" in the new direction. No-op when the
     * active source isn't FAKE.
     */
    fun steerFakeGps(headingDeg: Int) {
        fakeLocationSource.setHeading(headingDeg.toDouble())
    }

    /**
     * Throttle the synthetic GPS. > 0 makes the fake source advance the ego at
     * every tick along the current heading; 0 parks it.
     */
    fun throttleFakeGps(speedKph: Int) {
        fakeLocationSource.setSpeed(speedKph.toDouble())
    }

    /**
     * Debug-only: shift the FAKE source's parked position by [dEastM] east
     * and [dNorthM] north. The fake source pushes a fresh fix immediately,
     * so the ego marker and nav cue jump on the next state emission. No-op
     * when the active source isn't the fake one (silently ignored).
     */
    fun nudgeFakeGps(
        dEastM: Double,
        dNorthM: Double,
    ) {
        fakeLocationSource.nudgeManualOffset(dEastM, dNorthM)
    }

    /** Debug-only: clear the fake source's parked offset and resume circling. */
    fun resetFakeGps() {
        fakeLocationSource.resetManualOffset()
    }
}
