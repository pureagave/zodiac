package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaPoint
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.geo.PlayaViewport
import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.ui.playamap.drawPlayaMap
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Bg = Color(0xFF000000)
private val VectorGreen = Color(0xFF00FF66)
private val ElectricBlue = Color(0xFF00BFFF)
private val Amber = Color(0xFFFFD166)

private const val MAP_PIXELS_PER_METER = 0.18
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
                leftRail()
                Spacer(Modifier.width(10.dp))
                centerViewport(
                    modifier = Modifier.weight(1f),
                    state = state,
                    onHeadingChange = viewModel::setHeading,
                    onSpeedChange = viewModel::setSpeed,
                )
                Spacer(Modifier.width(10.dp))
                rightRail(
                    state = state,
                    onSelectTransport = viewModel::selectTransport,
                    onConnect = viewModel::connectTransport,
                    onDisconnect = viewModel::disconnectTransport,
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
private fun leftRail() {
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.dp, VectorGreen, RoundedCornerShape(4.dp))
                    .padding(8.dp),
            ) {
                Text(
                    text = "SYS-${idx + 1}",
                    color = VectorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun rightRail(
    state: CockpitUiState,
    onSelectTransport: (TransportType) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
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
                onClick = { onSelectTransport(TransportType.BLE) },
            )
            transportChip(
                label = "USB",
                selected = state.selectedTransport == TransportType.USB,
                onClick = { onSelectTransport(TransportType.USB) },
            )
            transportChip(
                label = "WIFI",
                selected = state.selectedTransport == TransportType.WIFI,
                onClick = { onSelectTransport(TransportType.WIFI) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actionChip(label = "CONNECT", onClick = onConnect)
            actionChip(label = "DISCONNECT", onClick = onDisconnect)
        }

        listOf(
            modeLine to Amber,
            "> LINK ${if (state.linkStable) "ESTABLISHED" else "LOST"}" to
                (if (state.linkStable) VectorGreen else Amber),
            "> CONN: $connectionLabel" to VectorGreen,
            "> THERMAL ${state.thermalC}C" to VectorGreen,
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
    onHeadingChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
) {
    val map = state.playaMap
    val projection = remember { PLAYA_PROJECTION }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .border(1.dp, VectorGreen)
                .padding(8.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.pressed) {
                                    val nx = change.position.x / size.width
                                    val ny = change.position.y / size.height
                                    onHeadingChange((nx * 359f).toInt())
                                    onSpeedChange((ny * 120f).toInt())
                                }
                            }
                        }
                    },
        ) {
            if (map != null) {
                val viewport =
                    PlayaViewport(
                        center = PlayaPoint(0.0, 0.0),
                        headingDeg = state.headingDeg.toDouble(),
                        pixelsPerMeter = MAP_PIXELS_PER_METER,
                        widthPx = size.width.toInt(),
                        heightPx = size.height.toInt(),
                    )
                drawPlayaMap(map = map, projection = projection, viewport = viewport)
            } else {
                drawNoMapPlaceholder()
            }
        }
    }
}

private fun DrawScope.drawNoMapPlaceholder() {
    drawCircle(
        color = VectorGreen,
        radius = 6f,
        center = Offset(size.width / 2f, size.height / 2f),
        style = Stroke(width = 1f),
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
