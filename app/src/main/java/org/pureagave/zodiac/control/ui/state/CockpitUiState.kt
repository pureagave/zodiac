package org.pureagave.zodiac.control.ui.state

import org.pureagave.zodiac.control.core.connection.ConnectionPhase
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.core.model.CockpitMode
import org.pureagave.zodiac.control.core.model.FollowMode
import org.pureagave.zodiac.control.core.model.MapCameraState
import org.pureagave.zodiac.control.core.model.MapMode
import org.pureagave.zodiac.control.core.model.PlayaMap
import org.pureagave.zodiac.control.core.navigation.NavigationCue
import org.pureagave.zodiac.control.core.ops.DriveTarget
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.nearestDriveTarget
import org.pureagave.zodiac.control.core.ops.toDriveTarget
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.core.telemetry.BeaconHealth
import org.pureagave.zodiac.control.core.telemetry.BeaconReadout
import org.pureagave.zodiac.control.core.telemetry.Odometer
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.VisionFeed

/**
 * Everything the cockpit draws, in one immutable value.
 *
 * Updated exclusively via `.copy()` from [CockpitViewModel]; nothing else
 * writes it, and no composable holds a mutable slice of it. That's what lets
 * all three concepts render the same world with no risk of drift between them.
 *
 * Fields are deliberately flat and primitive where the renderer wants them
 * flat — the map camera in particular is five loose fields ([headingDeg],
 * [pixelsPerMeter], [panEastM]/[panNorthM], [tiltDeg], [mapMode]) rather than
 * a nested object, which keeps the per-frame draw path free of unwrapping.
 * The derived read-only helpers at the bottom (e.g. [egoFix]) exist so the UI
 * never re-derives the same thing two different ways.
 */
