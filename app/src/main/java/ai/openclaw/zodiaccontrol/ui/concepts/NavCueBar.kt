package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.navigation.NavigationCue
import ai.openclaw.zodiaccontrol.ui.vectorText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
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
        themedText("NAV", theme.primary, 12.sp, theme.useVectorText, bold = true)
        themedText("·", theme.dim, 14.sp, useVector = false, bold = false)
        Box(modifier = Modifier.weight(1f)) {
            themedText(rendered.primary, theme.primary, 18.sp, theme.useVectorText, bold = true)
        }
        if (rendered.detail.isNotEmpty()) {
            themedText(rendered.detail, theme.accent, 13.sp, theme.useVectorText, bold = false)
        }
    }
}

/**
 * Switch between the regular monospace [Text] and [vectorText] based on
 * [useVector] — concept A's theme flips this on so its nav cue reads in
 * the same Atari-vector aesthetic as its top bar; every other concept
 * keeps its existing solid-glyph monospace look.
 */
@Composable
private fun themedText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    useVector: Boolean,
    bold: Boolean,
) {
    if (useVector) {
        vectorText(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    } else {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = fontSize,
        )
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
