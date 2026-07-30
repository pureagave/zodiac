package org.pureagave.zodiac.beacon

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reduces a block of 16-bit PCM samples to a normalized loudness — [Frame.rms]
 * and [Frame.peak], both 0..1 — plus a simple energy-based beat/onset flag. The
 * beat detector is stateful: it keeps a decaying running-average energy and flags
 * a frame whose energy jumps well above that average (ignoring near-silence).
 * Pure — no AudioRecord here; the broadcaster feeds it captured buffers and
 * emits a `$ZAUD` per frame for sound-reactive lighting.
 */
class AudioLevels(
    private val beatSensitivity: Double = DEFAULT_BEAT_SENSITIVITY,
    private val averageDecay: Double = DEFAULT_AVERAGE_DECAY,
) {
    private var avgEnergy: Double = 0.0
    private var primed: Boolean = false

    data class Frame(
        val rms: Double,
        val peak: Double,
        val beat: Boolean,
    )

    /** Analyze the first [count] samples of [samples] (default the whole array). */
    fun analyze(
        samples: ShortArray,
        count: Int = samples.size,
    ): Frame {
        if (count <= 0) return Frame(0.0, 0.0, false)
        var sumSq = 0.0
        var peakAbs = 0
        for (i in 0 until count) {
            val s = samples[i].toInt()
            sumSq += (s * s).toDouble()
            val a = abs(s)
            if (a > peakAbs) peakAbs = a
        }
        val rms = (sqrt(sumSq / count) / MAX_16BIT).coerceIn(0.0, 1.0)
        val peak = (peakAbs.toDouble() / MAX_16BIT).coerceIn(0.0, 1.0)
        val energy = rms * rms
        // A beat = a frame that's both clearly louder than the running average and
        // above the silence floor. Not primed on the first frame (no baseline yet).
        val beat = primed && energy > avgEnergy * beatSensitivity && energy > MIN_BEAT_ENERGY
        avgEnergy = if (primed) avgEnergy * averageDecay + energy * (1 - averageDecay) else energy
        primed = true
        return Frame(rms = rms, peak = peak, beat = beat)
    }

    private companion object {
        const val MAX_16BIT = 32767.0
        const val DEFAULT_BEAT_SENSITIVITY = 1.6 // energy must exceed 1.6× the running average
        const val DEFAULT_AVERAGE_DECAY = 0.9 // running-average smoothing per frame
        const val MIN_BEAT_ENERGY = 0.0004 // don't call beats in near-silence
    }
}
