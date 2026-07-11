package ai.openclaw.zodiaccontrol.ui.ops

import ai.openclaw.zodiaccontrol.ui.concepts.ConceptTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Backing = Color(0xCC000000)

/**
 * Bottom-centre "◂ PASSING <art>" flash for a notable art piece the ego just
 * drove near — passenger flavour. Sits above the drive-to bar / footer. Purely
 * decorative overlay (no pointer modifiers, so map gestures pass through);
 * shown while `CockpitUiState.passingCallout` is non-null (VM clears it on a
 * timer).
 */
@Composable
fun passingCallout(
    theme: ConceptTheme,
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier =
                Modifier
                    .padding(bottom = 118.dp)
                    .background(Backing)
                    .border(1.dp, theme.primary)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "◂ PASSING  ",
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
            )
            Text(
                text = name,
                color = theme.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
        }
    }
}
