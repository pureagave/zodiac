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
import ai.openclaw.zodiaccontrol.ui.playamap.EGO_ANCHOR_CENTER
import ai.openclaw.zodiaccontrol.ui.playamap.cockpitTouchInput
import ai.openclaw.zodiaccontrol.ui.playamap.drawEgoMarker
import ai.openclaw.zodiaccontrol.ui.playamap.drawPlayaMap
import ai.openclaw.zodiaccontrol.ui.playamap.drawRetroGrid
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.sin

private val Bg = Color(0xFF000000)
private val VectorGreen = Color(0xFF00FF66)
private val ElectricBlue = Color(0xFF00BFFF)
private val Amber = Color(0xFFFFD166)

private const val MAP_INITIAL_ZOOM: Double = 0.18
private const val TILT_CAMERA_DISTANCE: Float = 8f
private const val EGO_ANCHOR_TILT: Float = 0.78f
private const val MAP_ANCHOR_TILT: Double = 0.5
private const val TILT_ZOOM_BOOST: Double = 1.0
private val PLAYA_PROJECTION = PlayaProjection(GoldenSpike.Y2025)

@Composable
fun crtVectorScreen(viewModel: CockpitViewModel) {
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
                )
                Spacer(Modifier.width(10.dp))
                rightRail(
                    state = state,
                    callbacks =
                        RightRailCallbacks(
                            onSelectTransport = viewModel::selectTransport,
                            onConnect = viewModel::connectTransport,
                            onDisconnect = viewModel::disconnectTransport,
                            onSelectLocationSource = viewModel::selectLocationSource,
                            chips =
                                ChipControls(
                                    onSetHeading = viewModel::setHeading,
                                    onSetSpeed = viewModel::setSpeed,
                                    onSetTilt = viewModel::setTiltDeg,
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
) {
    val map = state.playaMap
    val projection = remember { PLAYA_PROJECTION }
    var pixelsPerMeter by remember { mutableDoubleStateOf(MAP_INITIAL_ZOOM) }
    var panEastM by remember { mutableDoubleStateOf(0.0) }
    var panNorthM by remember { mutableDoubleStateOf(0.0) }
    val tilt = state.mapMode == MapMode.TILT

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .border(1.dp, VectorGreen)
                .padding(8.dp)
                .cockpitTouchInput(
                    currentZoom = { pixelsPerMeter },
                    onPan = { dxScreen, dyScreen ->
                        val h = Math.toRadians(state.headingDeg.toDouble())
                        val cosH = cos(h)
                        val sinH = sin(h)
                        val ppm = pixelsPerMeter
                        panEastM += (-dxScreen * cosH + dyScreen * sinH) / ppm
                        panNorthM += (dxScreen * sinH + dyScreen * cosH) / ppm
                    },
                    onZoom = { pixelsPerMeter = it },
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
            val baseCenter =
                state.egoFix?.let { projection.project(it.location) }
                    ?: PlayaPoint(0.0, 0.0)
            val cameraCenter = PlayaPoint(baseCenter.eastM + panEastM, baseCenter.northM + panNorthM)
            val viewport =
                PlayaViewport(
                    center = cameraCenter,
                    headingDeg = state.headingDeg.toDouble(),
                    pixelsPerMeter = if (tilt) pixelsPerMeter * TILT_ZOOM_BOOST else pixelsPerMeter,
                    widthPx = size.width.toInt(),
                    heightPx = size.height.toInt(),
                    anchorYFrac = if (tilt) MAP_ANCHOR_TILT else 0.5,
                )
            if (tilt) drawRetroGrid(viewport)
            if (map != null) {
                drawPlayaMap(map = map, projection = projection, viewport = viewport)
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
            val viewport =
                PlayaViewport(
                    widthPx = size.width.toInt(),
                    heightPx = size.height.toInt(),
                )
            drawEgoMarker(
                viewport = viewport,
                anchorYFrac = if (tilt) EGO_ANCHOR_TILT else EGO_ANCHOR_CENTER,
            )
        }
    }
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
