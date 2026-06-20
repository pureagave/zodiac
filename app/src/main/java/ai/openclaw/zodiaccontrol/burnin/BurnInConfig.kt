package ai.openclaw.zodiaccontrol.burnin

/**
 * Tunable parameters for [BurnInMitigationManager]. Every value is meant to be
 * Preferences-backed and adjustable on the playa via the hidden tuning panel,
 * so the type carries its own bounds in [coerced] — a tampered or stale
 * persisted value can never seed an out-of-range config (mirrors how
 * `DataStoreCockpitPreferences` coerces zoom/tilt on read).
 *
 * Defaults target the Tab S9+ OLED: a ~2px / 45s pixel jitter and a ±4% / 20s
 * brightness breathe that read as natural phosphor variation, with idle
 * escalation at 5 / 30 / 60 minutes.
 *
 * @property pixelShiftEnabled whole-UI translation to spread static-pixel wear.
 * @property pixelShiftAmplitudePx peak ± translation in px (kept tiny so the
 *  shift is imperceptible; nothing important sits within this of the edge given
 *  each concept's 12dp inset).
 * @property pixelShiftPeriodSec seconds for one full shift cycle.
 * @property visualModulationEnabled the OLED-only breathe + dim compositing
 *  layer. Gated off on the LCD Fire HD 10 (can't burn in, and the per-frame
 *  full-screen composite would cost the slowest tablet for nothing).
 * @property breatheAmplitude peak ± fractional brightness modulation in [ACTIVE].
 * @property breathePeriodSec seconds for one full breathe cycle.
 * @property dimTimeoutSec idle seconds before [BurnInPhase.DIM].
 * @property dimContentAlpha dashboard alpha in [BurnInPhase.DIM].
 * @property dimBacklight window backlight (0..1) in [BurnInPhase.DIM].
 * @property deepIdleTimeoutSec idle seconds before [BurnInPhase.DEEP_IDLE].
 * @property deepIdleBacklight window backlight (0..1) in [BurnInPhase.DEEP_IDLE].
 * @property sleepTimeoutSec idle seconds before [BurnInPhase.SLEEP].
 * @property sleepBacklight window backlight (0..1) in [BurnInPhase.SLEEP];
 *  near-zero so the OLED backlight is effectively off behind black pixels.
 * @property movementSpeedKph GPS ground speed at/above which a fix counts as
 *  movement (resets the idle timer).
 * @property movementMeters cumulative GPS displacement from the reference fix
 *  that counts as movement, catching slow drift below [movementSpeedKph].
 */
data class BurnInConfig(
    val pixelShiftEnabled: Boolean = true,
    val pixelShiftAmplitudePx: Int = DEFAULT_SHIFT_AMPLITUDE_PX,
    val pixelShiftPeriodSec: Int = DEFAULT_SHIFT_PERIOD_SEC,
    val visualModulationEnabled: Boolean = true,
    val breatheAmplitude: Float = DEFAULT_BREATHE_AMPLITUDE,
    val breathePeriodSec: Int = DEFAULT_BREATHE_PERIOD_SEC,
    val dimTimeoutSec: Long = DEFAULT_DIM_TIMEOUT_SEC,
    val dimContentAlpha: Float = DEFAULT_DIM_CONTENT_ALPHA,
    val dimBacklight: Float = DEFAULT_DIM_BACKLIGHT,
    val deepIdleTimeoutSec: Long = DEFAULT_DEEP_IDLE_TIMEOUT_SEC,
    val deepIdleBacklight: Float = DEFAULT_DEEP_IDLE_BACKLIGHT,
    val sleepTimeoutSec: Long = DEFAULT_SLEEP_TIMEOUT_SEC,
    val sleepBacklight: Float = DEFAULT_SLEEP_BACKLIGHT,
    val movementSpeedKph: Double = DEFAULT_MOVEMENT_SPEED_KPH,
    val movementMeters: Double = DEFAULT_MOVEMENT_METERS,
) {
    /**
     * Returns a copy with every field clamped to a sane range and the three
     * idle timeouts forced strictly increasing (dim < deepIdle < sleep), so
     * downstream phase math never sees an inverted or zero-width band.
     */
    fun coerced(): BurnInConfig {
        val amplitude = pixelShiftAmplitudePx.coerceIn(0, MAX_SHIFT_AMPLITUDE_PX)
        val shiftPeriod = pixelShiftPeriodSec.coerceIn(MIN_PERIOD_SEC, MAX_PERIOD_SEC)
        val breathe = breatheAmplitude.coerceIn(0f, MAX_BREATHE_AMPLITUDE)
        val breathePeriod = breathePeriodSec.coerceIn(MIN_PERIOD_SEC, MAX_PERIOD_SEC)

        val dim = dimTimeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        val deep = deepIdleTimeoutSec.coerceIn(dim + 1, MAX_TIMEOUT_SEC)
        val sleep = sleepTimeoutSec.coerceIn(deep + 1, MAX_TIMEOUT_SEC)

        return copy(
            pixelShiftAmplitudePx = amplitude,
            pixelShiftPeriodSec = shiftPeriod,
            breatheAmplitude = breathe,
            breathePeriodSec = breathePeriod,
            dimTimeoutSec = dim,
            dimContentAlpha = dimContentAlpha.coerceIn(MIN_BACKLIGHT, 1f),
            dimBacklight = dimBacklight.coerceIn(MIN_BACKLIGHT, 1f),
            deepIdleTimeoutSec = deep,
            deepIdleBacklight = deepIdleBacklight.coerceIn(MIN_BACKLIGHT, 1f),
            sleepTimeoutSec = sleep,
            sleepBacklight = sleepBacklight.coerceIn(0f, 1f),
            movementSpeedKph = movementSpeedKph.coerceAtLeast(0.0),
            movementMeters = movementMeters.coerceAtLeast(0.0),
        )
    }

    companion object {
        const val DEFAULT_SHIFT_AMPLITUDE_PX: Int = 2
        const val DEFAULT_SHIFT_PERIOD_SEC: Int = 45
        const val DEFAULT_BREATHE_AMPLITUDE: Float = 0.04f
        const val DEFAULT_BREATHE_PERIOD_SEC: Int = 20
        const val DEFAULT_DIM_TIMEOUT_SEC: Long = 300
        const val DEFAULT_DIM_CONTENT_ALPHA: Float = 0.30f
        const val DEFAULT_DIM_BACKLIGHT: Float = 0.40f
        const val DEFAULT_DEEP_IDLE_TIMEOUT_SEC: Long = 1_800
        const val DEFAULT_DEEP_IDLE_BACKLIGHT: Float = 0.15f
        const val DEFAULT_SLEEP_TIMEOUT_SEC: Long = 3_600
        const val DEFAULT_SLEEP_BACKLIGHT: Float = 0.01f
        const val DEFAULT_MOVEMENT_SPEED_KPH: Double = 1.0
        const val DEFAULT_MOVEMENT_METERS: Double = 3.0

        const val MAX_SHIFT_AMPLITUDE_PX: Int = 8
        const val MAX_BREATHE_AMPLITUDE: Float = 0.20f
        const val MIN_PERIOD_SEC: Int = 1
        const val MAX_PERIOD_SEC: Int = 600
        const val MIN_TIMEOUT_SEC: Long = 1
        const val MAX_TIMEOUT_SEC: Long = 86_400
        const val MIN_BACKLIGHT: Float = 0.01f
    }
}
