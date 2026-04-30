package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.ui.playamap.EGO_ANCHOR_CENTER
import ai.openclaw.zodiaccontrol.ui.playamap.MapPalette
import ai.openclaw.zodiaccontrol.ui.playamap.cockpitTouchInput
import ai.openclaw.zodiaccontrol.ui.playamap.drawEgoMarker
import ai.openclaw.zodiaccontrol.ui.playamap.drawHexEgoMarker
import ai.openclaw.zodiaccontrol.ui.playamap.drawPlayaMap
import ai.openclaw.zodiaccontrol.ui.playamap.drawRetroGrid
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
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
private const val EGO_ANCHOR_TILT: Float = 0.78f
private const val MAP_ANCHOR_TILT: Double = 0.5
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

    Box(
        modifier =
            modifier
                .then(if (style.clipCircular) Modifier.clip(androidx.compose.foundation.shape.CircleShape) else Modifier)
                .cockpitTouchInput(
                    currentZoom = { pixelsPerMeter },
                    onPan = { dxScreen, dyScreen ->
                        val h = Math.toRadians(state.headingDeg.toDouble())
                        val cosH = cos(h)
                        val sinH = sin(h)
                        val dE = (-dxScreen * cosH + dyScreen * sinH) / pixelsPerMeter
                        val dN = (dxScreen * sinH + dyScreen * cosH) / pixelsPerMeter
                        viewModel.panBy(dE, dN)
                    },
                    onZoom = viewModel::setPixelsPerMeter,
                ),
    ) {
        mapBaseCanvas(state = state, projection = projection, style = style, tilt = tilt)
        egoOverlayCanvas(style = style, tilt = tilt, egoColor = egoColor)
        style.sweep?.let { sweepArmCanvas(it) }
    }
}

@Composable
private fun mapBaseCanvas(
    state: CockpitUiState,
    projection: PlayaProjection,
    style: PlayaMapPanelStyle,
    tilt: Boolean,
) {
    val anchorYFrac = if (tilt) MAP_ANCHOR_TILT else 0.5
    Canvas(
        modifier =
            if (tilt) {
                Modifier.fillMaxSize().graphicsLayer {
                    rotationX = state.tiltDeg.toFloat()
                    cameraDistance = TILT_CAMERA_DISTANCE * density
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
            } else {
                Modifier.fillMaxSize()
            },
    ) {
        val baseCenter = state.egoFix?.let { projection.project(it.location) } ?: PlayaPoint(0.0, 0.0)
        val viewport =
            PlayaViewport(
                center = PlayaPoint(baseCenter.eastM + state.panEastM, baseCenter.northM + state.panNorthM),
                headingDeg = state.headingDeg.toDouble(),
                pixelsPerMeter = if (tilt) state.pixelsPerMeter * TILT_ZOOM_BOOST else state.pixelsPerMeter,
                widthPx = size.width.toInt(),
                heightPx = size.height.toInt(),
                anchorYFrac = anchorYFrac,
            )
        if (style.showRetroGrid || tilt) drawRetroGrid(viewport, style.palette.grid)
        val map = state.playaMap
        if (map != null) {
            drawPlayaMap(map = map, projection = projection, viewport = viewport, palette = style.palette)
            style.sweep?.let { drawSweptMap(viewport, projection, map, it) }
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

@Composable
private fun egoOverlayCanvas(
    style: PlayaMapPanelStyle,
    tilt: Boolean,
    egoColor: Color,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val viewport = PlayaViewport(widthPx = size.width.toInt(), heightPx = size.height.toInt())
        val anchor = if (tilt) EGO_ANCHOR_TILT else EGO_ANCHOR_CENTER
        when (style.egoStyle) {
            EgoStyle.TRIANGLE -> drawEgoMarker(viewport = viewport, anchorYFrac = anchor, color = egoColor)
            EgoStyle.HEX -> drawHexEgoMarker(viewport = viewport, anchorYFrac = anchor, color = egoColor)
        }
    }
}

@Composable
private fun sweepArmCanvas(sweep: SweepOverlay) {
    Canvas(modifier = Modifier.fillMaxSize()) { drawSweepArm(sweep) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSweptMap(
    viewport: PlayaViewport,
    projection: PlayaProjection,
    map: ai.openclaw.zodiaccontrol.core.model.PlayaMap,
    sweep: SweepOverlay,
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
        drawPlayaMap(map = map, projection = projection, viewport = viewport, palette = sweep.litPalette)
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
