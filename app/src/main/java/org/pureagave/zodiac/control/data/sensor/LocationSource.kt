package org.pureagave.zodiac.control.data.sensor

import kotlinx.coroutines.flow.StateFlow
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType

/**
 * Contract every GPS provider implements. Mirrors the
 * [org.pureagave.zodiac.control.data.transport.TransportAdapter] shape so the
 * cockpit can route between fake/system/BLE/USB the same way it routes vehicle
 * commands across transports.
 *
 * Implementations are responsible for permission/handshake details inside
 * [start]; lifecycle errors surface as [LocationSourceState.Error].
 *
 * [stop] must leave [state] at [LocationSourceState.Disconnected], even if
 * releasing the underlying hardware/platform resource throws. Wrappers such
 * as [FailoverLocationSource] judge liveness from [state] alone, so a source
 * that stays `Active` past `stop()` returning presents a frozen fix as live.
 */
interface LocationSource {
    val type: LocationSourceType

    val state: StateFlow<LocationSourceState>

    suspend fun start()

    suspend fun stop()
}
