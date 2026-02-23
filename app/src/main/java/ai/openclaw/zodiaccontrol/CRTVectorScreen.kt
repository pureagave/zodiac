package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.CockpitMode
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.min

private val Bg = Color(0xFF000000)
private val VectorGreen = Color(0xFF00FF66)
private val ElectricBlue = Color(0xFF00BFFF)
private val Amber = Color(0xFFFFD166)
private val DarkGreen = Color(0xFF0A3D1D)

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
            val horizon = size.height * 0.62f
            for (i in -12..12) {
                val x = size.width / 2 + i * (size.width / 26f)
                drawLine(DarkGreen, Offset(size.width / 2, horizon), Offset(x, size.height), strokeWidth = 1f)
            }
            for (j in 0..8) {
                val y = horizon + j * ((size.height - horizon) / 9f)
                drawLine(DarkGreen, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            val center = Offset(size.width / 2, size.height * 0.48f)
            val s = min(size.width, size.height) * 0.24f
            val pts =
                listOf(
                    Offset(-1.3f, -0.2f), Offset(-1.0f, -0.55f), Offset(-0.2f, -0.7f), Offset(0.55f, -0.7f),
                    Offset(1.15f, -0.45f), Offset(1.35f, -0.1f), Offset(1.2f, 0.2f), Offset(0.75f, 0.45f),
                    Offset(-0.7f, 0.5f), Offset(-1.2f, 0.25f),
                ).map { Offset(center.x + it.x * s, center.y + it.y * s) }

            for (i in pts.indices) {
                drawLine(VectorGreen, pts[i], pts[(i + 1) % pts.size], strokeWidth = 2f, cap = StrokeCap.Round)
            }

            val top = pts.take(8).map { Offset(it.x + s * 0.2f, it.y - s * 0.18f) }
            for (i in top.indices) {
                drawLine(VectorGreen, top[i], top[(i + 1) % top.size], strokeWidth = 1.3f)
                if (i < pts.size) drawLine(VectorGreen, pts[i], top[i], strokeWidth = 1f)
            }

            val leftWheel = Offset(center.x - 0.68f * s, center.y + 0.56f * s)
            val rightWheel = Offset(center.x + 0.55f * s, center.y + 0.56f * s)
            drawCircle(VectorGreen, radius = 0.23f * s, center = leftWheel, style = Stroke(width = 2f))
            drawCircle(VectorGreen, radius = 0.23f * s, center = rightWheel, style = Stroke(width = 2f))
            drawCircle(VectorGreen, radius = 0.13f * s, center = leftWheel, style = Stroke(width = 1.4f))
            drawCircle(VectorGreen, radius = 0.13f * s, center = rightWheel, style = Stroke(width = 1.4f))

            drawLine(
                Amber,
                Offset(center.x - 0.2f * s, center.y - 0.7f * s),
                Offset(center.x - 0.85f * s, center.y - 1.1f * s),
                strokeWidth = 1.4f,
            )
            drawLine(
                Amber,
                Offset(center.x + 0.55f * s, center.y - 0.2f * s),
                Offset(center.x + 1.15f * s, center.y - 0.45f * s),
                strokeWidth = 1.4f,
            )

            drawContext.canvas.nativeCanvas.apply {
                drawText(
                    "HDG ${state.headingDeg}°",
                    center.x - s,
                    center.y + s * 1.15f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(0, 255, 102)
                        textSize = 28f
                        typeface = android.graphics.Typeface.MONOSPACE
                    },
                )
                drawText(
                    "SPD ${state.speedKph} kph",
                    center.x + s * 0.2f,
                    center.y + s * 1.15f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(255, 209, 102)
                        textSize = 28f
                        typeface = android.graphics.Typeface.MONOSPACE
                    },
                )
            }
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
