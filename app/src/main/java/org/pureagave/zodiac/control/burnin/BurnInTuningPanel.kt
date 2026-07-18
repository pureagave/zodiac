package org.pureagave.zodiac.control.burnin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Phosphor = Color(0xFF00FF66)
private val PhosphorDim = Color(0xFF2C8A4A)
private val HeaderGreen = Color(0xFF00FF66)
private val Scrim = Color(0xCC000000)
private val PanelBg = Color(0xFF020602)

private const val SHIFT_AMP_STEP = 1
private const val PERIOD_STEP_SEC = 5
private const val BREATHE_STEP = 0.01f
private const val ALPHA_STEP = 0.05f
private const val SMALL_ALPHA_STEP = 0.01f
private const val DIM_TIMEOUT_STEP_SEC = 30L
private const val DEEP_TIMEOUT_STEP_SEC = 60L
private const val SLEEP_TIMEOUT_STEP_SEC = 300L
private const val MOVE_SPEED_STEP = 0.5
private const val MOVE_METERS_STEP = 1.0
private const val SECONDS_PER_MINUTE = 60L

/**
 * Hidden on-playa tuning panel for [BurnInConfig]. Phosphor-green CRT styling
 * over a scrim; every row edits the live config (coerced + persisted by the
 * manager via [onConfig]) so changes apply immediately and survive relaunch.
 * Opened by a corner long-press (see [burnInScaffold]); not discoverable by
 * accident.
 */
