package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.navigation.NavigationCue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single-line navigation cue strip — drops into any concept's chrome and
 * renders the current [NavigationCue] formatted into BRC-clock vocabulary.
 *
 * Layout is fixed at three regions: a left "NAV" prefix, a centered primary
 * cue (the destination radial / arc / fractional clock), and a right-aligned
 * detail (the distance, or the secondary leg of an inbound radial cue). The
 * primary slot grows to fill any free space so a compact cue stays centered.
 *
 * Theme is the host concept's [ConceptTheme] so the bar reads as part of
 * the cockpit it sits inside.
 */
@Composable
fun navCueBar(
    cue: NavigationCue,
    theme: ConceptTheme,
    modifier: Modifier = Modifier,
) {
    val rendered = renderCue(cue)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(theme.background)
                .border(1.dp, theme.primary)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "NAV",
            color = theme.accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Text(
            text = "·",
            color = theme.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = rendered.primary,
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        if (rendered.detail.isNotEmpty()) {
            Text(
                text = rendered.detail,
                color = theme.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
    }
}

private data class RenderedCue(val primary: String, val detail: String)

private fun renderCue(cue: NavigationCue): RenderedCue =
    when (cue) {
        is NavigationCue.Unknown -> RenderedCue(primary = "—", detail = "")
        is NavigationCue.TowardClock ->
            RenderedCue(primary = "→ ${cue.clock.format()}", detail = formatDistance(cue.distanceM))
        is NavigationCue.AwayFromClock ->
            RenderedCue(primary = "← -${cue.clock.format()}", detail = formatDistance(cue.distanceM))
        is NavigationCue.OnRadialInbound ->
            RenderedCue(primary = "${cue.radialName}  →  ${cue.nextArc.uppercase()}", detail = "INBOUND")
        is NavigationCue.OnRadialOutbound ->
            RenderedCue(primary = "${cue.radialName}  ←  ${cue.lastArc.uppercase()}", detail = "OUTBOUND")
        is NavigationCue.OnArc ->
            RenderedCue(primary = "${cue.arcName.uppercase()}  ${cue.clock.format()}", detail = "")
    }

private const val METRES_PER_KILOMETRE = 1000.0

private fun formatDistance(metres: Double): String =
    if (metres >= METRES_PER_KILOMETRE) {
        "%.1fkm".format(metres / METRES_PER_KILOMETRE)
    } else {
        "%.0fm".format(metres)
    }
