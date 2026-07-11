package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.model.PlayaMap
import ai.openclaw.zodiaccontrol.core.ops.PlayaPoi
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.ui.playamap.LabelLayouts
import ai.openclaw.zodiaccontrol.ui.playamap.MapPalette
import ai.openclaw.zodiaccontrol.ui.playamap.ProjectedMap
import ai.openclaw.zodiaccontrol.ui.playamap.cockpitTouchInput
import ai.openclaw.zodiaccontrol.ui.playamap.drawEgoMarkerAt
import ai.openclaw.zodiaccontrol.ui.playamap.drawHexEgoMarkerAt
import ai.openclaw.zodiaccontrol.ui.playamap.drawProjectedMap
import ai.openclaw.zodiaccontrol.ui.playamap.drawRetroGrid
import ai.openclaw.zodiaccontrol.ui.playamap.project
import ai.openclaw.zodiaccontrol.ui.playamap.projectRetroGrid
import ai.openclaw.zodiaccontrol.ui.playamap.rememberLabelLayouts
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.sin

/** Ego marker drawing style for the shared map panel. */
enum class EgoStyle { TRIANGLE, HEX }

/**
 * The strict slice of [CockpitUiState] the map subtree actually reads.
 * Concept screens construct one of these from the live state and pass it
 * into [playaMapPanel] / `centerViewport` instead of the full state.
 *
 * The point is Compose's smart-skip: because every field here is stable
 * primitive / data-class and the surrounding parameters (`viewModel`,
 * `style`) are also stable, two consecutive recompositions with
 * unchanged map fields will be skipped at the call site even if the
 * parent recomposed for an unrelated reason — e.g. a thermal or
 * connection update flowing through `CockpitUiState`. Without the slice,
 * any state emission re-ran the map subtree's `remember` keys (cache
 * hits, but still allocations and Compose plumbing).
 */
@androidx.compose.runtime.Stable
data class MapUiInputs(
    val playaMap: PlayaMap?,
    val egoFix: GpsFix?,
    val cameraOverride: PlayaPoint?,
    val viewRotationDeg: Double,
    val pixelsPerMeter: Double,
    val tiltDeg: Int,
    val mapMode: MapMode,
    val headingDeg: Int,
    val pois: List<PlayaPoi>,
    val routeM: List<PlayaPoint>,
) {
    companion object {
        fun from(state: CockpitUiState): MapUiInputs =
            MapUiInputs(
                playaMap = state.playaMap,
                egoFix = state.egoFix,
                cameraOverride = state.cameraOverride,
                viewRotationDeg = state.viewRotationDeg,
                pixelsPerMeter = state.pixelsPerMeter,
                tiltDeg = state.tiltDeg,
                mapMode = state.mapMode,
                headingDeg = state.headingDeg,
                pois = state.pois,
                routeM = state.routeWaypointsM,
            )
    }
}

/**
 * Visual configuration for the shared playa map panel. Bundling these here
 * keeps [playaMapPanel]'s parameter list short and lets each concept declare
 * its style once at the call-site.
 *
 * [egoColor] defaults to null → the panel uses [MapPalette.fence] of the
 * supplied palette so the ego marker reads as part of the concept palette
 * unless the caller overrides it.
 */
data class PlayaMapPanelStyle(
    val palette: MapPalette,
    val egoStyle: EgoStyle = EgoStyle.TRIANGLE,
    val egoColor: Color? = null,
    val allowTilt: Boolean = true,
    val clipCircular: Boolean = false,
    val showRetroGrid: Boolean = false,
    val sweep: SweepOverlay? = null,
    /**
     * When set, plot nearby discovery POIs ([MapUiInputs.pois]) as scope
     * contacts. Kept off (null) for concepts that don't want the overlay.
     */
    val contacts: ContactsOverlay? = null,
    /**
     * When set, draw the street-aware route ([MapUiInputs.routeM]) from the ego
     * through its corners to the destination in this colour. Null = no route line.
     */
    val routeColor: Color? = null,
    /**
     * When true the camera is pinned to the ego (the car) and one-finger pan
     * is disabled — the vehicle stays at the scope centre and the map scrolls
     * under it. Used by the Concept-C radar scope so the sweep, which draws
     * from the canvas centre, always originates from the car. Zoom and rotate
     * still apply. Stale [CockpitUiState.cameraOverride] from another concept
     * is ignored while locked.
     */
    val lockCameraToEgo: Boolean = false,
)

