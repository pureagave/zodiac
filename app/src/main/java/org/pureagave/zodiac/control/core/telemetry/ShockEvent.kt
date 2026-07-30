package org.pureagave.zodiac.control.core.telemetry

/**
 * A shock/impact event from the Sensor Hub's accelerometer (`$ZSHK`): the peak
 * linear-acceleration magnitude in g (gravity removed). Event-driven — the
 * beacon only emits this when a spike crosses its threshold (a big bump, a hit,
 * someone climbing on the vehicle).
 */
data class ShockEvent(
    val peakG: Double,
)
