package ai.openclaw.zodiaccontrol.ui.state

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType

data class CockpitUiState(
    val headingDeg: Int = 0,
    val speedKph: Int = 0,
    val thermalC: Int = 0,
    val mode: CockpitMode = CockpitMode.DIAGNOSTIC,
    val linkStable: Boolean = true,
    val selectedTransport: TransportType = TransportType.BLE,
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionDetail: String? = null,
    val playaMap: PlayaMap? = null,
    val selectedLocationSource: LocationSourceType = LocationSourceType.FAKE,
    val locationState: LocationSourceState = LocationSourceState.Disconnected,
    val mapMode: MapMode = MapMode.TOP,
    val tiltDeg: Int = DEFAULT_TILT_DEG,
    val pixelsPerMeter: Double = DEFAULT_PIXELS_PER_METER,
    val panEastM: Double = 0.0,
    val panNorthM: Double = 0.0,
    val mapLoadError: String? = null,
    val concept: CockpitConcept = CockpitConcept.A,
) {
    companion object {
        const val DEFAULT_TILT_DEG: Int = 40
        const val MIN_TILT_DEG: Int = 0
        const val MAX_TILT_DEG: Int = 80

        // Vehicle command bounds. Heading is full circle exclusive of 360 (which
        // wraps to 0). Speed cap is a soft limit on what the cockpit will ever
        // ask the chassis for; the vehicle itself enforces its own hard limit.
        const val MIN_HEADING_DEG: Int = 0
        const val MAX_HEADING_DEG: Int = 359
        const val MIN_SPEED_KPH: Int = 0
        const val MAX_SPEED_KPH: Int = 160

        // Map zoom in screen pixels per playa meter. Defaults frame the ~5 km
        // city radius at the typical Fire-tablet viewport. Mirrors the bounds
        // enforced by MapTouchInput's pinch handler.
        const val DEFAULT_PIXELS_PER_METER: Double = 0.18
        const val MIN_PIXELS_PER_METER: Double = 0.05
        const val MAX_PIXELS_PER_METER: Double = 5.0

        // Hard cap on how far the camera can drift from ego before recenter is
        // required. Keeps a stuck/dragging finger from sliding the city far
        // off-canvas where the RECENTER chip is the only escape and the user
        // might not see it.
        const val MAX_PAN_M: Double = 5_000.0
    }

    val egoFix: GpsFix? = (locationState as? LocationSourceState.Active)?.fix
}