/**
 * Optional sweep overlay (concept C). [sweepDeg] is the *leading edge* of the
 * wedge in screen-space degrees clockwise from straight up; the wedge spans
 * [sweepWidthDeg] degrees behind it. The map is re-drawn in [litPalette]
 * clipped to the wedge so features visibly brighten as the arm passes — the
 * canonical M41A "ping" effect.
 *
 * [sweepDeg] is a lambda read at *draw* time (not composition): the 60 fps
 * animation updates a backing [androidx.compose.runtime.MutableFloatState]
 * that only the draw lambdas subscribe to, so the frame ticker invalidates
 * just the draw phase instead of recomposing the whole Motion Tracker tree
 * (header / stats / nav cue + their String.format calls) every frame.
 */
data class SweepOverlay(
    val sweepDeg: () -> Float,
    val sweepWidthDeg: Float,
    val litPalette: MapPalette,
    val armColor: Color,
    val coneFwdDeg: Float,
    val coneFill: Color,
)

/**
 * RADAR contact overlay: plots the nearest [maxContacts] discovery POIs that
 * fall inside the scope's visible radius as blips (art = diamond, camp = dot),
 * plus the active drive-to [target] as a distinct ringed blip.
 *
 * [sweepDeg] is the optional M41A sweep angle (same lambda the [SweepOverlay]
 * uses, read at *draw* time): when present, blips pulse bright as the arm
 * passes and fade to a floor between pings; when null they hold steady. The
 * colours come from the concept palette so the overlay stays on-system.
 */
data class ContactsOverlay(
    val artColor: Color,
    val campColor: Color,
    val targetColor: Color,
    val target: LatLon?,
    val sweepDeg: (() -> Float)? = null,
    val maxContacts: Int = DEFAULT_MAX_CONTACTS,
)

private const val TILT_CAMERA_DISTANCE: Float = 8f

/**
 * Vertical anchor in TILT mode (fraction of viewport height where the
 * camera centre — i.e. the ego's GPS fix — projects to). 0.78 = lower
 * third, the arcade-racer "vehicle in the foreground, playa extends ahead"
 * framing. Map and ego use the *same* anchor so the ego marker draws
 * exactly where its real-world location projects on the rendered map.
 */
private const val TILT_ANCHOR_Y: Double = 0.78
private const val TOP_ANCHOR_Y: Double = 0.5
private const val TILT_ZOOM_BOOST: Double = 1.0

/** The perspective tilt angle applied in TILT mode (fixed; see the graphicsLayer note). */
private const val TILT_ANGLE_DEG: Float = 45f
private val PLAYA_PROJECTION = PlayaProjection(GoldenSpike.Y2025)

/**
 * Shared BRC map viewport for both concepts (RADAR / MAP). Wraps the existing
 * `cockpitTouchInput` (pan + pinch), projects the map at the live heading and
 * zoom, optionally tilts via graphicsLayer, and overlays the ego marker.
 *
 * @param allowTilt if true, the panel honours [CockpitUiState.mapMode]; if
 *   false (RADAR) the panel is always top-down regardless of mode.
 * @param clipCircular wraps the whole panel in a circular clip — the M41A
 *   look. Touch input still hits the bounding rect.
 * @param showRetroGrid draws the meter-space backdrop grid before the BRC
 *   features. Auto-enabled in TILT; concepts can also force it on (e.g. B in
 *   TOP for the perspective vibe).
 */
