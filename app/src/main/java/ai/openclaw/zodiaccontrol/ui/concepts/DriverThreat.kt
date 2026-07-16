package ai.openclaw.zodiaccontrol.ui.concepts

/**
 * A single thermal contact the driver should be aware of, in camera-relative
 * terms: [relAzDeg] is bearing off the vehicle's nose (− left / + right) and
 * [size] is 0 (far) → 1 (near). [collision] flags a constant-bearing/looming
 * track — the only thing on the night HUD allowed to go bright red. Until the
 * FLIR feed exists these are placeholder contacts (a later phase replaces them
 * with a real source).
 */
data class DriverThreat(
    val relAzDeg: Float,
    val size: Float,
    val collision: Boolean = false,
)
