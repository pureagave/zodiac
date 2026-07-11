package ai.openclaw.zodiaccontrol.ui.ops

import ai.openclaw.zodiaccontrol.ui.concepts.ConceptTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

private val Backing = Color(0xCC000000)

/**
 * Big top-centre flash of the street the ego just drove onto / crossed. Purely
 * decorative overlay — no pointer modifiers, so map pan/zoom under it still
 * work. Shown while `CockpitUiState.streetPopup` is non-null (the ViewModel
 * clears it on a timer).
 */
@Composable
fun streetCrossingPopup(
    theme: ConceptTheme,
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .padding(top = 88.dp)
                    .background(Backing)
                    .border(2.dp, theme.primary)
                    .padding(horizontal = 30.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "ENTERING",
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 4.sp,
            )
            Text(
                text = name.uppercase(),
                color = theme.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 52.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
