package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.connection.ConnectionPhase
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.model.MapMode
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.ui.state.CockpitUiState
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import ai.openclaw.zodiaccontrol.ui.wrapHeading
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One-stop driver controls (transport / GPS / heading / speed / recenter).
 * Every concept screen needs the same set, so the chip layout lives here and
 * each concept passes its own [theme]. Keeping this in one place means a
 * heading-step change affects all four cockpits identically.
 */
@Composable
fun conceptControlStrip(
    state: CockpitUiState,
    viewModel: CockpitViewModel,
    theme: ConceptTheme,
    modifier: Modifier = Modifier,
    showTiltToggle: Boolean = true,
) {
    Column(
        modifier =
            modifier
                .border(1.dp, theme.primary)
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sectionLabel("> TRANSPORT", theme.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TransportType.entries.forEach { t ->
                themedChip(
                    label = t.name,
                    selected = state.selectedTransport == t,
                    theme = theme,
                    onClick = { viewModel.selectTransport(t) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actionChip(
                label = if (state.connectionPhase == ConnectionPhase.CONNECTED) "DISCONNECT" else "CONNECT",
                theme = theme,
                onClick = {
                    viewModel.setTransportConnected(state.connectionPhase != ConnectionPhase.CONNECTED)
                },
            )
            Text(
                text = state.connectionPhase.name,
                color = theme.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        sectionLabel("> GPS", theme.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LocationSourceType.entries.forEach { s ->
                themedChip(
                    label = s.name,
                    selected = state.selectedLocationSource == s,
                    theme = theme,
                    onClick = { viewModel.selectLocationSource(s) },
                )
            }
        }

        sectionLabel("> HDG ${state.headingDeg}°", theme.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HEADING_STEPS.forEach { step ->
                themedChip(
                    label = if (step >= 0) "+$step" else "$step",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.setHeading(wrapHeading(state.headingDeg + step)) },
                )
            }
        }

        sectionLabel("> SPD ${state.speedKph} kph", theme.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SPEED_STEPS.forEach { step ->
                themedChip(
                    label = if (step >= 0) "+$step" else "$step",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.setSpeed(state.speedKph + step) },
                )
            }
        }

        sectionLabel("> ZOOM ${"%.2f".format(state.pixelsPerMeter)} px/m", theme.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            themedChip(
                label = "ZOOM-",
                selected = false,
                theme = theme,
                onClick = { viewModel.setPixelsPerMeter(state.pixelsPerMeter / ZOOM_STEP) },
            )
            themedChip(
                label = "ZOOM+",
                selected = false,
                theme = theme,
                onClick = { viewModel.setPixelsPerMeter(state.pixelsPerMeter * ZOOM_STEP) },
            )
            actionChip(label = "RECENTER", theme = theme, onClick = viewModel::recenterPan)
        }

        if (showTiltToggle) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                themedChip(
                    label = "TOP",
                    selected = state.mapMode == MapMode.TOP,
                    theme = theme,
                    onClick = { viewModel.setMapMode(MapMode.TOP) },
                )
                themedChip(
                    label = "TILT",
                    selected = state.mapMode == MapMode.TILT,
                    theme = theme,
                    onClick = { viewModel.setMapMode(MapMode.TILT) },
                )
            }
        }

        if (state.selectedLocationSource == LocationSourceType.FAKE) {
            sectionLabel("> FAKE GPS NUDGE", theme.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                themedChip(
                    label = "N+$NUDGE_STEP_M",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.nudgeFakeGps(0.0, NUDGE_STEP_M.toDouble()) },
                )
                themedChip(
                    label = "S+$NUDGE_STEP_M",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.nudgeFakeGps(0.0, -NUDGE_STEP_M.toDouble()) },
                )
                themedChip(
                    label = "E+$NUDGE_STEP_M",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.nudgeFakeGps(NUDGE_STEP_M.toDouble(), 0.0) },
                )
                themedChip(
                    label = "W+$NUDGE_STEP_M",
                    selected = false,
                    theme = theme,
                    onClick = { viewModel.nudgeFakeGps(-NUDGE_STEP_M.toDouble(), 0.0) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                actionChip(label = "GPS RESET", theme = theme, onClick = viewModel::resetFakeGps)
            }
        }
    }
}

@Composable
private fun sectionLabel(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun themedChip(
    label: String,
    selected: Boolean,
    theme: ConceptTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .border(1.dp, if (selected) theme.accent else theme.primary)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = if (selected) theme.accent else theme.primary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
fun actionChip(
    label: String,
    theme: ConceptTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .border(1.dp, theme.secondary)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = theme.secondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

private val HEADING_STEPS = listOf(-15, -1, 1, 15)
private val SPEED_STEPS = listOf(-10, -1, 1, 10)
private const val ZOOM_STEP: Double = 1.4
private const val NUDGE_STEP_M: Int = 100
