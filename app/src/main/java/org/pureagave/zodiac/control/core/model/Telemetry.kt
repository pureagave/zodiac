package org.pureagave.zodiac.control.core.model

data class Telemetry(
    val headingDeg: Int,
    val speedKph: Int,
    val thermalC: Int,
    val linkStable: Boolean,
    val mode: CockpitMode,
)

enum class CockpitMode {
    DIAGNOSTIC,
    DRIVE,
    COMBAT,
}
