package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.ConnectionState
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.transport.TransportAdapter
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Routes vehicle commands to whichever [TransportAdapter] is currently
 * selected. [connectionState] is derived from the active adapter's state via
 * `flatMapLatest + stateIn`, so subscribers see real-time updates without the
 * gateway having to remember to write back after every call. Same shape as
 * [ai.openclaw.zodiaccontrol.data.sensor.RoutedLocationSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutedVehicleGateway(
    private val transportRegistry: TransportRegistry,
    initialTransport: TransportType,
    scope: CoroutineScope,
) : VehicleConnectionGateway {
    private val _selectedTransport = MutableStateFlow(initialTransport)
    override val selectedTransport: StateFlow<TransportType> = _selectedTransport.asStateFlow()

    override val connectionState: StateFlow<ConnectionState> =
        _selectedTransport
            .flatMapLatest { type -> transportRegistry.adapterFor(type).state }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                ConnectionState(transport = initialTransport, phase = ConnectionPhase.DISCONNECTED),
            )

    private fun currentAdapter(): TransportAdapter = transportRegistry.adapterFor(_selectedTransport.value)

    override suspend fun selectTransport(transportType: TransportType) {
        _selectedTransport.value = transportType
    }

    override suspend fun connect() {
        currentAdapter().connect()
    }

    override suspend fun disconnect() {
        currentAdapter().disconnect()
    }

    override suspend fun send(command: VehicleCommand) {
        currentAdapter().send(command)
    }
}
