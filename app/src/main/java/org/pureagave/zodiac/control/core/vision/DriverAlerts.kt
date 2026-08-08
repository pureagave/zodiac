package org.pureagave.zodiac.control.core.vision

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * The two imperatives the DRIVER HUD can put in front of the driver, after
 * gating and smoothing. Never both — see [SurroundRing.hudStatus] for the
 * precedence.
 */
data class DriverAlerts(
    val brake: Boolean = false,
    val checkRear: Boolean = false,
)

/**
 * Turns a stream of thermal contacts into the alerts the HUD shows: sector- and
 * speed-gated by [SurroundRing], then each held by its own [AlarmLatch] so a
 * chattering collision flag reads as one steady warning.
 *
 * **Both alerts are latched, not just braking.** They come off the same noisy
 * per-frame `collision` flag, so a rear contact would strobe `! CHECK REAR !`
 * exactly the way a forward one strobed `! BRAKE !` — proved on the bench
 * 2026-08-08 by injecting a rear collision on the real bus. Latching one and
 * not the other would also let the two alerts disagree mid-flicker, which is
 * worse than either failure alone.
 *
 * This is an operator rather than a pair of ViewModel methods for a practical
 * reason: clearing an alarm needs a *timer*, because when a hazard passes the
 * edge box simply stops flagging it and a quiet feed produces no further frames
 * to re-evaluate on. Expressed as a flow, `transformLatest` gives that for free
 * — each new frame cancels the pending clear, and the tail of the block emits
 * the clear if no frame arrives first. The ViewModel just collects.
 *
 * [speedKph] is read per frame rather than captured, so the gate follows the
 * vehicle slowing to a stop mid-alarm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<DriverThreat>>.driverAlerts(
    speedKph: () -> Float,
    brakeLatch: AlarmLatch = AlarmLatch(),
    rearLatch: AlarmLatch = AlarmLatch(),
): Flow<DriverAlerts> =
    transformLatest { threats ->
        emit(
            DriverAlerts(
                brake = brakeLatch.update(SurroundRing.brakeAdvised(threats, speedKph())),
                checkRear = rearLatch.update(SurroundRing.rearAlert(threats)),
            ),
        )
        // Nothing else will wake us: schedule the clear for exactly when the
        // last hold expires rather than polling. A newer frame cancels this
        // whole block, which is why the latches are consulted again below
        // rather than assuming the answer is still false.
        val remaining = maxOf(brakeLatch.holdRemainingMs(), rearLatch.holdRemainingMs())
        if (remaining > 0L) {
            delay(remaining)
            emit(DriverAlerts(brake = brakeLatch.update(false), checkRear = rearLatch.update(false)))
        }
    }.distinctUntilChanged()
