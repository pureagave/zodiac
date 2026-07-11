package ai.openclaw.zodiaccontrol.ui.state

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.FollowMode
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.navigation.NavigationCue
import ai.openclaw.zodiaccontrol.core.ops.DriveTarget
import ai.openclaw.zodiaccontrol.core.ops.NavTarget
import ai.openclaw.zodiaccontrol.core.ops.PlayaPoi
import ai.openclaw.zodiaccontrol.core.ops.nearestDriveTarget
import ai.openclaw.zodiaccontrol.core.ops.toDriveTarget
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
    /**
     * Absolute camera position in playa metres when [followMode] is
     * [FollowMode.FREE]. Null in [FollowMode.TRACK_UP] — the renderer
     * centres on the live GPS fix instead.
     */
    val cameraOverride: PlayaPoint? = null,
    val followMode: FollowMode = FollowMode.TRACK_UP,
    /**
     * Compass direction (degrees CW from true north) currently aligned with
     * the top of the viewport. In [FollowMode.TRACK_UP] this tracks
     * [headingDeg] so the ego always points up. In [FollowMode.FREE] the
     * two-finger rotate gesture moves it independently of the ego's
     * physical heading.
     */
    val viewRotationDeg: Double = 0.0,
    val mapLoadError: String? = null,
    val concept: CockpitConcept = CockpitConcept.RADAR,
    val navCue: NavigationCue = NavigationCue.Unknown,
    /** Street name to flash big as the ego drives onto/past a street; null when nothing to show. */
    val streetPopup: String? = null,
    /** Notable art the ego is currently passing (within range), flashed as a bottom callout; null when none. */
    val passingCallout: String? = null,
    /** Active "drive to" preset — HOME/MAN/TEMPLE (default HOME/camp). */
    val navTarget: NavTarget = NavTarget.HOME,
    /**
     * When true the active drive-to is the *nearest toilet bank* (the dynamic
     * "BATH" destination) instead of [navTarget] — see [activeDriveTarget]. It
     * re-resolves as the ego moves. Cleared whenever a preset is re-selected.
     */
    val driveToBath: Boolean = false,
    /**
     * A typed-in address (or other arbitrary destination) that overrides the
     * preset / BATH as the active drive-to. Cleared when a preset or BATH is
     * chosen. Set via the address keypad ([addressEntryOpen]).
     */
    val customTarget: DriveTarget? = null,
    /** Whether the full-screen address-entry keypad is showing (overlay in `CockpitScreen`). */
    val addressEntryOpen: Boolean = false,
    /**
     * Street-aware route to [activeDriveTarget] across the BRC polar grid, in
     * playa metres (see `core/navigation/PlayaRouter`). Empty when there's no
     * route (no fix / target / city model). Drawn on the map.
     */
    val routeWaypointsM: List<PlayaPoint> = emptyList(),
    /**
     * The next corner along [routeWaypointsM] the driver should steer toward —
     * what the guidance chevron + ops arrow actually aim at (vs. the final
     * destination, which the label/distance still refer to). Null = no route.
     */
    val nextWaypoint: LatLon? = null,
    /** The clock street the route enters the city on (e.g. "2:30"), for display; null when free-drive. */
    val entranceRadial: String? = null,
    /**
     * Playa-discovery points of interest (art + camps) from the offline-first
     * [ai.openclaw.zodiaccontrol.data.discovery.DiscoveryRepository]. Rendered
     * as RADAR contacts / MAP markers; empty until the cache/first sync lands.
     */
    val pois: List<PlayaPoi> = emptyList(),
    /**
     * Short message describing the most recent failed vehicle command send
     * (SetHeading / SetSpeed), or null when the last send succeeded. Surfaced
     * here so a dropped command on a vehicle control surface is observable
     * instead of being silently swallowed by the fire-and-forget launch.
     */
    val commandError: String? = null,
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

        // Hard cap on how far the camera can drift from ego in [FollowMode.FREE].
        // Keeps a stuck/dragging finger from sliding the city far off-canvas
        // where the recenter button might be the only escape.
        const val MAX_CAMERA_OFFSET_M: Double = 5_000.0
    }

    val egoFix: GpsFix? = (locationState as? LocationSourceState.Active)?.fix

    /**
     * The destination the cockpit is actually guiding to: the live nearest
     * toilet when [driveToBath] is set (null if there's no fix / no toilets
     * loaded), otherwise the active [navTarget] preset. The heading-guidance
     * chevron, the ops footer, and the RADAR target blip all steer to this.
     */
    val activeDriveTarget: DriveTarget? =
        customTarget
            ?: if (driveToBath) {
                nearestDriveTarget(
                    label = "BATH",
                    ego = egoFix?.location,
                    candidates = playaMap?.toilets?.mapNotNull { it.centroid }.orEmpty(),
                    projection = NAV_PROJECTION,
                )
            } else {
                navTarget.toDriveTarget()
            }
}

/** Shared projection for drive-to resolution (nearest-toilet distances). */
private val NAV_PROJECTION = PlayaProjection(GoldenSpike.Y2025)
