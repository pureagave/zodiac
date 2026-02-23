package ai.openclaw.zodiaccontrol.data

import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs

class FakeTelemetryRepository : TelemetryRepository {
    override fun stream(): Flow<Telemetry> =
        flow {
            var tick = 0
            while (true) {
                val heading = (42 + tick * 3) % 360
                val speed = 22 + abs((tick % 40) - 20)
                val thermal = 58 + (tick % 9)
                val mode =
                    when ((tick / 20) % 3) {
                        0 -> CockpitMode.DIAGNOSTIC
                        1 -> CockpitMode.DRIVE
                        else -> CockpitMode.COMBAT
                    }
                emit(
                    Telemetry(
                        headingDeg = heading,
                        speedKph = speed,
                        thermalC = thermal,
                        linkStable = true,
                        mode = mode,
                    ),
                )
                tick++
                delay(500)
            }
        }
}
