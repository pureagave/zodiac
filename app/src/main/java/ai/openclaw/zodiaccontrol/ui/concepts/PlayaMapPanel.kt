package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.ui.playamap.MapPalette
import ai.openclaw.zodiaccontrol.ui.playamap.ProjectedMap
import ai.openclaw.zodiaccontrol.ui.playamap.cockpitTouchInput
import ai.openclaw.zodiaccontrol.ui.playamap.drawEgoMarkerAt
import ai.openclaw.zodiaccontrol.ui.playamap.drawHexEgoMarkerAt
import ai.openclaw.zodiaccontrol.ui.playamap.drawProjectedMap
import ai.openclaw.zodiaccontrol.ui.playamap.drawRetroGrid
import ai.openclaw.zodiaccontrol.ui.playamap.project
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
)

/**
 * Optional sweep overlay (concept C). [sweepDeg] is the *leading edge* of the
 * wedge in screen-space degrees clockwise from straight up; the wedge spans
 * [sweepWidthDeg] degrees behind it. The map is re-drawn in [litPalette]
 * clipped to the wedge so features visibly brighten as the arm passes — the
 * canonical M41A "ping" effect.
 */
data class SweepOverlay(
    val sweepDeg: Float,
    val sweepWidthDeg: Float,
    val litPalette: MapPalette,
    val armColor: Color,
    val coneFwdDeg: Float,
    val coneFill: Color,
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
private val PLAYA_PROJECTION = PlayaProjection(GoldenSpike.Y2025)

/**
 * Shared BRC map viewport for concepts B / C / D. Wraps the existing
 * `cockpitTouchInput` (pan + pinch), projects the map at the live heading and
 * zoom, optionally tilts via graphicsLayer, and overlays the ego marker.
 *
 * Concept A keeps its own inline implementation in `CRTVectorScreen` —
 * intentionally untouched per the directive to leave A as-is.
 *
 * @param allowTilt if true, the panel honours [CockpitUiState.mapMode]; if
 *   false (concept C) the panel is always top-down regardless of mode.
 * @param clipCircular wraps the whole panel in a circular clip — the M41A
 *   look. Touch input still hits the bounding rect.
 * @param showRetroGrid draws the meter-space backdrop grid before the BRC
 *   features. Auto-enabled in TILT; concepts can also force it on (e.g. B in
 *   TOP for the perspective vibe).
 */
@Composable
fun playaMapPanel(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    style: PlayaMapPanelStyle,
    modifier: Modifier = Modifier,
) {
    val projection = remember { PLAYA_PROJECTION }
    val pixelsPerMeter = state.pixelsPerMeter
    val tilt = style.allowTilt && state.mapMode == MapMode.TILT
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
            state.egoFix?.location,
            state.cameraOverride,
            state.viewRotationDeg,
            state.pixelsPerMeter,
            state.tiltDeg,
            tilt,
            canvasSize,
        ) {
            if (canvasSize.width <= 0 || canvasSize.height <= 0) {
                null
            } else {
                viewportFor(state, projection, tilt, canvasSize.width, canvasSize.height)
            }
        }

    // Project every BRC feature through [viewport] once per camera-state
    // change. ~600 streets × ~tens of vertices each is the dominant cost
    // when this is done per frame; with the cache, panning at 60 fps
    // reuses the same projection across all frames in a GPS tick window.
    val projected: ProjectedMap? =
        remember(state.playaMap, viewport) {
            val map = state.playaMap
            if (map != null && viewport != null) map.project(projection, viewport) else null
        }

    Box(
        modifier =
            modifier
                .then(if (style.clipCircular) Modifier.clip(androidx.compose.foundation.shape.CircleShape) else Modifier)
                .onSizeChanged { canvasSize = it }
                .cockpitTouchInput(
                    currentZoom = { pixelsPerMeter },
                    onPan = { dxScreen, dyScreen ->
                        // Convert screen-pixel delta to world metres using
                        // the *display* rotation, not the ego's heading —
                        // in FREE the user has rotated the display
                        // independently of heading, and pan must move along
                        // the visible axes.
                        val h = Math.toRadians(state.viewRotationDeg)
                        val cosH = cos(h)
                        val sinH = sin(h)
                        val dE = (-dxScreen * cosH + dyScreen * sinH) / pixelsPerMeter
                        val dN = (dxScreen * sinH + dyScreen * cosH) / pixelsPerMeter
                        viewModel.panBy(dE, dN)
                    },
                    onZoom = viewModel::setPixelsPerMeter,
                    onRotate = viewModel::nudgeViewRotation,
                ),
    ) {
        mapBaseCanvas(viewport = viewport, projected = projected, style = style, tilt = tilt, tiltDeg = state.tiltDeg)
        egoOverlayCanvas(
            EgoOverlayInputs(
                state = state,
                projection = projection,
                viewport = viewport,
                style = style,
                tilt = tilt,
                egoColor = egoColor,
            ),
        )
        style.sweep?.let { sweepArmCanvas(it) }
    }
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
    state: CockpitUiState,
    projection: PlayaProjection,
    tilt: Boolean,
    widthPx: Int,
    heightPx: Int,
): PlayaViewport {
    val ego = state.egoFix?.let { projection.project(it.location) } ?: PlayaPoint(0.0, 0.0)
    val cameraCenter = state.cameraOverride ?: ego
    val anchorYFrac = if (tilt) TILT_ANCHOR_Y else TOP_ANCHOR_Y
    return PlayaViewport(
        center = cameraCenter,
        headingDeg = state.viewRotationDeg,
        pixelsPerMeter = if (tilt) state.pixelsPerMeter * TILT_ZOOM_BOOST else state.pixelsPerMeter,
        widthPx = widthPx,
        heightPx = heightPx,
        anchorYFrac = anchorYFrac,
    )
}

