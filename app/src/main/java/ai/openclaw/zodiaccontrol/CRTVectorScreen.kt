package ai.openclaw.zodiaccontrol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val Bg = Color(0xFF000000)
private val VectorGreen = Color(0xFF00FF66)
private val ElectricBlue = Color(0xFF00BFFF)
private val Amber = Color(0xFFFFD166)
private val DarkGreen = Color(0xFF0A3D1D)

@Composable
fun CRTVectorScreen() {
    var heading by remember { mutableFloatStateOf(42f) }
    var speed by remember { mutableFloatStateOf(28f) }
    var mode by remember { mutableStateOf("DIAGNOSTIC") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(12.dp)
            .border(2.dp, VectorGreen)
    ) {
        ScanLineOverlay()

        Column(Modifier.fillMaxSize()) {
            TopHeader(mode = mode, heading = heading, speed = speed)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxSize()) {
                LeftRail()
                Spacer(Modifier.width(10.dp))
                CenterViewport(
                    modifier = Modifier.weight(1f),
                    heading = heading,
                    onHeadingChange = { heading = it },
                    speed = speed,
                    onSpeedChange = { speed = it }
                )
                Spacer(Modifier.width(10.dp))
                RightRail()
            }
        }

        Text(
            text = "ZODIAC CONTROL // CRT VECTOR",
            color = VectorGreen,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        )

        Text(
            text = "DOUBLE TAP MODE TO CYCLE",
            color = Amber,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .pointerInteropFilter {
                    if (it.action == android.view.MotionEvent.ACTION_DOWN && it.eventTime - it.downTime < 180) {
                        mode = when (mode) {
                            "DIAGNOSTIC" -> "DRIVE"
                            "DRIVE" -> "COMBAT"
                            else -> "DIAGNOSTIC"
                        }
                    }
                    true
                }
        )
    }
}

@Composable
private fun TopHeader(mode: String, heading: Float, speed: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.dp, VectorGreen)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderText("MODE: $mode")
        HeaderText("HDG: ${heading.toInt()}°")
        HeaderText("VEL: ${speed.toInt()} kph")
        HeaderText("LINK: STABLE")
    }
}

@Composable
private fun HeaderText(value: String) {
    Text(
        text = value,
        color = VectorGreen,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    )
}

@Composable
private fun LeftRail() {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(190.dp)
            .border(1.dp, ElectricBlue)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(6) { idx ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.dp, VectorGreen, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "SYS-${idx + 1}",
                    color = VectorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun RightRail() {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(235.dp)
            .border(1.dp, ElectricBlue)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            "> MODE: DIAGNOSTIC" to Amber,
            "> LINK ESTABLISHED" to VectorGreen,
            "> VECTOR CORE READY" to VectorGreen,
            "> THERMAL NOMINAL" to VectorGreen,
            "> TOUCH INPUT ACTIVE" to VectorGreen,
            "> NO PROTOCOL BINDING (V1)" to Amber
        ).forEach { (line, color) ->
            Text(
                text = line,
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun CenterViewport(
    modifier: Modifier,
    heading: Float,
    onHeadingChange: (Float) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .border(1.dp, VectorGreen)
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            val nx = change.position.x / size.width
                            val ny = change.position.y / size.height
                            onHeadingChange((nx * 359f).coerceIn(0f, 359f))
                            onSpeedChange((ny * 120f).coerceIn(0f, 120f))
                            change.consume()
                        }
                    }
                }
            }) {
            // perspective grid
            val horizon = size.height * 0.62f
            for (i in -12..12) {
                val x = size.width / 2 + i * (size.width / 26f)
                drawLine(DarkGreen, Offset(size.width / 2, horizon), Offset(x, size.height), strokeWidth = 1f)
            }
            for (j in 0..8) {
                val y = horizon + j * ((size.height - horizon) / 9f)
                drawLine(DarkGreen, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }

            // wireframe rover/taxi body
            val center = Offset(size.width / 2, size.height * 0.48f)
            val s = min(size.width, size.height) * 0.24f
            val pts = listOf(
                Offset(-1.3f, -0.2f), Offset(-1.0f, -0.55f), Offset(-0.2f, -0.7f), Offset(0.55f, -0.7f),
                Offset(1.15f, -0.45f), Offset(1.35f, -0.1f), Offset(1.2f, 0.2f), Offset(0.75f, 0.45f),
                Offset(-0.7f, 0.5f), Offset(-1.2f, 0.25f)
            ).map { Offset(center.x + it.x * s, center.y + it.y * s) }

            for (i in pts.indices) {
                drawLine(VectorGreen, pts[i], pts[(i + 1) % pts.size], strokeWidth = 2f, cap = StrokeCap.Round)
            }

            val top = pts.take(8).map { Offset(it.x + s * 0.2f, it.y - s * 0.18f) }
            for (i in top.indices) {
                drawLine(VectorGreen, top[i], top[(i + 1) % top.size], strokeWidth = 1.3f)
                if (i < pts.size) drawLine(VectorGreen, pts[i], top[i], strokeWidth = 1f)
            }

            // wheels
            val lw = Offset(center.x - 0.68f * s, center.y + 0.56f * s)
            val rw = Offset(center.x + 0.55f * s, center.y + 0.56f * s)
            drawCircle(VectorGreen, radius = 0.23f * s, center = lw, style = Stroke(width = 2f))
            drawCircle(VectorGreen, radius = 0.23f * s, center = rw, style = Stroke(width = 2f))
            drawCircle(VectorGreen, radius = 0.13f * s, center = lw, style = Stroke(width = 1.4f))
            drawCircle(VectorGreen, radius = 0.13f * s, center = rw, style = Stroke(width = 1.4f))

            // status vectors
            drawLine(Amber, Offset(center.x - 0.2f * s, center.y - 0.7f * s), Offset(center.x - 0.85f * s, center.y - 1.1f * s), strokeWidth = 1.4f)
            drawLine(Amber, Offset(center.x + 0.55f * s, center.y - 0.2f * s), Offset(center.x + 1.15f * s, center.y - 0.45f * s), strokeWidth = 1.4f)

            drawContext.canvas.nativeCanvas.apply {
                drawText("HDG ${heading.toInt()}°", center.x - s, center.y + s * 1.15f, android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(0, 255, 102)
                    textSize = 28f
                    typeface = android.graphics.Typeface.MONOSPACE
                })
                drawText("SPD ${speed.toInt()} kph", center.x + s * 0.2f, center.y + s * 1.15f, android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(255, 209, 102)
                    textSize = 28f
                    typeface = android.graphics.Typeface.MONOSPACE
                })
            }
        }
    }
}

@Composable
private fun ScanLineOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 4f
        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = Color(0x1100FF66),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
            y += step
        }
    }
}