@Composable
fun playaMapPanel(
    inputs: MapUiInputs,
    viewModel: CockpitViewModel,
    style: PlayaMapPanelStyle,
    modifier: Modifier = Modifier,
) {
    val projection = remember { PLAYA_PROJECTION }
    val pixelsPerMeter = inputs.pixelsPerMeter
    val tilt = style.allowTilt && inputs.mapMode == MapMode.TILT
    val egoColor = style.egoColor ?: style.palette.fence

    // Track the panel's pixel size at Composable scope so the viewport (and
    // therefore the projected-map cache key) only invalidates when the
    // box is actually resized — not on every Canvas redraw.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Build the camera once per state-or-size change and reuse it across
    // both the map base canvas and the ego overlay so they project through
    // the *same* camera.
    val viewport: PlayaViewport? =
        remember(
            inputs.egoFix?.location,
            inputs.cameraOverride,
            inputs.viewRotationDeg,
            inputs.pixelsPerMeter,
            inputs.tiltDeg,
            tilt,
            canvasSize,
        ) {
            if (canvasSize.width <= 0 || canvasSize.height <= 0) {
                null
            } else {
                viewportFor(inputs, projection, tilt, canvasSize, style.lockCameraToEgo)
            }
        }

    // Project every BRC feature through [viewport] once per camera-state
    // change. ~600 streets × ~tens of vertices each is the dominant cost
    // when this is done per frame; with the cache, panning at 60 fps
    // reuses the same projection across all frames in a GPS tick window.
    val projected: ProjectedMap? =
        remember(inputs.playaMap, viewport) {
            val map = inputs.playaMap
            if (map != null && viewport != null) map.project(projection, viewport) else null
        }

    val retroGrid = rememberRetroGridPath(viewport)
    // Pre-measure every label's glyphs once per (map, density). Cache
    // survives camera changes and palette colour swaps; only invalidates
    // when the underlying map or screen density changes (i.e. essentially
    // never at runtime).
    val labelLayouts = rememberLabelLayouts(inputs.playaMap, style.palette.labelsEnabled)

    Box(
        modifier =
            modifier
                .then(if (style.clipCircular) Modifier.clip(androidx.compose.foundation.shape.CircleShape) else Modifier)
                .onSizeChanged { canvasSize = it }
                .cockpitTouchInput(
                    currentZoom = { pixelsPerMeter },
                    onPan =
                        if (style.lockCameraToEgo) {
                            // Radar-scope concepts (C) pin the car to the scope
                            // centre, so a one-finger drag must not pan the camera
                            // off the ego. Zoom and rotate still apply.
                            { _, _ -> }
                        } else {
                            { dxScreen, dyScreen ->
                                // Convert screen-pixel delta to world metres using
                                // the *display* rotation, not the ego's heading —
                                // in FREE the user has rotated the display
                                // independently of heading, and pan must move along
                                // the visible axes.
                                val h = Math.toRadians(inputs.viewRotationDeg)
                                val cosH = cos(h)
                                val sinH = sin(h)
                                val dE = (-dxScreen * cosH + dyScreen * sinH) / pixelsPerMeter
                                val dN = (dxScreen * sinH + dyScreen * cosH) / pixelsPerMeter
                                viewModel.panBy(dE, dN)
                            }
                        },
                    onZoom = viewModel::setPixelsPerMeter,
                    onRotate = viewModel::nudgeViewRotation,
                ),
    ) {
        // In TILT the whole map *plane* — base features, route, contacts, and the
        // ego — must share one 3D rotation so the overlays stay on the tilted
        // ground instead of floating flat above it. The sweep arm (RADAR only,
        // never tilted) draws from the flat canvas centre and stays outside.
        Box(
            modifier =
                if (tilt) {
                    Modifier.fillMaxSize().graphicsLayer {
                        // Fixed tilt angle. (A runtime-computed rotationX from
                        // inputs.tiltDeg wouldn't render in this graphicsLayer on the
                        // S9+ — only a compile-time constant does — and tiltDeg isn't
                        // user-adjustable, so a constant is both correct and robust.)
                        rotationX = TILT_ANGLE_DEG
                        cameraDistance = TILT_CAMERA_DISTANCE * density
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                } else {
                    Modifier.fillMaxSize()
                },
        ) {
            mapBaseCanvas(
                MapBaseInputs(
                    viewport = viewport,
                    projected = projected,
                    retroGrid = retroGrid,
                    labelLayouts = labelLayouts,
                    style = style,
                    tilt = tilt,
                ),
            )
            style.routeColor?.let { routeCanvas(inputs.egoFix, inputs.routeM, projection, viewport, it) }
            style.contacts?.let { contactsCanvas(inputs.pois, projection, viewport, it) }
            egoOverlayCanvas(
                EgoOverlayInputs(
                    inputs = inputs,
                    projection = projection,
                    viewport = viewport,
                    style = style,
                    tilt = tilt,
                    egoColor = egoColor,
                ),
            )
        }
        style.sweep?.let { sweepArmCanvas(it) }
    }
}

/**
 * Cache the meter-space backdrop grid as a single Path keyed on the
 * camera viewport. ~50 east/west ticks → one drawPath per frame instead
 * of 102 drawLine calls. Returns an empty Path until the canvas is sized
 * so the renderer can blit it unconditionally.
 */
@Composable
private fun rememberRetroGridPath(viewport: PlayaViewport?): Path {
    return remember(viewport) { viewport?.let(::projectRetroGrid) ?: Path() }
}

/**
 * Build the same [PlayaViewport] the map canvas uses, given the live state
 * and the panel's tilt mode. Centralised so the ego overlay projects
 * through the *exact* same camera as the rendered map.
 *
 * Camera position is the [CockpitUiState.cameraOverride] when the cockpit
 * is in [FollowMode.FREE] (an absolute world position parked by the user)
 * and the live ego fix in [FollowMode.TRACK_UP]. Display rotation comes
 * from [CockpitUiState.viewRotationDeg], which decouples the user's
 * two-finger twist from the ego's GPS-reported heading.
 */
private fun viewportFor(
    inputs: MapUiInputs,
    projection: PlayaProjection,
    tilt: Boolean,
    canvasSize: IntSize,
    lockToEgo: Boolean,
): PlayaViewport {
    val ego = inputs.egoFix?.let { projection.project(it.location) } ?: PlayaPoint(0.0, 0.0)
    // When locked (the Motion Tracker radar scope) the camera always centres
    // on the ego, so the car stays under the fixed scope centre and a pan
    // never decouples the sweep origin from the vehicle. A stale cameraOverride
    // left FREE by another concept is ignored.
    val cameraCenter = if (lockToEgo) ego else inputs.cameraOverride ?: ego
    val anchorYFrac = if (tilt) TILT_ANCHOR_Y else TOP_ANCHOR_Y
    return PlayaViewport(
        center = cameraCenter,
        headingDeg = inputs.viewRotationDeg,
        pixelsPerMeter = if (tilt) inputs.pixelsPerMeter * TILT_ZOOM_BOOST else inputs.pixelsPerMeter,
        widthPx = canvasSize.width,
        heightPx = canvasSize.height,
        anchorYFrac = anchorYFrac,
    )
}

/** Bundle so [mapBaseCanvas] stays under detekt's parameter cap. */
private data class MapBaseInputs(
    val viewport: PlayaViewport?,
    val projected: ProjectedMap?,
    val retroGrid: Path,
    val labelLayouts: LabelLayouts,
    val style: PlayaMapPanelStyle,
    val tilt: Boolean,
)

@Composable
private fun mapBaseCanvas(inputs: MapBaseInputs) {
    val tilt = inputs.tilt
    val style = inputs.style
    // Tilt is applied by the shared wrapper in [playaMapPanel] so the overlays
    // (route / contacts / ego) rotate with the base map onto one plane.
    Canvas(modifier = Modifier.fillMaxSize()) {
        val viewport = inputs.viewport ?: return@Canvas
        if (style.showRetroGrid || tilt) drawRetroGrid(inputs.retroGrid, style.palette.grid)
        val projected = inputs.projected
        if (projected != null) {
            drawProjectedMap(projected, style.palette, inputs.labelLayouts, viewport.pixelsPerMeter)
            style.sweep?.let { drawSweptProjectedMap(projected, it) }
        } else {
            drawCircle(
                color = style.palette.fence,
                radius = 6f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 1f),
            )
        }
    }
}

