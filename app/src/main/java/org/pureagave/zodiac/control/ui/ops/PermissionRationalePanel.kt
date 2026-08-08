package org.pureagave.zodiac.control.ui.ops

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.pureagave.zodiac.control.ui.RetroFont
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme

private val Scrim = Color(0xE6000000)
private val PanelBg = Color(0xFF020602)

/**
 * Shown once, before the system permission dialog, when Android says a
 * rationale is warranted (i.e. the user has already declined once).
 *
 * The stake is specific: from Android 11 a second decline latches to "don't
 * ask again", and the system dialog then never appears again. A fleet tablet
 * in that state can't locate itself and can only be fixed by a trip through
 * Settings — which is a genuinely bad five minutes to have at camp. This
 * screen exists to make the second decline an informed one rather than a
 * reflex.
 *
 * Deliberately says what the permission *buys* rather than what it *is*, and
 * offers a real way out: NOT NOW leaves the cockpit fully usable on the
 * synthetic and network GPS sources, which is true and worth saying.
 */
@Composable
fun permissionRationalePanel(
    theme: ConceptTheme,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(PANEL_WIDTH)
                    .background(PanelBg)
                    .border(2.dp, theme.primary)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "LOCATION ACCESS",
                color = theme.primary,
                fontFamily = RetroFont,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(16.dp))
            body(theme, "Zodiac uses this tablet's own GNSS as the backup when the vehicle beacon drops out.")
            Spacer(Modifier.height(8.dp))
            body(theme, "Without it the map still works, but a beacon failure leaves the cockpit with no position at all.")
            Spacer(Modifier.height(8.dp))
            // Naming the consequence is the entire reason this screen exists.
            Text(
                text = "Declining twice permanently hides Android's prompt — after that it can only be re-enabled in Settings.",
                color = theme.error,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                panelKey("NOT NOW", theme.dim, Modifier.weight(1f), onDismiss)
                panelKey("CONTINUE", theme.primary, Modifier.weight(1f), onContinue)
            }
        }
    }
}

@Composable
private fun body(
    theme: ConceptTheme,
    text: String,
) {
    Text(
        text = text,
        color = theme.secondary,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
private fun panelKey(
    label: String,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(KEY_HEIGHT)
                .border(1.dp, color)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = color,
            fontFamily = RetroFont,
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
        )
    }
}

private val PANEL_WIDTH = 560.dp
private val KEY_HEIGHT = 54.dp
