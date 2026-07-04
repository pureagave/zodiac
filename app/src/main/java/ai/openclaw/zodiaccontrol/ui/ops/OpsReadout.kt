package ai.openclaw.zodiaccontrol.ui.ops

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.PlayaProjection
import ai.openclaw.zodiaccontrol.core.ops.Camp
import ai.openclaw.zodiaccontrol.core.ops.campGuidance
import ai.openclaw.zodiaccontrol.core.ops.sunTimes
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.ui.concepts.ConceptTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

private const val TICK_MS = 1_000L
private const val METERS_PER_KM = 1_000.0
private const val KM_CUTOVER_M = 950.0

/**
 * Operational readout — BRC wall clock, today's sunrise/sunset, and a live
 * return-to-camp distance + a heading-relative arrow pointing home. A
 * **first-class, palette-driven** element: it renders in the host concept's
 * [ConceptTheme] (no bar/background of its own) so each concept places it in
 * its native chrome (a bordered footer, a gauge tile, …) and it reads as part
 * of that concept rather than a shared overlay.
 *
 * Owns its own per-second time tick and the sun/camp computation; those reads
 * invalidate only this composable, not the host concept.
 */
@Composable
fun opsReadout(
    theme: ConceptTheme,
    egoFix: GpsFix?,
    headingDeg: Int,
    modifier: Modifier = Modifier,
) {
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
    val guidance = remember(egoFix?.location) { egoFix?.let { campGuidance(it.location, Camp.GALACTIC_RELAY, projection) } }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = now.toLocalTime().formatHhMm(),
            color = theme.accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Text(
            text = "RISE ${sun.sunrise.formatHhMm()}   SET ${sun.sunset.formatHhMm()}",
            color = theme.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "CAMP ${guidance?.distanceM?.let(::formatDistance) ?: "--"}",
                color = theme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            if (guidance != null) {
                Text(
                    text = " ▲",
                    color = theme.accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    // Track-up: 0° = straight ahead; rotate to camp relative to heading.
                    modifier = Modifier.rotate((guidance.bearingDeg - headingDeg).toFloat()),
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