@Composable
private fun mapBaseCanvas(
    viewport: PlayaViewport?,
    projected: ProjectedMap?,
    style: PlayaMapPanelStyle,
    tilt: Boolean,
    tiltDeg: Int,
) {
    Canvas(
        modifier =
            if (tilt) {
                Modifier.fillMaxSize().graphicsLayer {
                    rotationX = tiltDeg.toFloat()
                    cameraDistance = TILT_CAMERA_DISTANCE * density
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
            } else {
                Modifier.fillMaxSize()
            },
    ) {
        if (viewport == null) return@Canvas
        if (style.showRetroGrid || tilt) drawRetroGrid(viewport, style.palette.grid)
        if (projected != null) {
            drawProjectedMap(projected, style.palette, viewport.pixelsPerMeter)
            style.sweep?.let { drawSweptProjectedMap(projected, it, viewport.pixelsPerMeter) }
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
    val state: CockpitUiState,
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
        val state = inputs.state
        val viewport = inputs.viewport
        val tilt = inputs.tilt
        val ego =
            if (viewport == null) {
                null
            } else {
                state.egoFix?.location?.let { viewport.toScreen(inputs.projection.project(it)) }
            }
        val cx = ego?.x?.toFloat() ?: (size.width / 2f)
        val cy = ego?.y?.toFloat() ?: (size.height * (if (tilt) TILT_ANCHOR_Y else TOP_ANCHOR_Y).toFloat())
        val rotationDeg = (state.headingDeg - state.viewRotationDeg).toFloat()
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

/**
 * Concept-C "ping" overlay: re-blit the same cached [ProjectedMap] in the
 * sweep's lit palette, clipped to the rotating wedge. The cache is what
 * makes this affordable on Fire-class devices — every frame we'd
 * otherwise be re-projecting ~600 streets twice.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSweptProjectedMap(
    projected: ProjectedMap,
    sweep: SweepOverlay,
    pixelsPerMeter: Double,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.width.coerceAtMost(size.height)
    val wedge =
        wedgePath(
            cx = cx,
            cy = cy,
            radius = radius,
            startDeg = sweep.sweepDeg - sweep.sweepWidthDeg,
            endDeg = sweep.sweepDeg,
        )
    clipPath(wedge) {
        drawProjectedMap(projected, sweep.litPalette, pixelsPerMeter)
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

    // Leading edge of the sweep — the bright arm.
    val armA = Math.toRadians((sweep.sweepDeg - 90.0))
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
