package org.pureagave.zodiac.control.core.telemetry

/**
 * Ambient-sound level from the Sensor Hub's microphone (`$ZAUD`), for
 * sound-reactive lighting. [rms] is the smoothed loudness and [peak] the
 * instantaneous peak, both normalized 0..1; [beat] flags a detected onset in the
 * current frame.
 */
data class AudioLevel(
    val rms: Double,
    val peak: Double,
    val beat: Boolean,
)
