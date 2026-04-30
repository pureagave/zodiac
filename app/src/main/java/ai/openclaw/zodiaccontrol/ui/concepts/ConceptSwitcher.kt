package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
 * Tap to cycle A → B → C → D → A. Always sits in the top-right; rendered last
 * so it stacks above the screen's frame. Borrows the host theme's accent color
 * so it reads as part of whatever concept is currently showing.
 */
@Composable
fun conceptSwitcher(
    current: CockpitConcept,
    onCycle: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(Color(0xCC000000))
                .border(2.dp, accent)
                .clickable(onClick = onCycle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "[${current.tag}] ${current.displayName}  >",
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}
