package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-only LocationSource that's purely state-driven — start/stop just write
 * to its state, and tests can call [emit] to inject any state synchronously.
 * No timers or background work, so it composes safely with `runTest` /
 * `advanceUntilIdle` without fighting the virtual clock.
 */
internal class StubLocationSource(override val type: LocationSourceType) : LocationSource {
    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    override suspend fun start() {
        startCalls += 1
        _state.value = LocationSourceState.Searching
    }

    override suspend fun stop() {
        stopCalls += 1
        _state.value = LocationSourceState.Disconnected
    }

    fun emit(state: LocationSourceState) {
        _state.value = state
    }
}