/** Bundle so [egoOverlayCanvas] stays under detekt's parameter cap. */
private data class EgoOverlayInputs(
    val inputs: MapUiInputs,
    val projection: PlayaProjection,
    val viewport: PlayaViewport?,
    val style: PlayaMapPanelStyle,
    val tilt: Boolean,
    val egoColor: Color,
)

@Composable
private fun egoOverlayCanvas(inputs: EgoOverlayInputs) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Project the live GPS fix through the same viewport the map uses
        // so the marker tracks its real-world position across pan / zoom /
        // rotate. The screen-rotation angle is heading − viewRotation:
        // 0 in TRACK_UP (heading lines up with the display rotation, ego
        // points up); non-zero in FREE after a two-finger twist (heading
        // is unchanged, display rotated, marker keeps pointing in the
        // physical direction of motion). Falls back to viewport centre
        // when there's no fix yet so the marker is still visible at boot.
        val mapInputs = inputs.inputs
        val viewport = inputs.viewport
        val tilt = inputs.tilt
        val ego =
            if (viewport == null) {
                null
            } else {
                mapInputs.egoFix?.location?.let { viewport.toScreen(inputs.projection.project(it)) }
            }
        val cx = ego?.x?.toFloat() ?: (size.width / 2f)
        val cy = ego?.y?.toFloat() ?: (size.height * (if (tilt) TILT_ANCHOR_Y else TOP_ANCHOR_Y).toFloat())
        val rotationDeg = (mapInputs.headingDeg - mapInputs.viewRotationDeg).toFloat()
        when (inputs.style.egoStyle) {
            EgoStyle.TRIANGLE -> drawEgoMarkerAt(cx = cx, cy = cy, color = inputs.egoColor, rotationDeg = rotationDeg)
            EgoStyle.HEX -> drawHexEgoMarkerAt(cx = cx, cy = cy, color = inputs.egoColor, rotationDeg = rotationDeg)
        }
    }
}

