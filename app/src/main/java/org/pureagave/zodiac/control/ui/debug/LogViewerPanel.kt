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
 * One consistent read of the log's state. Taken together off the IO thread so
 * the counters can't disagree with the lines they describe.
 */
private data class LogSnapshot(
    val lines: List<String> = emptyList(),
    val sizeKb: Double = 0.0,
    /** Lines lost to IO failure. */
    val dropped: Long = 0L,
    /** Lines that aged out of the rotation window — expected, but not silent. */
    val agedOut: Long = 0L,
    /** Rotations the filesystem refused; non-zero means the cap is at risk. */
    val rotationFailures: Long = 0L,
    /**
     * Lines shed by the pre-file buffer under a burst, before they ever reached
     * the rolling file. The one loss the file's own counters cannot see.
     */
    val overflow: Long = 0L,
    val lastError: String? = null,
)

/**
 * On-device log viewer — the last [TAIL_LINES] of the rolling file.
 *
 * The premise of the whole logging feature is a tablet that misbehaved hours
 * ago somewhere out on the playa. This answers it standing next to the vehicle
 * with no laptop — and on a Fire, where `adb pull` of `Android/data` is
 * blocked outright, it is the *only* way the log is ever read at all.
 *
 * Which is why the loss counters are on screen next to the lines: a reader who
 * cannot tell a complete record from a truncated one is being lied to by the
 * one artefact they came to trust.
 *
 * Reached by a hidden long-press in the bottom-right corner (see
 * `CockpitScreen`), never by cockpit chrome. The windscreen is the primary
 * instrument; a control the driver can hit by accident is a defect.
 *
 * Read on IO, never on the main thread — the file is capped but still hundreds
 * of KB, and this opens while the cockpit is live.
 */
@Composable
fun logViewerPanel(
    log: RollingFileLog,
    theme: ConceptTheme,
    onClose: () -> Unit,
    /**
     * Lines dropped by the pre-file buffer under a burst (see
     * [org.pureagave.zodiac.control.data.log.FileLogTree.droppedBeforeWrite]).
     * A supplier, so it is read fresh inside each snapshot; defaults to zero for
     * previews and any caller that has no buffered tree.
     */
    bufferOverflow: () -> Long = { 0L },
) {
    var snapshot by remember { mutableStateOf(LogSnapshot()) }
    var reloads by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(reloads) {
        val read =
            withContext(Dispatchers.IO) {
                LogSnapshot(
                    lines = log.tail(TAIL_LINES),
                    sizeKb = log.files().sumOf { it.length() } / BYTES_PER_KB,
                    dropped = log.droppedLines,
                    agedOut = log.discardedLines,
                    rotationFailures = log.rotationFailures,
                    overflow = bufferOverflow(),
                    lastError = log.lastError?.let { "${it::class.java.simpleName}: ${it.message}" },
                )
            }
        snapshot = read
        // Newest last, and the newest is what you opened this for. The
        // aged-out marker, when present, is item 0 and shifts the rest.
        val leading = if (read.agedOut > 0) 1 else 0
        if (read.lines.isNotEmpty()) listState.scrollToItem(read.lines.lastIndex + leading)
    }
    val lines = snapshot.lines

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
            header(theme, snapshot, onReload = { reloads++ }, onClose = onClose)
            snapshot.lastError?.let {
                Text(
                    text = "LAST ERROR  $it",
                    color = theme.error,
                    fontFamily = FontFamily.Monospace,
                    fontSize = LINE_SP.sp,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
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
                    // Scrolling to the top of the buffer must not read as the
                    // beginning of the story when it is really where the story
                    // was cut. The header carries the same number; this puts it
                    // where the cut actually is.
                    if (snapshot.agedOut > 0) {
                        item {
                            Text(
                                text = "— ${snapshot.agedOut} earlier lines aged out of the rotation window —",
                                color = theme.dim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = LINE_SP.sp,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
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
    snapshot: LogSnapshot,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "LOG  ${snapshot.lines.size} lines  ${"%.0f".format(snapshot.sizeKb)} KB",
            color = theme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = HEADER_SP.sp,
        )
        // Silent loss would make the log a liar about its own completeness, so
        // both ways a line can vanish get a chip here — but they are different
        // claims and are coloured as such. Aged out is the window working as
        // designed (data value, purple); dropped and a refused rotation are the
        // log failing at its job (fault, red).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (snapshot.agedOut > 0) headerChip("${snapshot.agedOut} AGED OUT", theme.accent)
            // Buffer overflow is load-shedding, not an IO failure — the app
            // out-logged the flash and the pre-file buffer shed the oldest to
            // stay bounded. Coloured with AGED OUT (by-design loss), not with
            // the red faults, but still on screen: it means the record has
            // holes these other counters can't account for.
            if (snapshot.overflow > 0) headerChip("${snapshot.overflow} OVERFLOW", theme.accent)
            if (snapshot.dropped > 0) headerChip("${snapshot.dropped} DROPPED", theme.error)
            if (snapshot.rotationFailures > 0) headerChip("${snapshot.rotationFailures} ROTATE FAIL", theme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            panelButton("RELOAD", theme.primary, onReload)
            panelButton("CLOSE", theme.error, onClose)
        }
    }
}

@Composable
private fun headerChip(
    text: String,
    color: Color,
) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = HEADER_SP.sp,
    )
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
