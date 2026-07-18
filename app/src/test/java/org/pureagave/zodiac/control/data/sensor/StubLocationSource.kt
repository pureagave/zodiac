package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType

/**
 * Test-only LocationSource that's purely state-driven — start/stop just write
 * to its state, and tests can call [emit] to inject any state synchronously.
 * No timers or background work, so it composes safely with `runTest` /
 * `advanceUntilIdle` without fighting the virtual clock.
 */
internal class StubLocationSource(
    override val type: LocationSourceType,
    /**
     * Optional shared log so tests can assert the cross-source ordering of
     * start/stop calls (e.g. that a select() stops the old source before
     * starting the new one). Each entry is `"$type:start"` / `"$type:stop"`.
     * Defaults to null, so existing call sites that only need the per-source
     * counters keep compiling unchanged.
     */
    private val lifecycleLog: MutableList<String>? = null,
) : LocationSource {
    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    override suspend fun start() {
        startCalls += 1
        lifecycleLog?.add("$type:start")
        _state.value = LocationSourceState.Searching
    }

    override suspend fun stop() {
        stopCalls += 1
        lifecycleLog?.add("$type:stop")
        _state.value = LocationSourceState.Disconnected
    }

    fun emit(state: LocationSourceState) {
        _state.value = state
    }
}
