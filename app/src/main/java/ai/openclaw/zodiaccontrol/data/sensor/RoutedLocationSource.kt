package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the active [LocationSource] selection and reflects its [state] as a
 * single StateFlow regardless of which provider is currently picked. Same
 * shape as [ai.openclaw.zodiaccontrol.data.RoutedVehicleGateway].
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
            .stateIn(scope, SharingStarted.Eagerly, LocationSourceState.Disconnected)

    suspend fun start() =
        mutex.withLock {
            registry.sourceFor(_selected.value).start()
        }

    suspend fun select(type: LocationSourceType) =
        mutex.withLock {
            if (type == _selected.value) return@withLock
            registry.sourceFor(_selected.value).stop()
            _selected.value = type
            registry.sourceFor(type).start()
        }

    suspend fun stop() =
        mutex.withLock {
            registry.sourceFor(_selected.value).stop()
        }
}
