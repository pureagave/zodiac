package org.pureagave.zodiac.control.ui.ops

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pureagave.zodiac.control.core.ops.NavTarget
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme

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
 *
 * [enabled] is the follower-gating affordance (spec R4) — the hard gate
 * already lives in `NavShareController.userSet`'s central check, so a
 * follower's tap is a no-op either way; this just makes that visible rather
 * than leaving buttons that silently do nothing. Selection highlight always
 * keeps tracking [active] regardless — a follower still sees where the fleet
 * is headed, it just can't change it.
 */
@Composable
fun driveToBar(
    theme: ConceptTheme,
    active: DriveSelection,
    onSelect: (NavTarget) -> Unit,
    onSelectBath: () -> Unit,
    onOpenAddress: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
                enabled = enabled,
                onClick = { onSelect(target) },
                modifier = Modifier.weight(1f),
            )
        }
        driveToButton("BATH", active == DriveSelection.Bath, theme, enabled, onSelectBath, Modifier.weight(1f))
        driveToButton("ADDR", active == DriveSelection.Address, theme, enabled, onOpenAddress, Modifier.weight(1f))
    }
}

@Composable
private fun driveToButton(
    label: String,
    selected: Boolean,
    theme: ConceptTheme,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) theme.secondary else theme.primary
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .alpha(alpha)
                .border(if (selected) 2.dp else 1.dp, color)
                .background(if (selected) theme.secondary.copy(alpha = SELECTED_FILL_ALPHA) else theme.background)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
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
private const val DISABLED_ALPHA = 0.5f
