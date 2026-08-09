package org.pureagave.zodiac.beacon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A periodic tick loop that survives a throwing body. Without this, one bad
 * tick (e.g. a transient sensor/NMEA hiccup) would silently end the whole
 * coroutine — every synthesized channel (`$GPHDT`/`$ZTLM`/`$ZENV`/`$ZODO`/
 * `$ZBCN`) stops while raw GNSS passthrough (a separate listener) keeps
 * flowing and `isRunning` stays true, with nothing on the phone saying so.
 *
 * [CancellationException] is rethrown — a `stop()` is not an error, it's the
 * loop ending on purpose. A plain [Error] (OOM, etc.) is not caught either;
 * that class of failure should crash and let the process restart, not limp
 * along in a corrupted state.
 */
internal class TickLoop(
    private val body: (tick: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    suspend fun run(intervalMs: Long) {
        var tick = 0L
        while (currentCoroutineContext().isActive) {
            @Suppress("TooGenericExceptionCaught") // one bad tick must not end every synthesized channel
            try {
                body(tick)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                onError(t)
            }
            tick++
            delay(intervalMs)
        }
    }
}

/** Loop dead beyond this long since the last tick → scream, don't wait for a human to notice. */
internal const val TICK_DEAD_MS = 5_000L

/**
 * Pure health-line computation for the on-device status readout — kept free of
 * Android imports so it's exhaustively testable off-device.
 *
 * Dead-loop detection is deliberately independent of the loop itself: it must
 * be computable by something *other* than the thing that could be dead (see
 * the watchdog coroutine in [TelemetryBroadcaster.start]).
 */
internal fun tickHealthLine(
    nowMs: Long,
    lastTickAtMs: Long,
    tickErrors: Long,
    lastError: String?,
): String? =
    when {
        lastTickAtMs > 0 && nowMs - lastTickAtMs > TICK_DEAD_MS ->
            "⚠ TICK LOOP DEAD ${(nowMs - lastTickAtMs) / 1000}s — HDT/ZTLM/ZENV/ZODO/ZBCN stopped"
        tickErrors > 0 -> "⚠ tick errors: $tickErrors (last: $lastError)"
        else -> null
    }
