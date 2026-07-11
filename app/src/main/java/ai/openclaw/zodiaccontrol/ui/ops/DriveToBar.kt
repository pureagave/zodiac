package ai.openclaw.zodiaccontrol.ui.ops

import ai.openclaw.zodiaccontrol.core.ops.NavTarget
import ai.openclaw.zodiaccontrol.ui.concepts.ConceptTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val BAR_HEIGHT_DP = 40

/** Which drive-to destination is currently active — drives the bar's highlight. */
sealed interface DriveSelection {
    data class Preset(val target: NavTarget) : DriveSelection

    data object Bath : DriveSelection

    data object Address : DriveSelection
}

/** Resolve the active [DriveSelection] from the cockpit's drive-to state. */
fun driveSelectionOf(
    customActive: Boolean,
    bathActive: Boolean,
    preset: NavTarget,
): DriveSelection =
    when {
        customActive -> DriveSelection.Address
        bathActive -> DriveSelection.Bath
        else -> DriveSelection.Preset(preset)
    }

/**
 * Prominent "drive to" destination selector — a full-width row of large
 * HOME / MAN / TEMPLE + BATH + ADDR buttons for glance-and-tap while driving.
 * The [active] destination is highlighted blue (selected-status) with a faint
 * fill; the rest are plain green. BATH targets the nearest toilet bank; ADDR
 * opens the address keypad ([onOpenAddress]) and stays lit while a typed-in
 * address is the active target. Tapping a preset calls [onSelect]. The chevron
 * card + ops footer then guide to whichever is active.
 */
@Composable
fun driveToBar(
    theme: ConceptTheme,
    active: DriveSelection,
    onSelect: (NavTarget) -> Unit,
    onSelectBath: () -> Unit,
    onOpenAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(BAR_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NavTarget.entries.forEach { target ->
            driveToButton(
                label = target.label,
                selected = active == DriveSelection.Preset(target),
                theme = theme,
                onClick = { onSelect(target) },
                modifier = Modifier.weight(1f),
            )
        }
        driveToButton("BATH", active == DriveSelection.Bath, theme, onSelectBath, Modifier.weight(1f))
        driveToButton("ADDR", active == DriveSelection.Address, theme, onOpenAddress, Modifier.weight(1f))
    }
}

@Composable
private fun driveToButton(
    label: String,
    selected: Boolean,
    theme: ConceptTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) theme.secondary else theme.primary
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .border(if (selected) 2.dp else 1.dp, color)
                .background(if (selected) theme.secondary.copy(alpha = SELECTED_FILL_ALPHA) else theme.background)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

private const val SELECTED_FILL_ALPHA = 0.18f
