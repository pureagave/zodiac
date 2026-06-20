package ai.openclaw.zodiaccontrol.burnin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Phosphor = Color(0xFF00FF66)
private const val SWEEP_PERIOD_S = 6f
private const val SWEEP_ALPHA = 0.5f
private const val SWEEP_GLOW_ALPHA = 0.14f
private const val LABEL_ALPHA = 0.55f
private const val SUBLABEL_ALPHA = 0.30f

/**
 * Deep-idle screen: a CRT terminal asleep, not a generic Android screensaver.
 * Pure black with a single phosphor-green scan line drifting slowly down the
 * tube and a dim "STANDBY" caption. Drawn at low alpha and pixel-shifted by the
 * enclosing [burnInScaffold]; escalates to full black [BurnInPhase.SLEEP] on
 * the normal deep→sleep timer. Owns its own frame ticker, read only inside the
 * Canvas draw lambda, so it animates the draw phase only and only while shown.
 */
@Composable
fun standbyScreen(modifier: Modifier = Modifier) {
    val tSec = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) tSec.floatValue += (now - last) / 1e9f
                last = now
            }
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val frac = (tSec.floatValue % SWEEP_PERIOD_S) / SWEEP_PERIOD_S
            val y = frac * size.height
            drawLine(
                color = Phosphor.copy(alpha = SWEEP_GLOW_ALPHA),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 7f,
            )
            drawLine(
                color = Phosphor.copy(alpha = SWEEP_ALPHA),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5f,
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "STANDBY",
                color = Phosphor.copy(alpha = LABEL_ALPHA),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = 10.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "ZODIAC // TAP TO RESUME",
                color = Phosphor.copy(alpha = SUBLABEL_ALPHA),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
            )
        }
    }
}
