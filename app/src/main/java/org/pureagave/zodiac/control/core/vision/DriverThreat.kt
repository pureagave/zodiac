package org.pureagave.zodiac.control.core.vision

/**
 * A thermal contact the driver should be aware of, in camera-relative terms:
 * [relAzDeg] is bearing off the vehicle's nose (− left / + right), [size] is
 * 0 (far) → 1 (near), and [collision] flags a constant-bearing/looming track —
 * the only thing on the night HUD allowed to go bright red. [id] is a stable
 * track id so a contact can be followed frame to frame (defaults to 0 for
 * ad-hoc/test contacts). Emitted by the thermal edge box (Jetson) and, until
 * that exists, by [org.pureagave.zodiac.control.data.vision.FakeThreatSource].
 */
data class DriverThreat(
    val relAzDeg: Float,
    val size: Float,
    val collision: Boolean = false,
    val id: Int = 0,
)
