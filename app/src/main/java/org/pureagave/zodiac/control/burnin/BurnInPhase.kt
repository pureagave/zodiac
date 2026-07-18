package org.pureagave.zodiac.control.burnin

/**
 * Display-protection state for the OLED dashboard (Galaxy Tab S9+). Burn-in is
 * an OLED-only concern; on the LCD Fire HD 10 the visual layers are gated off
 * (see [BurnInConfig.visualModulationEnabled]) and the machine effectively
 * idles in [ACTIVE].
 *
 * Phases escalate with idle time and de-escalate instantly to [ACTIVE] on any
 * activity (touch, real GPS movement, or a vehicle-link phase change).
 */
enum class BurnInPhase {
    /** Normal cockpit, full brightness, pixel-shift + subtle brightness breathe. */
    ACTIVE,

    /** Dashboard dimmed (~30% content, reduced backlight); breathing frozen. */
    DIM,

    /** Dashboard replaced by the minimal CRT "STANDBY" screen + slow sweep. */
    DEEP_IDLE,

    /** App-drawn pure black + minimum backlight — OLED pixels off. Wakes on activity. */
    SLEEP,
}
