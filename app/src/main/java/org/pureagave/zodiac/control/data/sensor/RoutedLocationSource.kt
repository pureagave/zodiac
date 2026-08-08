package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import timber.log.Timber

/**
 * Holds the active [LocationSource] selection and reflects its [state] as a
 * single StateFlow regardless of which provider is currently picked. Same
 * shape as [org.pureagave.zodiac.control.data.RoutedVehicleGateway].
 *
 * State forwarding is derived (`flatMapLatest`), so subscribers automatically
 * follow [select] without manual job juggling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutedLocationSource(
    private val registry: LocationSourceRegistry,
    scope: CoroutineScope,
    initialType: LocationSourceType,
) {
    private val _selected = MutableStateFlow(initialType)
    val selected: StateFlow<LocationSourceType> = _selected.asStateFlow()

    private val mutex = Mutex()

    val state: StateFlow<LocationSourceState> =
        _selected
            .flatMapLatest { type -> registry.sourceFor(type).state }
            // The screen only has room for the error *category*; the free-text
            // detail is the part that says which port, which device, which
            // exception — so it goes here, once per error, not once per fix.
            .onEach { s -> if (s is LocationSourceState.Error) Timber.w("gps: %s — %s", s.kind, s.detail) }
            .stateIn(scope, SharingStarted.Eagerly, LocationSourceState.Disconnected)

    /**
     * Whether the *currently selected* source is quietly running on a backup —
     * today only [FailoverLocationSource] (the beacon backed by this tablet's
     * own GNSS) can be, and every other source reports false.
     *
     * Exposed here rather than as another ViewModel dependency: the ViewModel
     * already holds this router and is at its constructor-size limit, and
     * "which source am I really on" is a routing question anyway.
     */
    val usingFallback: StateFlow<Boolean> =
        _selected
            .flatMapLatest { type ->
                when (val source = registry.sourceFor(type)) {
                    is FailoverLocationSource -> source.usingFallback
                    else -> flowOf(false)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun start() =
        mutex.withLock {
            Timber.i("gps: start %s", _selected.value)
            registry.sourceFor(_selected.value).start()
        }

    suspend fun select(type: LocationSourceType) =
        mutex.withLock {
            if (type == _selected.value) return@withLock
            // "Which source were we on when it went wrong" is the first
            // question of any GPS postmortem, and nothing else records it.
            Timber.i("gps: select %s -> %s", _selected.value, type)
            registry.sourceFor(_selected.value).stop()
            _selected.value = type
            registry.sourceFor(type).start()
        }

    suspend fun stop() =
        mutex.withLock {
            Timber.i("gps: stop %s", _selected.value)
            registry.sourceFor(_selected.value).stop()
        }
}