@Composable
private fun sweepArmCanvas(sweep: SweepOverlay) {
    Canvas(modifier = Modifier.fillMaxSize()) { drawSweepArm(sweep) }
}

@Composable
private fun contactsCanvas(
    pois: List<PlayaPoi>,
    projection: PlayaProjection,
    viewport: PlayaViewport?,
    overlay: ContactsOverlay,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val vp = viewport ?: return@Canvas
        drawContacts(pois, projection, vp, overlay)
    }
}

@Composable
private fun routeCanvas(
    egoFix: GpsFix?,
    routeM: List<PlayaPoint>,
    projection: PlayaProjection,
    viewport: PlayaViewport?,
    color: Color,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val vp = viewport ?: return@Canvas
        drawRoute(egoFix, routeM, projection, vp, color)
    }
}

private const val DEFAULT_MAX_CONTACTS: Int = 40

/**
 * Concept-C "ping" overlay: re-blit the same cached [ProjectedMap] in the
 * sweep's lit palette, clipped to the rotating wedge. The cache is what
 * makes this affordable on Fire-class devices — every frame we'd
 * otherwise be re-projecting ~600 streets twice.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSweptProjectedMap(
    projected: ProjectedMap,
    sweep: SweepOverlay,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.width.coerceAtMost(size.height)
    // Read the animated sweep angle here, inside the draw scope, so only the
    // draw phase re-runs each frame — not recomposition of the concept tree.
    val deg = sweep.sweepDeg()
    val wedge =
        wedgePath(
            cx = cx,
            cy = cy,
            radius = radius,
            startDeg = deg - sweep.sweepWidthDeg,
            endDeg = deg,
        )
    clipPath(wedge) {
        drawProjectedMap(projected, sweep.litPalette)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSweepArm(sweep: SweepOverlay) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.width.coerceAtMost(size.height) / 2f * 0.96f

    // Forward detection cone (heading-aligned, drawn straight up since the
    // map already rotates track-up). Solid translucent fill plus edge lines.
    val coneHalf = sweep.coneFwdDeg / 2f
    val coneStartDeg = -coneHalf
    val coneEndDeg = coneHalf
    val cone = wedgePath(cx, cy, radius * 2f, coneStartDeg, coneEndDeg)
    drawPath(path = cone, color = sweep.coneFill)
    drawLine(
        color = sweep.armColor,
        start = Offset(cx, cy),
        end =
            Offset(
                cx + radius * cos(Math.toRadians(coneStartDeg - 90.0)).toFloat(),
                cy + radius * sin(Math.toRadians(coneStartDeg - 90.0)).toFloat(),
            ),
        strokeWidth = 1.4f,
    )
    drawLine(
        color = sweep.armColor,
        start = Offset(cx, cy),
        end =
            Offset(
                cx + radius * cos(Math.toRadians(coneEndDeg - 90.0)).toFloat(),
                cy + radius * sin(Math.toRadians(coneEndDeg - 90.0)).toFloat(),
            ),
        strokeWidth = 1.4f,
    )

    // Leading edge of the sweep — the bright arm. Read the animated angle in
    // the draw scope so the frame ticker invalidates draw, not composition.
    val armA = Math.toRadians((sweep.sweepDeg() - 90.0))
    drawLine(
        color = sweep.armColor,
        start = Offset(cx, cy),
        end = Offset((cx + radius * cos(armA)).toFloat(), (cy + radius * sin(armA)).toFloat()),
        strokeWidth = 2.4f,
    )

    // Concentric range rings — drawn last so they overlay both the dim and
    // lit map to give the scope its M41A reticle look.
    val rings = 5
    for (i in 1..rings) {
        drawCircle(
            color = sweep.armColor.copy(alpha = 0.35f),
            radius = radius * i / rings,
            center = Offset(cx, cy),
            style = Stroke(width = 1f),
        )
    }
}

private fun wedgePath(
    cx: Float,
    cy: Float,
    radius: Float,
    startDeg: Float,
    endDeg: Float,
): Path {
    val path = Path()
    path.moveTo(cx, cy)
    val steps = 24
    for (i in 0..steps) {
        val t = startDeg + (endDeg - startDeg) * i / steps
        val a = Math.toRadians(t.toDouble() - 90.0)
        path.lineTo(
            (cx + radius * cos(a)).toFloat(),
            (cy + radius * sin(a)).toFloat(),
        )
    }
    path.close()
    return path
}
