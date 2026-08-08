package org.pureagave.zodiac.control.core.sensor

/**
 * Lifecycle of a [org.pureagave.zodiac.control.data.sensor.LocationSource].
 * Intentionally a closed sum — UI maps each branch to a distinct visual.
 */
sealed interface LocationSourceState {
    data object Disconnected : LocationSourceState

    data object Searching : LocationSourceState

    data class Active(val fix: GpsFix) : LocationSourceState

    /**
     * [detail] is the free-text diagnostic (goes to the rolling log); [kind]
     * is the actionable category that reaches the screen — see
     * [LocationSourceError] for why the two are separate.
     */
    data class Error(
        val detail: String,
        val kind: LocationSourceError = LocationSourceError.UNKNOWN,
    ) : LocationSourceState
}
