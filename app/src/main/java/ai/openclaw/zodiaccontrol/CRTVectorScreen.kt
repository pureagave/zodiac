package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.ui.concepts.ThemeCrtVector
import ai.openclaw.zodiaccontrol.ui.concepts.conceptSwitcher
import ai.openclaw.zodiaccontrol.ui.concepts.navCueBar
import ai.openclaw.zodiaccontrol.ui.concepts.recenterButton
import ai.openclaw.zodiaccontrol.ui.playamap.ProjectedMap
import ai.openclaw.zodiaccontrol.ui.playamap.cockpitTouchInput
import ai.openclaw.zodiaccontrol.ui.playamap.drawEgoMarkerAt
import ai.openclaw.zodiaccontrol.ui.playamap.drawProjectedMap
import ai.openclaw.zodiaccontrol.ui.playamap.drawRetroGrid
import ai.openclaw.zodiaccontrol.ui.playamap.project
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import ai.openclaw.zodiaccontrol.ui.wrapHeading
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.sin

private val Bg = Color(0xFF000000)
private val VectorGreen = Color(0xFF00FF66)
private val ElectricBlue = Color(0xFF00BFFF)
private val Amber = Color(0xFFFFD166)

private const val TILT_CAMERA_DISTANCE: Float = 8f

/**
 * Vertical anchor (fraction of viewport height) where the ego's GPS fix
 * projects on the canvas. Map and ego use the *same* anchor so the marker
 * draws exactly where its real-world location sits on the rendered map —
 * panning slides ego and map together. TILT keeps the arcade-racer
 * lower-third framing; TOP centres ego in the viewport.
 */
private const val TILT_ANCHOR_Y: Double = 0.78
private const val TOP_ANCHOR_Y: Double = 0.5
private const val TILT_ZOOM_BOOST: Double = 1.0
private val PLAYA_PROJECTION = PlayaProjection(GoldenSpike.Y2025)

@Composable
fun crtVectorScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit = {},
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(12.dp)
                .border(2.dp, VectorGreen),
    ) {
        scanLineOverlay()

        Column(Modifier.fillMaxSize()) {
            topHeader(state = state)
            Spacer(Modifier.height(6.dp))
            navCueBar(cue = state.navCue, theme = ThemeCrtVector)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxSize()) {
                leftRail(
                    mapMode = state.mapMode,
                    onToggleMapMode = {
                        viewModel.setMapMode(
                            if (state.mapMode == MapMode.TOP) MapMode.TILT else MapMode.TOP,
                        )
                    },
                )
                Spacer(Modifier.width(10.dp))
                centerViewport(
                    modifier = Modifier.weight(1f),
                    state = state,
                    onPan = viewModel::panBy,
                    onZoom = viewModel::setPixelsPerMeter,
                    onRotate = viewModel::nudgeViewRotation,
                )
                Spacer(Modifier.width(10.dp))
                rightRail(
                    state = state,
                    callbacks =
                        RightRailCallbacks(
                            onSelectTransport = viewModel::selectTransport,
                            onConnect = { viewModel.setTransportConnected(true) },
                            onDisconnect = { viewModel.setTransportConnected(false) },
                            onSelectLocationSource = viewModel::selectLocationSource,
                            chips =
                                ChipControls(
                                    onSetHeading = viewModel::setHeading,
                                    onSetSpeed = viewModel::setSpeed,
                                    onSetTilt = viewModel::setTiltDeg,
                                    onRecenter = viewModel::recenterPan,
                                ),
                        ),
                )
            }
        }

        Text(
            text = "ZODIAC CONTROL // CRT VECTOR",
            color = VectorGreen,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )

        conceptSwitcher(
            current = state.concept,
            onCycle = onCycleConcept,
            accent = ThemeCrtVector.accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )

        recenterButton(
            followMode = state.followMode,
            theme = ThemeCrtVector,
            onClick = viewModel::recenterPan,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
    }
}

@Composable
private fun topHeader(state: CockpitUiState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, VectorGreen)
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        headerText("MODE: ${state.mode.name}")
        headerText("HDG: ${state.headingDeg}°")
        headerText("VEL: ${state.speedKph} kph")
        headerText("THERM: ${state.thermalC}C")
    }
}

