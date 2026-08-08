package org.pureagave.zodiac.control.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.pureagave.zodiac.control.core.model.CockpitMode
import org.pureagave.zodiac.control.core.model.Telemetry
import kotlin.math.abs

/**
 * Synthetic telemetry for bench work: heading sweeps, speed oscillates, the
 * thermal wanders, and the mode rotates on a fixed cycle.
 *
 * The tick wraps at [CYCLE_TICKS] rather than counting up forever. Every
 * derived value is periodic — heading every 120 ticks, speed 40, thermal 9,
 * mode 60 — and 360 is their least common multiple, so wrapping there emits a
 * byte-identical sequence while making an unbounded counter impossible. The
 * overflow it prevents was 34 years away and purely cosmetic; the point is
 * that a stream nothing ever resets shouldn't have a horizon at all.
 */
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
                tick = (tick + 1) % CYCLE_TICKS
                delay(TICK_MS)
            }
        }

    private companion object {
        /** LCM of the heading (120), speed (40), thermal (9) and mode (60) periods. */
        const val CYCLE_TICKS = 360
        const val TICK_MS = 500L
    }
}
