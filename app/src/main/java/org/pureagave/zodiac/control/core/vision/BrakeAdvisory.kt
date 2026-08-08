package org.pureagave.zodiac.control.core.vision

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * Turns a stream of thermal contacts into the braking advice the HUD shows,
 * sector- and speed-gated by [SurroundRing.brakeAdvised] and then smoothed by
 * an [AlarmLatch] so a chattering collision flag reads as one steady warning.
 *
 * This is an operator rather than a pair of ViewModel methods for a practical
 * reason: clearing the alarm needs a *timer*, because when a hazard passes the
 * edge box simply stops flagging it and a quiet feed produces no further frames
 * to re-evaluate on. Expressed as a flow, `transformLatest` gives that for free
 * — each new frame cancels the pending clear, and the tail of the block emits
 * the clear if no frame arrives first. The ViewModel just collects a Boolean.
 *
 * [speedKph] is read per frame rather than captured, so the gate follows the
 * vehicle slowing to a stop mid-alarm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<DriverThreat>>.brakeAdvisory(
    speedKph: () -> Float,
    latch: AlarmLatch = AlarmLatch(),
): Flow<Boolean> =
    transformLatest { threats ->
        emit(latch.update(SurroundRing.brakeAdvised(threats, speedKph())))
        // Nothing else will wake us: schedule the clear for exactly when the
        // hold expires rather than polling. A newer frame cancels this whole
        // block, which is why the latch is consulted again below rather than
        // assuming the answer is still false.
        val remaining = latch.holdRemainingMs()
        if (remaining > 0L) {
            delay(remaining)
            emit(latch.update(false))
        }
    }.distinctUntilChanged()
