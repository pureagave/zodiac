package org.pureagave.zodiac.control.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.geo.PlayaProjection
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.parseClockEntry
import org.pureagave.zodiac.control.core.navigation.requiredClockDigits
import org.pureagave.zodiac.control.core.ops.addressTarget
import org.pureagave.zodiac.control.core.ops.campGuidance
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.ui.RetroFont
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme
import kotlin.math.roundToInt

private val Scrim = Color(0xE6000000)
private val PanelBg = Color(0xFF020602)
private const val FLASH_MS = 10_000L
private val RING_NAMES = listOf("Esp", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K")

private enum class Stage { CLOCK, RING, FLASH }

private data class FlashInfo(val label: String, val bearingDeg: Double?)

/**
 * Full-screen address-entry keypad. Type a clock time on the big numeric pad
 * (2–9 → `H:MM`, 1 → `10:MM`, auto-advancing), pick a ring street, then a
 * 10-second heading flash before the live chevron + street route take over.
 * [onDriveToAddress] hands the resolved (clock, ring) to the ViewModel; the
 * scrim / DONE close via [onClose]. Big keys + retro-futurism font for gloves
 * and dust.
 */
@Composable
fun addressEntryPanel(
    theme: ConceptTheme,
    egoFix: GpsFix?,
    onDriveToAddress: (ClockTime, String) -> Unit,
    onClose: () -> Unit,
) {
    val projection = remember { PlayaProjection(GoldenSpike.Y2025) }
    var stage by remember { mutableStateOf(Stage.CLOCK) }
    var entry by remember { mutableStateOf("") }
    var clock by remember { mutableStateOf<ClockTime?>(null) }
    var flash by remember { mutableStateOf<FlashInfo?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().background(Scrim).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .width(620.dp)
                    .background(PanelBg)
                    .border(2.dp, theme.primary)
                    .padding(22.dp)
                    .clickable(enabled = false) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (stage) {
                Stage.CLOCK ->
                    clockStage(
                        theme = theme,
                        entry = entry,
                        onDigit = { d ->
                            appendDigit(entry, d)?.let { next ->
                                entry = next
                                if (next.length == requiredClockDigits(next)) {
                                    val parsed = parseClockEntry(next)
                                    if (parsed != null) {
                                        clock = parsed
                                        stage = Stage.RING
                                    } else {
                                        entry = ""
                                    }
                                }
                            }
                        },
                        onDel = { entry = entry.dropLast(1) },
                        onClose = onClose,
                    )

                Stage.RING ->
                    ringStage(theme = theme, clock = clock) { ring ->
                        val c = clock ?: return@ringStage
                        onDriveToAddress(c, ring)
                        val target = addressTarget(c, ring, projection)
                        val bearing =
                            if (egoFix != null && target != null) {
                                campGuidance(egoFix.location, target.location, projection).bearingDeg
                            } else {
                                null
                            }
                        flash = FlashInfo(label = target?.label ?: "${c.format()} & $ring", bearingDeg = bearing)
                        stage = Stage.FLASH
                    }

                Stage.FLASH -> flashStage(theme = theme, info = flash, onDone = onClose)
            }
        }
    }
}

@Composable
private fun clockStage(
    theme: ConceptTheme,
    entry: String,
    onDigit: (Char) -> Unit,
    onDel: () -> Unit,
    onClose: () -> Unit,
) {
    header(theme, "ENTER ADDRESS  —  TIME")
    Text(
        text = entryDisplay(entry),
        color = theme.accent,
        fontFamily = RetroFont,
        fontWeight = FontWeight.Bold,
        fontSize = 60.sp,
    )
    Spacer(Modifier.height(16.dp))
    listOf("123", "456", "789").forEach { rowChars ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rowChars.forEach { c -> bigKey(theme, c.toString(), Modifier.weight(1f)) { onDigit(c) } }
        }
        Spacer(Modifier.height(12.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        bigKey(theme, "DEL", Modifier.weight(1f), color = theme.dim, onClick = onDel)
        bigKey(theme, "0", Modifier.weight(1f)) { onDigit('0') }
        bigKey(theme, "✕", Modifier.weight(1f), color = theme.error, onClick = onClose)
    }
}

@Composable
private fun ringStage(
    theme: ConceptTheme,
    clock: ClockTime?,
    onRing: (String) -> Unit,
) {
    header(theme, "${clock?.format() ?: "--"}  —  PICK STREET")
    Spacer(Modifier.height(12.dp))
    RING_NAMES.chunked(4).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { name ->
                val ring = if (name == "Esp") "Esplanade" else name
                bigKey(theme, name, Modifier.weight(1f)) { onRing(ring) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun flashStage(
    theme: ConceptTheme,
    info: FlashInfo?,
    onDone: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(FLASH_MS)
        onDone()
    }
    header(theme, "DRIVE TO ${info?.label ?: ""}")
    Spacer(Modifier.height(10.dp))
    // "HEADING" label above the big degrees — keeping the big line to just the
    // number (4 glyphs) so wide values like 345° never wrap to a second line.
    Text(
        text = "HEADING",
        color = theme.primary,
        fontFamily = RetroFont,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 3.sp,
    )
    Text(
        text = "${info?.bearingDeg?.roundToInt()?.let { "%03d".format(((it % 360) + 360) % 360) } ?: "---"}°",
        color = theme.accent,
        fontFamily = RetroFont,
        fontWeight = FontWeight.Black,
        fontSize = 104.sp,
        maxLines = 1,
        softWrap = false,
    )
    Spacer(Modifier.height(12.dp))
    Text("▸ ROUTING…", color = theme.secondary, fontFamily = RetroFont, fontWeight = FontWeight.Medium, fontSize = 18.sp)
}

@Composable
private fun header(
    theme: ConceptTheme,
    text: String,
) {
    Text(
        text = text,
        color = theme.primary,
        fontFamily = RetroFont,
        fontWeight = FontWeight.Black,
        fontSize = 27.sp,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun bigKey(
    theme: ConceptTheme,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = theme.primary,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1.6f)
                .border(1.dp, color)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontFamily = RetroFont, fontWeight = FontWeight.Black, fontSize = 44.sp)
    }
}

/** Append [digit] to [entry] if it keeps a valid clock prefix, else null. */
private fun appendDigit(
    entry: String,
    digit: Char,
): String? {
    val candidate = entry + digit
    return when {
        entry.isEmpty() && digit == '0' -> null // no leading zero
        entry == "1" && digit != '0' -> null // a leading 1 must be 10:MM
        candidate.length > requiredClockDigits(candidate) -> null
        else -> candidate
    }
}

/** "2:15" once 3+ digits are in; the raw digits with a placeholder otherwise. */
private fun entryDisplay(entry: String): String =
    when {
        entry.isEmpty() -> "_:__"
        entry.first() == '1' -> "10:" + entry.drop(2).padEnd(2, '_')
        entry.length == 1 -> "$entry:__"
        else -> "${entry.first()}:" + entry.drop(1).padEnd(2, '_')
    }
