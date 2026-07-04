package ai.openclaw.zodiaccontrol.ui.ops

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.ops.Camp
import ai.openclaw.zodiaccontrol.core.ops.campGuidance
import ai.openclaw.zodiaccontrol.core.ops.sunTimes
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

private val Phosphor = Color(0xFF00FF66)
private val PhosphorDim = Color(0xFF2C8A4A)
private val Amber = Color(0xFFFFD166)

// Opaque black (OLED pixels off) rather than a translucent veil, so the strip
// reads as a crisp HUD footer instead of dimming the concept behind it.
private val StripBg = Color(0xFF000000)
private val Divider = Color(0x5900FF66)
private const val DIVIDER_PX = 2f
private const val STRIP_HEIGHT_DP = 30
private const val TICK_MS = 1_000L
private const val METERS_PER_KM = 1_000.0
private const val KM_CUTOVER_M = 950.0

/**
 * Ambient operational status strip pinned to the bottom edge, rendered over
 * every concept (inside `burnInScaffold`, so it dims/sleeps with the cockpit).
 * Shows the BRC wall clock, today's sunrise/sunset, and a live return-to-camp
 * readout (distance + a heading-relative arrow pointing the way home).
 *
 * Collects its own narrow state so per-second clock ticks and GPS updates
 * invalidate only this strip, not the concept dispatch.
 */
@Composable
fun opsStrip(
    viewModel: CockpitViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.of("America/Los_Angeles") }
    val projection = remember { PlayaProjection(GoldenSpike.Y2025) }

    var now by remember { mutableStateOf(ZonedDateTime.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            now = ZonedDateTime.now(zone)
            delay(TICK_MS)
        }
    }
    val sun = remember(now.toLocalDate()) { sunTimes(now.toLocalDate(), GoldenSpike.Y2025.lat, GoldenSpike.Y2025.lon, zone) }

    val egoFix = state.egoFix
    val guidance =
        remember(egoFix?.location) {
            egoFix?.let { campGuidance(it.location, Camp.GALACTIC_RELAY, projection) }
        }

    Row(
        modifier =
            modifier
                // Sit above any bottom/side system chrome (gesture bar, cutout);
                // ~0 in true fullscreen/kiosk, so the bar hugs the edge there.
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .fillMaxWidth()
                .height(STRIP_HEIGHT_DP.dp)
                .background(StripBg)
                .drawBehind {
                    drawLine(Divider, Offset(0f, 0f), Offset(size.width, 0f), DIVIDER_PX)
                }
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // BRC clock — prominent.
        Text(
            text = now.toLocalTime().formatHhMm(),
            color = Phosphor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )

        Text(
            text = "RISE ${sun.sunrise.formatHhMm()}   SET ${sun.sunset.formatHhMm()}",
            color = PhosphorDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "CAMP ${guidance?.distanceM?.let(::formatDistance) ?: "--"}",
                color = Phosphor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            if (guidance != null) {
                Text(
                    text = " ▲",
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    // Track-up: 0° = straight ahead. Rotate to camp relative to heading.
                    modifier =
                        Modifier
                            .size(20.dp)
                            .rotate((guidance.bearingDeg - state.headingDeg).toFloat()),
                )
            }
        }
    }
}

private fun LocalTime?.formatHhMm(): String = this?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--"

private fun formatDistance(meters: Double): String =
    if (meters < KM_CUTOVER_M) {
        "${meters.roundToInt()}m"
    } else {
        "%.1fkm".format(meters / METERS_PER_KM)
    }
