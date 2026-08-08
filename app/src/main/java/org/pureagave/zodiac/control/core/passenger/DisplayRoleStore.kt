package org.pureagave.zodiac.control.core.passenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What this particular tablet is *for* — driver cockpit or passenger display.
 *
 * Process-scoped and deliberately **not** part of `CockpitUiState`: it isn't
 * cockpit state at all. It doesn't change while driving, no concept reads it,
 * and it belongs to the device rather than the session — the same reasoning
 * that keeps burn-in configuration out of the ViewModel.
 *
 * Persisted, because a passenger tablet that forgets its role after a power
 * cycle is useless in a vehicle where nobody is going to reconfigure it.
 */
class DisplayRoleStore(
    private val scope: CoroutineScope,
    private val read: suspend () -> Boolean,
    private val write: suspend (Boolean) -> Unit,
) {
    private val _passengerMode = MutableStateFlow(false)
    val passengerMode: StateFlow<Boolean> = _passengerMode.asStateFlow()

    init {
        scope.launch { _passengerMode.value = read() }
    }

    /** Flip this tablet's role and remember it. */
    fun setPassengerMode(enabled: Boolean) {
        _passengerMode.value = enabled
        scope.launch { write(enabled) }
    }
}
