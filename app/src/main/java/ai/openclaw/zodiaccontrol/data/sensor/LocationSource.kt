package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract every GPS provider implements. Mirrors the
 * [ai.openclaw.zodiaccontrol.data.transport.TransportAdapter] shape so the
 * cockpit can route between fake/system/BLE/USB the same way it routes vehicle
 * commands across transports.
 *
 * Implementations are responsible for permission/handshake details inside
 * [start]; lifecycle errors surface as [LocationSourceState.Error].
 */
interface LocationSource {
    val type: LocationSourceType

    val state: StateFlow<LocationSourceState>

    suspend fun start()

    suspend fun stop()
}
