package org.pureagave.zodiac.control.core.ops

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether this particular tablet may set + broadcast the shared nav target
 * (`$ZNAV`) — the S9+ and the A54 HUD, not the two Fires (spec R3).
 *
 * Process-scoped and deliberately **not** part of `CockpitUiState`'s
 * persistence path — mirrors
 * [org.pureagave.zodiac.control.core.passenger.DisplayRoleStore] line for
 * line: it is a property of the device, not the session, and a tablet that
 * forgot its authority after a power cycle would need to be re-toggled every
 * single night. (The *current value* still flows into `CockpitUiState` for
 * the ViewModel's send-gating and the UI's affordance — see decision 7 in the
 * plan for the tension that's worth noting, not fixing.)
 */
class NavAuthorityStore(
    private val scope: CoroutineScope,
    private val read: suspend () -> Boolean,
    private val write: suspend (Boolean) -> Unit,
) {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        scope.launch { _enabled.value = read() }
    }

    /** Flip this tablet's nav authority and remember it. */
    fun setEnabled(authority: Boolean) {
        _enabled.value = authority
        scope.launch { write(authority) }
    }
}
