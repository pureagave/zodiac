package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.model.Telemetry
import kotlinx.coroutines.flow.Flow

interface TelemetryRepository {
    fun stream(): Flow<Telemetry>
}