@Composable
fun burnInTuningPanel(
    config: BurnInConfig,
    onConfig: (BurnInConfig) -> Unit,
    onPark: () -> Unit,
    onWake: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Scrim)
                .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(540.dp)
                    .heightIn(max = 560.dp)
                    .background(PanelBg)
                    .border(1.dp, Phosphor)
                    .padding(16.dp)
                    // Swallow clicks on the panel so they don't hit the scrim's close.
                    .clickable(enabled = false) {},
        ) {
            Text(
                text = "▸ BURN-IN TUNING",
                color = HeaderGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                toggleRow("PIXEL SHIFT", config.pixelShiftEnabled) {
                    onConfig(config.copy(pixelShiftEnabled = it))
                }
                stepperRow(
                    "  SHIFT AMP",
                    "${config.pixelShiftAmplitudePx}px",
                    { onConfig(config.copy(pixelShiftAmplitudePx = config.pixelShiftAmplitudePx - SHIFT_AMP_STEP)) },
                    { onConfig(config.copy(pixelShiftAmplitudePx = config.pixelShiftAmplitudePx + SHIFT_AMP_STEP)) },
                )
                stepperRow(
                    "  SHIFT PERIOD",
                    "${config.pixelShiftPeriodSec}s",
                    { onConfig(config.copy(pixelShiftPeriodSec = config.pixelShiftPeriodSec - PERIOD_STEP_SEC)) },
                    { onConfig(config.copy(pixelShiftPeriodSec = config.pixelShiftPeriodSec + PERIOD_STEP_SEC)) },
                )

                toggleRow("BRIGHTNESS MOD (OLED)", config.visualModulationEnabled) {
                    onConfig(config.copy(visualModulationEnabled = it))
                }
                stepperRow(
                    "  BREATHE AMP",
                    percent(config.breatheAmplitude),
                    { onConfig(config.copy(breatheAmplitude = config.breatheAmplitude - BREATHE_STEP)) },
                    { onConfig(config.copy(breatheAmplitude = config.breatheAmplitude + BREATHE_STEP)) },
                )
                stepperRow(
                    "  BREATHE PERIOD",
                    "${config.breathePeriodSec}s",
                    { onConfig(config.copy(breathePeriodSec = config.breathePeriodSec - PERIOD_STEP_SEC)) },
                    { onConfig(config.copy(breathePeriodSec = config.breathePeriodSec + PERIOD_STEP_SEC)) },
                )

                stepperRow(
                    "DIM AFTER",
                    duration(config.dimTimeoutSec),
                    { onConfig(config.copy(dimTimeoutSec = config.dimTimeoutSec - DIM_TIMEOUT_STEP_SEC)) },
                    { onConfig(config.copy(dimTimeoutSec = config.dimTimeoutSec + DIM_TIMEOUT_STEP_SEC)) },
                )
                stepperRow(
                    "  DIM CONTENT",
                    percent(config.dimContentAlpha),
                    { onConfig(config.copy(dimContentAlpha = config.dimContentAlpha - ALPHA_STEP)) },
                    { onConfig(config.copy(dimContentAlpha = config.dimContentAlpha + ALPHA_STEP)) },
                )
                stepperRow(
                    "  DIM BACKLIGHT",
                    percent(config.dimBacklight),
                    { onConfig(config.copy(dimBacklight = config.dimBacklight - ALPHA_STEP)) },
                    { onConfig(config.copy(dimBacklight = config.dimBacklight + ALPHA_STEP)) },
                )

                stepperRow(
                    "STANDBY AFTER",
                    duration(config.deepIdleTimeoutSec),
                    { onConfig(config.copy(deepIdleTimeoutSec = config.deepIdleTimeoutSec - DEEP_TIMEOUT_STEP_SEC)) },
                    { onConfig(config.copy(deepIdleTimeoutSec = config.deepIdleTimeoutSec + DEEP_TIMEOUT_STEP_SEC)) },
                )
                stepperRow(
                    "  STANDBY BACKLIGHT",
                    percent(config.deepIdleBacklight),
                    { onConfig(config.copy(deepIdleBacklight = config.deepIdleBacklight - ALPHA_STEP)) },
                    { onConfig(config.copy(deepIdleBacklight = config.deepIdleBacklight + ALPHA_STEP)) },
                )

                stepperRow(
                    "SLEEP AFTER",
                    duration(config.sleepTimeoutSec),
                    { onConfig(config.copy(sleepTimeoutSec = config.sleepTimeoutSec - SLEEP_TIMEOUT_STEP_SEC)) },
                    { onConfig(config.copy(sleepTimeoutSec = config.sleepTimeoutSec + SLEEP_TIMEOUT_STEP_SEC)) },
                )
                stepperRow(
                    "  SLEEP BACKLIGHT",
                    percent(config.sleepBacklight),
                    { onConfig(config.copy(sleepBacklight = config.sleepBacklight - SMALL_ALPHA_STEP)) },
                    { onConfig(config.copy(sleepBacklight = config.sleepBacklight + SMALL_ALPHA_STEP)) },
                )

                stepperRow(
                    "MOVE SPEED THRESH",
                    "%.1fkph".format(config.movementSpeedKph),
                    { onConfig(config.copy(movementSpeedKph = config.movementSpeedKph - MOVE_SPEED_STEP)) },
                    { onConfig(config.copy(movementSpeedKph = config.movementSpeedKph + MOVE_SPEED_STEP)) },
                )
                stepperRow(
                    "MOVE DIST THRESH",
                    "%.0fm".format(config.movementMeters),
                    { onConfig(config.copy(movementMeters = config.movementMeters - MOVE_METERS_STEP)) },
                    { onConfig(config.copy(movementMeters = config.movementMeters + MOVE_METERS_STEP)) },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actionButton("PARK NOW", HeaderGreen, Modifier.weight(1f)) {
                    onPark()
                    onClose()
                }
                actionButton("WAKE", Phosphor, Modifier.weight(1f), onClick = onWake)
                actionButton("DEFAULTS", PhosphorDim, Modifier.weight(1f)) { onConfig(BurnInConfig()) }
                actionButton("CLOSE", Phosphor, Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}

@Composable
private fun toggleRow(
    label: String,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        label(label)
        Text(
            text = if (value) "[ ON ]" else "[ OFF ]",
            color = if (value) Phosphor else PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.clickable { onToggle(!value) }.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun stepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        label(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            stepGlyph("[-]", onMinus)
            Text(
                text = value,
                color = HeaderGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.width(72.dp),
                textAlign = TextAlign.End,
            )
            stepGlyph("[+]", onPlus)
        }
    }
}

@Composable
private fun label(text: String) {
    Text(
        text = text,
        color = Phosphor,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
private fun stepGlyph(
    glyph: String,
    onClick: () -> Unit,
) {
    Text(
        text = glyph,
        color = Phosphor,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun actionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(40.dp)
                .border(1.dp, color)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

private fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

private fun duration(totalSec: Long): String {
    if (totalSec < SECONDS_PER_MINUTE) return "${totalSec}s"
    val minutes = totalSec / SECONDS_PER_MINUTE
    val seconds = totalSec % SECONDS_PER_MINUTE
    return if (seconds == 0L) "${minutes}m" else "${minutes}m${seconds}s"
}