@Composable
private fun headerText(value: String) {
    Text(
        text = value,
        color = VectorGreen,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    )
}

@Composable
private fun leftRail(
    mapMode: MapMode,
    onToggleMapMode: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(190.dp)
                .border(1.dp, ElectricBlue)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(6) { idx ->
            val isMapToggle = idx == MAP_TOGGLE_IDX
            val label = if (isMapToggle) "MAP: ${mapMode.name}" else "SYS-${idx + 1}"
            val tint = if (isMapToggle && mapMode == MapMode.TILT) Amber else VectorGreen
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.dp, tint, RoundedCornerShape(4.dp))
                    .then(if (isMapToggle) Modifier.clickable(onClick = onToggleMapMode) else Modifier)
                    .padding(8.dp),
            ) {
                Text(
                    text = label,
                    color = tint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private const val MAP_TOGGLE_IDX: Int = 2
private const val HDG_STEP_BIG: Int = 15
private const val SPD_STEP_BIG: Int = 10
private const val TILT_STEP_BIG: Int = 10

data class RightRailCallbacks(
    val onSelectTransport: (TransportType) -> Unit,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onSelectLocationSource: (LocationSourceType) -> Unit,
    val chips: ChipControls,
)

data class ChipControls(
    val onSetHeading: (Int) -> Unit,
    val onSetSpeed: (Int) -> Unit,
    val onSetTilt: (Int) -> Unit,
    val onRecenter: () -> Unit,
)

@Composable
private fun rightRail(
    state: CockpitUiState,
    callbacks: RightRailCallbacks,
) {
    val modeLine =
        when (state.mode) {
            CockpitMode.DIAGNOSTIC -> "> MODE: DIAGNOSTIC"
            CockpitMode.DRIVE -> "> MODE: DRIVE"
            CockpitMode.COMBAT -> "> MODE: COMBAT"
        }

    val connectionLabel =
        when (state.connectionPhase) {
            ConnectionPhase.DISCONNECTED -> "DISCONNECTED"
            ConnectionPhase.CONNECTING -> "CONNECTING"
            ConnectionPhase.CONNECTED -> "CONNECTED"
            ConnectionPhase.ERROR -> "ERROR"
        }

    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(235.dp)
                .border(1.dp, ElectricBlue)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "> TRANSPORT",
            color = Amber,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            transportChip(
                label = "BLE",
                selected = state.selectedTransport == TransportType.BLE,
                onClick = { callbacks.onSelectTransport(TransportType.BLE) },
            )
            transportChip(
                label = "USB",
                selected = state.selectedTransport == TransportType.USB,
                onClick = { callbacks.onSelectTransport(TransportType.USB) },
            )
            transportChip(
                label = "WIFI",
                selected = state.selectedTransport == TransportType.WIFI,
                onClick = { callbacks.onSelectTransport(TransportType.WIFI) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actionChip(label = "CONNECT", onClick = callbacks.onConnect)
            actionChip(label = "DISCONNECT", onClick = callbacks.onDisconnect)
        }

        Text(
            text = "> GPS",
            color = Amber,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            transportChip(
                label = "FAKE",
                selected = state.selectedLocationSource == LocationSourceType.FAKE,
                onClick = { callbacks.onSelectLocationSource(LocationSourceType.FAKE) },
            )
            transportChip(
                label = "GPS",
                selected = state.selectedLocationSource == LocationSourceType.SYSTEM,
                onClick = { callbacks.onSelectLocationSource(LocationSourceType.SYSTEM) },
            )
            transportChip(
                label = "BLE",
                selected = state.selectedLocationSource == LocationSourceType.BLE,
                onClick = { callbacks.onSelectLocationSource(LocationSourceType.BLE) },
            )
            transportChip(
                label = "USB",
                selected = state.selectedLocationSource == LocationSourceType.USB,
                onClick = { callbacks.onSelectLocationSource(LocationSourceType.USB) },
            )
        }

        Text(
            text = "> HDG SET: ${state.headingDeg}°",
            color = Amber,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            transportChip(
                label = "-15",
                selected = false,
                onClick = { callbacks.chips.onSetHeading(wrapHeading(state.headingDeg - HDG_STEP_BIG)) },
            )
            transportChip(
                label = "-1",
                selected = false,
                onClick = { callbacks.chips.onSetHeading(wrapHeading(state.headingDeg - 1)) },
            )
            transportChip(
                label = "+1",
                selected = false,
                onClick = { callbacks.chips.onSetHeading(wrapHeading(state.headingDeg + 1)) },
            )
            transportChip(
                label = "+15",
                selected = false,
                onClick = { callbacks.chips.onSetHeading(wrapHeading(state.headingDeg + HDG_STEP_BIG)) },
            )
        }

        Text(
            text = "> SPD SET: ${state.speedKph} kph",
            color = Amber,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            transportChip(
                label = "-10",
                selected = false,
                onClick = { callbacks.chips.onSetSpeed(state.speedKph - SPD_STEP_BIG) },
            )
            transportChip(
                label = "-1",
                selected = false,
                onClick = { callbacks.chips.onSetSpeed(state.speedKph - 1) },
            )
            transportChip(
                label = "+1",
                selected = false,
                onClick = { callbacks.chips.onSetSpeed(state.speedKph + 1) },
            )
            transportChip(
                label = "+10",
                selected = false,
                onClick = { callbacks.chips.onSetSpeed(state.speedKph + SPD_STEP_BIG) },
            )
        }

        Text(
            text = "> TILT: ${state.tiltDeg}°",
            color = Amber,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            transportChip(
                label = "-10",
                selected = false,
                onClick = { callbacks.chips.onSetTilt(state.tiltDeg - TILT_STEP_BIG) },
            )
            transportChip(
                label = "-1",
                selected = false,
                onClick = { callbacks.chips.onSetTilt(state.tiltDeg - 1) },
            )
            transportChip(
                label = "+1",
                selected = false,
                onClick = { callbacks.chips.onSetTilt(state.tiltDeg + 1) },
            )
            transportChip(
                label = "+10",
                selected = false,
                onClick = { callbacks.chips.onSetTilt(state.tiltDeg + TILT_STEP_BIG) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actionChip(label = "RECENTER MAP", onClick = callbacks.chips.onRecenter)
        }

        listOf(
            modeLine to Amber,
            "> LINK ${if (state.linkStable) "ESTABLISHED" else "LOST"}" to
                (if (state.linkStable) VectorGreen else Amber),
            "> CONN: $connectionLabel" to VectorGreen,
            "> THERMAL ${state.thermalC}C" to VectorGreen,
            locationLine(state.locationState),
            "> TOUCH INPUT ACTIVE" to VectorGreen,
            "> NO PROTOCOL BINDING (V1)" to Amber,
        ).forEach { (line, color) ->
            Text(
                text = line,
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun locationLine(state: LocationSourceState): Pair<String, Color> =
    when (state) {
        LocationSourceState.Disconnected -> "> GPS: OFFLINE" to Amber
        LocationSourceState.Searching -> "> GPS: SEARCHING" to Amber
        is LocationSourceState.Active -> {
            val lat = "%.5f".format(state.fix.location.lat)
            val lon = "%.5f".format(state.fix.location.lon)
            "> GPS: $lat $lon" to VectorGreen
        }
        is LocationSourceState.Error -> "> GPS: ERR ${state.detail}" to Amber
    }

@Composable
private fun transportChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .border(1.dp, if (selected) Amber else VectorGreen)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Amber else VectorGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun actionChip(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .border(1.dp, ElectricBlue)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = ElectricBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun centerViewport(
    modifier: Modifier,
    state: CockpitUiState,
    onPan: (Double, Double) -> Unit,
    onZoom: (Double) -> Unit,
    onRotate: (Float) -> Unit,
) {
    val projection = remember { PLAYA_PROJECTION }
    val pixelsPerMeter = state.pixelsPerMeter
    val tilt = state.mapMode == MapMode.TILT

    // Cache key plumbing — measure the box at Composable scope, build the
    // viewport once per state-or-size change, and project the BRC map only
    // when that viewport (or the map itself) changes. ~600 streets × tens
    // of vertices each is the dominant frame cost without the cache; with
    // it, panning at 60 fps reuses the same projection across all frames
    // in a GPS tick window.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
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
                buildViewport(state, projection, tilt, canvasSize.width, canvasSize.height)
            }
        }
    val projected: ProjectedMap? =
        remember(state.playaMap, viewport) {
            val map = state.playaMap
            if (map != null && viewport != null) map.project(projection, viewport) else null
        }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .border(1.dp, VectorGreen)
                .padding(8.dp)
                .onSizeChanged { canvasSize = it }
                .cockpitTouchInput(
                    currentZoom = { pixelsPerMeter },
                    onPan = { dxScreen, dyScreen ->
                        // Use the *display* rotation, not the heading, so a
                        // pan after a two-finger twist moves the camera
                        // along the visible axes — standard map-app feel.
                        val h = Math.toRadians(state.viewRotationDeg)
                        val cosH = cos(h)
                        val sinH = sin(h)
                        val ppm = pixelsPerMeter
                        val dE = (-dxScreen * cosH + dyScreen * sinH) / ppm
                        val dN = (dxScreen * sinH + dyScreen * cosH) / ppm
                        onPan(dE, dN)
                    },
                    onZoom = onZoom,
                    onRotate = onRotate,
                ),
    ) {
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
            if (viewport == null) return@Canvas
            if (tilt) drawRetroGrid(viewport)
            if (projected != null) {
                drawProjectedMap(projected, ai.openclaw.zodiaccontrol.ui.playamap.MapPalette.Default, viewport.pixelsPerMeter)
            } else {
                drawCircle(
                    color = VectorGreen,
                    radius = 6f,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = 1f),
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (viewport == null) return@Canvas
            val ego = state.egoFix?.location?.let { viewport.toScreen(projection.project(it)) }
            val cx = ego?.x?.toFloat() ?: (size.width / 2f)
            val cy = ego?.y?.toFloat() ?: (size.height * (if (tilt) TILT_ANCHOR_Y else TOP_ANCHOR_Y).toFloat())
            // Marker rotation = ego heading − display rotation. 0 in
            // TRACK_UP (heading at top, marker points up); non-zero in
            // FREE after a two-finger rotate (display turned independently
            // of the ego's physical motion direction).
            val rotationDeg = (state.headingDeg - state.viewRotationDeg).toFloat()
            drawEgoMarkerAt(cx = cx, cy = cy, rotationDeg = rotationDeg)
        }
    }
}

/**
 * Single source of truth for the viewport used by both the map base canvas
 * and the ego overlay. Camera position comes from [CockpitUiState.cameraOverride]
 * in [ai.openclaw.zodiaccontrol.core.model.FollowMode.FREE] (an absolute
 * world point parked by the user) and the live ego fix in
 * [ai.openclaw.zodiaccontrol.core.model.FollowMode.TRACK_UP]; display
 * rotation is the user-controllable [CockpitUiState.viewRotationDeg].
 */
private fun buildViewport(
    state: CockpitUiState,
    projection: PlayaProjection,
    tilt: Boolean,
    widthPx: Int,
    heightPx: Int,
): PlayaViewport {
    val ego = state.egoFix?.let { projection.project(it.location) } ?: PlayaPoint(0.0, 0.0)
    val cameraCenter = state.cameraOverride ?: ego
    return PlayaViewport(
        center = cameraCenter,
        headingDeg = state.viewRotationDeg,
        pixelsPerMeter = if (tilt) state.pixelsPerMeter * TILT_ZOOM_BOOST else state.pixelsPerMeter,
        widthPx = widthPx,
        heightPx = heightPx,
        anchorYFrac = if (tilt) TILT_ANCHOR_Y else TOP_ANCHOR_Y,
    )
}

@Composable
private fun scanLineOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 4f
        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = Color(0x1100FF66),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += step
        }
    }
}
