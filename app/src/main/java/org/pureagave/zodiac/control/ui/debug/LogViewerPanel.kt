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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.pureagave.zodiac.control.core.log.LogSeverity
import org.pureagave.zodiac.control.core.log.RollingFileLog
import org.pureagave.zodiac.control.core.log.logLineSeverity
import org.pureagave.zodiac.control.core.telemetry.FleetRoleName
import org.pureagave.zodiac.control.core.telemetry.FleetRosterEntry
import org.pureagave.zodiac.control.core.telemetry.FleetStatus
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
 * The list item to scroll to so the newest line rests at the bottom, or null
 * when there is nothing to show. When [agedOut] > 0 the "N earlier lines aged
 * out" banner takes item 0 and pushes every line down one, so the newest line
 * lives at [lineCount] rather than [lineCount] - 1. Off by one here lands the
 * view on the banner or one line shy of the newest — the entry you opened this
 * for. Pulled out of the composable so the arithmetic is unit-testable.
 */
internal fun logViewerScrollTarget(
    lineCount: Int,
    agedOut: Long,
): Int? {
    if (lineCount <= 0) return null
    val leading = if (agedOut > 0) 1 else 0
    return (lineCount - 1) + leading
}

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
    /**
     * FLEET-1 fleet-version roster (`FleetVersionMonitor.roster`) shown in the
     * FLEET tab — who's on which build, worst-status-first. Collected only while
     * the panel is open. Defaults to empty for previews / callers with no monitor.
     */
    fleetRoster: StateFlow<List<FleetRosterEntry>> = MutableStateFlow(emptyList()),
) {
    var snapshot by remember { mutableStateOf(LogSnapshot()) }
    var reloads by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(PanelTab.LOG) }
    val roster by fleetRoster.collectAsStateWithLifecycle()
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
        // Newest last, and the newest is what you opened this for.
        logViewerScrollTarget(read.lines.size, read.agedOut)?.let { listState.scrollToItem(it) }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabButton("LOG", tab == PanelTab.LOG, theme) { tab = PanelTab.LOG }
                    tabButton("FLEET", tab == PanelTab.FLEET, theme) { tab = PanelTab.FLEET }
                    Text(
                        text =
                            if (tab == PanelTab.LOG) {
                                "${snapshot.lines.size} lines  ${"%.0f".format(snapshot.sizeKb)} KB"
                            } else {
                                fleetSummary(roster)
                            },
                        color = theme.dim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = HEADER_SP.sp,
                    )
                }
                // Loss chips belong to the log; the roster shows each node's status per row.
                if (tab == PanelTab.LOG) logLossChips(snapshot, theme)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    panelButton("RELOAD", theme.primary) { reloads++ }
                    panelButton("CLOSE", theme.error, onClose)
                }
            }
            when (tab) {
                PanelTab.FLEET -> fleetRosterView(roster, theme)
                PanelTab.LOG -> logBody(snapshot, lines, listState, theme)
            }
        }
    }
}

/** Which view the log-viewer overlay is showing. */
private enum class PanelTab { LOG, FLEET }

/** The rolling-log body: last-error banner, then the tailed lines (or an empty note). */
@Composable
private fun logBody(
    snapshot: LogSnapshot,
    lines: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    theme: ConceptTheme,
) {
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

/**
 * The FLEET-1 roster: one row per node, worst-status-first (the ordering
 * `FleetRoster.compute` already applied). CURRENT is blue (status-ok); BEHIND /
 * OFFLINE / UNKNOWN are red (a stale or unheard-from node is a problem you want to
 * see). `▸` marks this device. The build string is `FleetVersion.identity.render()`.
 */
@Composable
private fun fleetRosterView(
    roster: List<FleetRosterEntry>,
    theme: ConceptTheme,
) {
    if (roster.isEmpty()) {
        Text(
            text = "— no nodes heard — every device is silent, or none is emitting \$ZVER yet —",
            color = theme.dim,
            fontFamily = FontFamily.Monospace,
            fontSize = LINE_SP.sp,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        items(roster.size) { i -> rosterRow(roster[i], theme) }
    }
}

@Composable
private fun rosterRow(
    entry: FleetRosterEntry,
    theme: ConceptTheme,
) {
    val marker = if (entry.isSelf) "▸" else " "
    val role = FleetRoleName.of(entry.version.name)
    val line =
        "$marker ${role.padEnd(ROSTER_NAME_COLS)} " +
            "${entry.status.name.padEnd(ROSTER_STATUS_COLS)} ${entry.version.identity.render()}"
    Text(
        text = line,
        color = if (entry.status == FleetStatus.CURRENT) theme.secondary else theme.error,
        fontFamily = FontFamily.Monospace,
        fontSize = LINE_SP.sp,
        fontWeight = if (entry.isSelf) FontWeight.Bold else FontWeight.Normal,
        softWrap = false,
        maxLines = 1,
    )
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

/**
 * The rolling-log's loss chips. Silent loss would make the log a liar about its
 * own completeness, so each way a line can vanish gets a chip — coloured by what
 * it means. Aged out is the window working as designed (data value, purple);
 * buffer overflow is load-shedding (also by-design loss, purple); dropped and a
 * refused rotation are the log failing at its job (fault, red). LOG tab only —
 * the roster shows each node's status per row.
 */
@Composable
private fun logLossChips(
    snapshot: LogSnapshot,
    theme: ConceptTheme,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (snapshot.agedOut > 0) headerChip("${snapshot.agedOut} AGED OUT", theme.accent)
        if (snapshot.overflow > 0) headerChip("${snapshot.overflow} OVERFLOW", theme.accent)
        if (snapshot.dropped > 0) headerChip("${snapshot.dropped} DROPPED", theme.error)
        if (snapshot.rotationFailures > 0) headerChip("${snapshot.rotationFailures} ROTATE FAIL", theme.error)
    }
}

/** One-line summary of the roster for the FLEET tab header: node count + how many are not CURRENT. */
private fun fleetSummary(roster: List<FleetRosterEntry>): String {
    val stale = roster.count { it.status != FleetStatus.CURRENT }
    val tail = if (stale == 0) "all current" else "$stale stale"
    return "${roster.size} node${if (roster.size == 1) "" else "s"} · $tail"
}

/** A LOG/FLEET tab toggle — the active one is bright and boxed, the inactive one dim. */
@Composable
private fun tabButton(
    label: String,
    active: Boolean,
    theme: ConceptTheme,
    onClick: () -> Unit,
) {
    val color = if (active) theme.primary else theme.dim
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

// Fixed monospace column widths for the FLEET roster rows so name / status / build
// align. Name column fits the longest role label ("PASSENGER 11") plus a gap.
private const val ROSTER_NAME_COLS = 14
private const val ROSTER_STATUS_COLS = 8