data class CockpitUiState(
    val headingDeg: Int = 0,
    /**
     * The *commanded* speed from the SPD debug chips. Drives the synthetic GPS
     * and the outbound SetSpeed command — it is an input, not a measurement.
     * Anything asking "how fast is the vehicle moving" wants
     * [effectiveSpeedKph] instead.
     */
    val speedKph: Int = 0,
    /** Measured ground speed from the GPS fix; null until a fix carries one. */
    val gpsSpeedKph: Double? = null,
    val thermalC: Int = 0,
    val mode: CockpitMode = CockpitMode.DIAGNOSTIC,
    val linkStable: Boolean = true,
    val selectedTransport: TransportType = TransportType.BLE,
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionDetail: String? = null,
    val playaMap: PlayaMap? = null,
    val selectedLocationSource: LocationSourceType = LocationSourceType.FAKE,
    val locationState: LocationSourceState = LocationSourceState.Disconnected,
    /**
     * Where the map camera is looking — mode, tilt, zoom, free-pan position,
     * follow mode and view rotation, as one value (A2). Writers must go
     * through this; the flat properties below are read-only conveniences so
     * the render path keeps its short names.
     */
    val camera: MapCameraState = MapCameraState.DEFAULT,
    val mapLoadError: String? = null,
    /**
     * True while a manually-triggered [org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel.retryMapLoad]
     * attempt is in flight. Gates the on-screen RETRY chip so mashing it can't
     * pile up concurrent map parses; cleared as soon as the repository's next
     * Loaded/Failed result lands, whichever it is.
     */
    val mapLoadRetrying: Boolean = false,
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
     * [org.pureagave.zodiac.control.data.discovery.DiscoveryRepository]. Rendered
     * as RADAR contacts / MAP markers; empty until the cache/first sync lands.
     */
    val pois: List<PlayaPoi> = emptyList(),
    /**
     * Thermal contacts for the DRIVER night HUD, from the routed threat source
     * (real network feed from the Jetson edge box, else a fake moving demo).
     * Empty = all clear.
     */
    val threats: List<DriverThreat> = emptyList(),
    /**
     * Health of the [threats] feed — LIVE (real detections), DEMO (bench
     * fallback), or ABSENT (no feed and no demo). Defaults to ABSENT: a
     * ViewModel wired with no vision source genuinely has no vision. The
     * DRIVER HUD status line may only show "CLEAR" when this is LIVE — an
     * absent feed must never look like a clear road.
     */
    val visionFeed: VisionFeed = VisionFeed.ABSENT,
    /**
     * Which bearing arcs a *currently delivering* camera watches, from the edge
     * box's low-rate `ZCOVER` signal (RES-P2-1). Null = no fresh coverage
     * signal, so the surround ring falls back to
     * [org.pureagave.zodiac.control.core.vision.SurroundRing.COVERED_ARCS]
     * (an old Jetson, or a stale signal — today's behaviour). Empty = a live
     * feed watching nothing, so the whole ring is blind. Drives the ring rim's
     * COVERED/BLIND classification via `SurroundRingCoverage.rimSegments`; a dead camera
     * shows as a visible blind wedge instead of a false all-clear.
     */
    val visionCoverage: List<ClosedFloatingPointRange<Float>>? = null,
    /**
     * Whether the HUD should be telling the driver to brake, **after** the
     * flicker latch — see [org.pureagave.zodiac.control.core.vision.AlarmLatch].
     * Distinct from recomputing `brakeAdvised` at the draw site, which strobes:
     * the edge box's collision flag chatters at frame rate while the hazard
     * itself is continuous.
     */
    val brakeAdvised: Boolean = false,
    /**
     * Whether a closing contact astern should be called out — latched the same
     * way as [brakeAdvised], and for the same reason. Braking is deliberately
     * *not* advised for these: it puts the vehicle further into the contact's
     * path.
     */
    val checkRear: Boolean = false,
    /**
     * Ambient light (lux) from the Sensor Hub's `$ZENV`, or null when no beacon
     * is feeding it. Drives the auto-dim ([luxToBrightness] → window brightness);
     * null leaves the tablet on its own system brightness.
     */
    val ambientLux: Double? = null,
    /** Beacon health heartbeat (`$ZBCN`) for the ops footer; null until one arrives. */
    val beaconHealth: BeaconHealth? = null,
    /** Trip + lifetime odometer (`$ZODO`) for the ops footer; null until one arrives. */
    val odometer: Odometer? = null,
    /**
     * Transient shock/impact alert (peak g from `$ZSHK`); non-null only while the
     * alert banner is showing, then cleared on a timer by the ViewModel.
     */
    val shockAlertG: Double? = null,
    /**
     * Whether *this device* may set + broadcast the shared nav target
     * (`$ZNAV`) — true on the S9+ and A54, false on the two Fires. Automatic:
     * it *is*
     * [org.pureagave.zodiac.control.burnin.BurnInDeviceProfile.visualModulationSupported],
     * computed once at startup, not a per-device toggle — the OLED Samsungs
     * are always authorities, the LCD Fires always follow. Gates the drive-to
     * entry points (chips, ADDR keypad) both at the ViewModel
     * (`NavShareController.userSet`'s central gate) and here in the UI, so a
     * follower's controls visibly do nothing rather than silently eating a
     * tap.
     *
     * Folded into `CockpitUiState` (unlike `passengerMode`, which never enters
     * it) because the ViewModel itself needs the current value synchronously
     * to gate every send — reading a separate `StateFlow` from inside
     * `NavShareController.userSet` would work but would split "what can this
     * device do" across two sources of truth the UI also has to agree on.
     * Default **true** so a bare `CockpitUiState()` (most tests)
     * renders/behaves as authority-enabled without extra wiring; production
     * folds the real, device-derived value in before the first frame via the
     * existing collector.
     */
    val navAuthority: Boolean = true,
) {
    /**
     * The three beacon readings the ops footer draws, bundled — they arrive on
     * separate sentences but are always rendered as a group.
     */
    val beaconReadout: BeaconReadout
        get() = BeaconReadout(odometer = odometer, health = beaconHealth, shockG = shockAlertG)

    companion object {
        // Camera bounds now live on MapCameraState (A2); re-exported here
        // because call sites across the UI already reach for them by this name.
        const val DEFAULT_TILT_DEG: Int = MapCameraState.DEFAULT_TILT_DEG
        const val MIN_TILT_DEG: Int = MapCameraState.MIN_TILT_DEG
        const val MAX_TILT_DEG: Int = MapCameraState.MAX_TILT_DEG

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
        const val DEFAULT_PIXELS_PER_METER: Double = MapCameraState.DEFAULT_PIXELS_PER_METER
        const val MIN_PIXELS_PER_METER: Double = MapCameraState.MIN_PIXELS_PER_METER
        const val MAX_PIXELS_PER_METER: Double = MapCameraState.MAX_PIXELS_PER_METER
        const val MAX_CAMERA_OFFSET_M: Double = MapCameraState.MAX_CAMERA_OFFSET_M
    }

    // Read-only views onto [camera]. Kept because ~90 call sites across the
    // render path read them by these names, and because a renderer has no
    // business writing the camera — only CockpitViewModel does, and it writes
    // the whole value at once so an inconsistent pair can't slip through.
    val mapMode: MapMode get() = camera.mapMode
    val tiltDeg: Int get() = camera.tiltDeg
    val pixelsPerMeter: Double get() = camera.pixelsPerMeter
    val cameraOverride: PlayaPoint? get() = camera.cameraOverride
    val followMode: FollowMode get() = camera.followMode
    val viewRotationDeg: Double get() = camera.viewRotationDeg

    /**
     * How fast the vehicle is actually moving. **One owner, on purpose.**
     *
     * There were three uncorrelated speeds in this codebase — the debug chip,
     * the GPS fix, and the beacon's `$ZTLM` — and each consumer picked one ad
     * hoc. The forward-collision BRAKE gate picked the debug chip, so on a real
     * drive it read 0 and the braking imperative could never fire; the rear
     * alert has no speed gate, which is why bench testing against real bus
     * traffic never caught it. Route every speed question through here.
     *
     * Measurement wins over command: a real fix is ground truth, and the debug
     * value is only meaningful when the fake source is driving. (`$ZTLM` speed
     * belongs in this chain too once it is folded into state.)
     */
    val effectiveSpeedKph: Double get() = gpsSpeedKph ?: speedKph.toDouble()

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
private val NAV_PROJECTION = PlayaProjection(GoldenSpike.ACTIVE)
