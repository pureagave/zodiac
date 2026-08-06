package org.pureagave.zodiac.control.ui.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.ops.DriveTarget
import org.pureagave.zodiac.control.core.ops.campGuidance
import org.pureagave.zodiac.control.core.ops.sunTimes
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.telemetry.BeaconReadout
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

private const val TICK_MS = 1_000L
private const val METERS_PER_KM = 1_000.0
private const val KM_CUTOVER_M = 950.0

/**
 * Footer heights. Concepts pin these rather than letting the footer flex, so
 * the beacon line appearing mid-drive can't reflow the tile wall above it.
 */
val OPS_FOOTER_ONE_LINE = 34.dp
val OPS_FOOTER_TWO_LINE = 52.dp

// The beacon is the fleet's only GPS. If its battery dies every tablet loses
// position, so a low cell is a genuine fault, not a status nicety.
private const val BEACON_BATTERY_LOW_PCT = 20

// Below this many satellites a fix exists but shouldn't be trusted for nav.
private const val SATS_MARGINAL = 5

private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

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
 *
 * A **second line** appears only when the beacon is actually reporting
 * ([odometer] / [beaconHealth] / [shockAlertG]): trip + lifetime distance on
 * the left, beacon health on the right, and a transient shock readout between
 * them. With no beacon on the bus the footer collapses back to one line rather
 * than showing a row of dashes.
 */
@Composable
fun opsReadout(
    theme: ConceptTheme,
    egoFix: GpsFix?,
    headingDeg: Int,
    target: DriveTarget?,
    aim: LatLon? = null,
    beacon: BeaconReadout = BeaconReadout.NONE,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.of("America/Los_Angeles") }
    val projection = remember { PlayaProjection(GoldenSpike.ACTIVE) }

    var now by remember { mutableStateOf(ZonedDateTime.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            now = ZonedDateTime.now(zone)
            delay(TICK_MS)
        }
    }
    val sun =
        remember(now.toLocalDate()) {
            sunTimes(now.toLocalDate(), GoldenSpike.ACTIVE.lat, GoldenSpike.ACTIVE.lon, zone)
        }
    // Distance to the final destination; the arrow points at [aim] (the next
    // route corner) so it agrees with the guidance chevron.
    val aimLoc = aim ?: target?.location
    val guidance =
        remember(egoFix?.location, target) {
            if (egoFix != null && target != null) campGuidance(egoFix.location, target.location, projection) else null
        }
    val aimBearing =
        remember(egoFix?.location, aimLoc) {
            if (egoFix != null && aimLoc != null) campGuidance(egoFix.location, aimLoc, projection).bearingDeg else null
        }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    text = "▸ ${target?.label ?: "--"} ${guidance?.distanceM?.let(::formatDistance) ?: "--"}",
                    color = theme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                if (aimBearing != null) {
                    Text(
                        text = " ▲",
                        color = theme.accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        // Track-up: 0° = straight ahead; rotate toward the next route corner.
                        modifier = Modifier.rotate((aimBearing - headingDeg).toFloat()),
                    )
                }
            }
        }

        if (beacon.any) {
            beaconLine(theme, beacon)
        }
    }
}

/**
 * The beacon's own line: distance travelled, a transient shock readout, and the
 * hub's health. Laid out so the two steady readings hold the outer edges and
 * the intermittent alert appears between them, rather than shoving anything
 * sideways when it fires.
 */
@Composable
private fun beaconLine(
    theme: ConceptTheme,
    beacon: BeaconReadout,
) {
    val odometer = beacon.odometer
    val health = beacon.health
    val shockAlertG = beacon.shockG
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Distance is a live data value -> accent (purple); its labels are chrome.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (odometer != null) {
                labelled(theme, "TRIP", formatDistance(odometer.tripMeters), theme.accent)
                labelled(theme, "  ODO", formatDistance(odometer.totalMeters), theme.accent)
            }
        }

        if (shockAlertG != null) {
            // Faults/extreme warnings only get red — a real jolt qualifies.
            Text(
                text = "◆ SHOCK %.1fg".format(shockAlertG),
                color = theme.error,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (health != null) {
                // Battery is a live value, but a flat beacon takes the whole
                // fleet's GPS with it — so low reads as a fault, not a value.
                val battLow = health.batteryPct <= BEACON_BATTERY_LOW_PCT
                labelled(
                    theme,
                    "BATT",
                    "${health.batteryPct}%",
                    if (battLow) theme.error else theme.accent,
                )
                // Fix quality and satellite count are link/GPS *state* -> secondary (blue),
                // except no-fix, which is a fault.
                val noFix = health.fixQuality <= 0
                labelled(
                    theme,
                    "  SATS",
                    "${health.satellites}",
                    when {
                        noFix -> theme.error
                        health.satellites < SATS_MARGINAL -> theme.error
                        else -> theme.secondary
                    },
                )
                labelled(
                    theme,
                    "  UP",
                    health.uptimeSec.formatUptime(),
                    theme.secondary,
                )
            }
        }
    }
}

/** Green chrome label + a value in whatever colour its meaning calls for. */
@Composable
private fun labelled(
    theme: ConceptTheme,
    label: String,
    value: String,
    valueColor: Color,
) {
    Text(
        text = "$label ",
        color = theme.dim,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
    Text(
        text = value,
        color = valueColor,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    )
}

private fun LocalTime?.formatHhMm(): String = this?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "--:--"

private fun formatDistance(meters: Double): String =
    if (meters < KM_CUTOVER_M) {
        "${meters.roundToInt()}m"
    } else {
        "%.1fkm".format(meters / METERS_PER_KM)
    }

/** Compact hub uptime — "3h07" / "12m" — so a beacon that silently rebooted is visible. */
private fun Long.formatUptime(): String {
    val hours = this / SECONDS_PER_HOUR
    val minutes = (this % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return if (hours > 0) "%dh%02d".format(hours, minutes) else "%dm".format(minutes)
}
