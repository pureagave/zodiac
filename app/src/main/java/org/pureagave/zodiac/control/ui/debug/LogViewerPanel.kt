package org.pureagave.zodiac.control.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pureagave.zodiac.control.core.log.LogSeverity
import org.pureagave.zodiac.control.core.log.RollingFileLog
import org.pureagave.zodiac.control.core.log.logLineSeverity
import org.pureagave.zodiac.control.ui.concepts.ConceptTheme

private val Scrim = Color(0xF2000000)
private val PanelBg = Color(0xFF010401)
private const val TAIL_LINES = 400
private const val BYTES_PER_KB = 1024.0

/**
 * On-device log viewer — the last [TAIL_LINES] of the rolling file.
 *
 * The premise of the whole logging feature is a tablet that misbehaved hours
 * ago somewhere out on the playa. `adb pull` answers that at camp with a
 * laptop; this answers it standing next to the vehicle with neither. That's
 * why it exists despite `adb` already working.
 *
 * Read on IO, never on the main thread — the file is capped but still hundreds
 * of KB, and this opens while the cockpit is live.
 */
@Composable
fun logViewerPanel(
    log: RollingFileLog,
    theme: ConceptTheme,
    onClose: () -> Unit,
) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var sizeKb by remember { mutableStateOf(0.0) }
    var dropped by remember { mutableStateOf(0L) }
    var reloads by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(reloads) {
        val snapshot =
            withContext(Dispatchers.IO) {
                Triple(log.tail(TAIL_LINES), log.files().sumOf { it.length() }, log.droppedLines)
            }
        lines = snapshot.first
        sizeKb = snapshot.second / BYTES_PER_KB
        dropped = snapshot.third
        // Newest last, and the newest is what you opened this for.
        if (snapshot.first.isNotEmpty()) listState.scrollToItem(snapshot.first.lastIndex)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Scrim).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(WIDTH_FRACTION)
                    .fillMaxHeight(HEIGHT_FRACTION)
                    .background(PanelBg)
                    .border(1.dp, theme.primary)
                    .padding(12.dp)
                    .clickable(enabled = false) {},
        ) {
            header(theme, lines.size, sizeKb, dropped, onReload = { reloads++ }, onClose = onClose)
            if (lines.isEmpty()) {
                Text(
                    text = "— no log lines —",
                    color = theme.dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = LINE_SP.sp,
                )
            } else {
                LazyColumn(
                    state = listState,
                    // Lines are long and must not wrap: a wrapped timestamp
                    // column destroys the scannability that makes this usable.
                    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                ) {
                    items(lines.size) { i -> logLine(lines[i], theme) }
                }
            }
        }
    }
}

@Composable
private fun logLine(
    line: String,
    theme: ConceptTheme,
) {
    Text(
        text = line,
        color =
            when (logLineSeverity(line)) {
                LogSeverity.ERROR -> theme.error
                LogSeverity.WARN -> theme.accent
                LogSeverity.NORMAL -> theme.dim
            },
        fontFamily = FontFamily.Monospace,
        fontSize = LINE_SP.sp,
        softWrap = false,
        maxLines = 1,
    )
}

@Composable
private fun header(
    theme: ConceptTheme,
    lineCount: Int,
    sizeKb: Double,
    dropped: Long,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "LOG  $lineCount lines  ${"%.0f".format(sizeKb)} KB",
            color = theme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = HEADER_SP.sp,
        )
        // Silent loss would make the log a liar about its own completeness.
        if (dropped > 0) {
            Text(
                text = "$dropped DROPPED",
                color = theme.error,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = HEADER_SP.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            panelButton("RELOAD", theme.primary, onReload)
            panelButton("CLOSE", theme.error, onClose)
        }
    }
}

@Composable
private fun panelButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = HEADER_SP.sp,
        modifier =
            Modifier
                .border(1.dp, color)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private const val WIDTH_FRACTION = 0.94f
private const val HEIGHT_FRACTION = 0.88f
private const val LINE_SP = 10
private const val HEADER_SP = 13
