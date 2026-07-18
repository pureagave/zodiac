package org.pureagave.zodiac.control.data

import kotlinx.coroutines.flow.Flow
import org.pureagave.zodiac.control.core.model.Telemetry

interface TelemetryRepository {
    fun stream(): Flow<Telemetry>
}
