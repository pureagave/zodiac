package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.ui.playamap.MapPalette
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val SWEEP_PERIOD_S = 4f
private const val SWEEP_WIDTH_DEG = 60f
private const val FORWARD_CONE_DEG = 70f

/**
 * Dim base palette — features barely show until the sweep arm lights them up.
 * Skeletal city outline only, so the M41A "blip on a dark scope" effect reads.
 */
private val TrackerBasePalette =
    MapPalette(
        fence = Color(0xFF1E5C16),
        street = Color(0xFF124210),
        streetOutline = Color(0xFF0A2A0A),
        plaza = Color(0xFF2A5C16),
        toilet = Color(0xFF1E5C16),
        cpn = Color(0xFF1E5C16),
        artMajor = Color(0xFF2A5C16),
        artMinor = Color(0xFF124210),
        grid = Color(0xFF0A2A0A),
    )

/** Lit palette — what the wedge re-renders inside its clip region. */
private val TrackerLitPalette =
    MapPalette(
        fence = Color(0xFFB8FF98),
        street = Color(0xFF7EFF62),
        streetOutline = Color(0xFF3AAA2C),
        plaza = Color(0xFFFFF700),
        toilet = Color(0xFFFFF700),
        cpn = Color(0xFF7EFF62),
        artMajor = Color(0xFFFFF700),
        artMinor = Color(0xFF7EFF62),
        grid = Color(0xFF3AAA2C),
    )

@Composable
fun motionTrackerScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val theme = ThemeTracker

    var sweepDeg by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    sweepDeg = (sweepDeg + dt * 360f / SWEEP_PERIOD_S) % 360f
                }
                last = now
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(theme.background)
                .padding(12.dp)
                .border(2.dp, theme.primary),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "MOTION TRACKER",
                    color = theme.secondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("M41A / ZODIAC BAY", color = theme.secondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        "RNG ${"%.1f".format(rangeMetres(state.pixelsPerMeter))}m",
                        color = theme.secondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            navCueBar(cue = state.navCue, theme = theme)
            Spacer(Modifier.height(4.dp))

            Row(Modifier.fillMaxSize()) {
                trackerStatColumn(
                    pair =
                        StatPair(
                            title1 = "BEARING",
                            value1 = "%03d".format(state.headingDeg),
                            title2 = "SPEED",
                            value2 = "%03d".format(state.speedKph),
                        ),
                    theme = theme,
                    modifier = Modifier.fillMaxHeight().width(170.dp),
                )

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(1f)
                                .border(2.dp, theme.primary),
                    ) {
                        playaMapPanel(
                            state = state,
                            viewModel = viewModel,
                            style =
                                PlayaMapPanelStyle(
                                    palette = TrackerBasePalette,
                                    egoStyle = EgoStyle.TRIANGLE,
                                    egoColor = theme.accent,
                                    allowTilt = false,
                                    clipCircular = true,
                                    sweep =
                                        SweepOverlay(
                                            sweepDeg = sweepDeg,
                                            sweepWidthDeg = SWEEP_WIDTH_DEG,
                                            litPalette = TrackerLitPalette,
                                            armColor = theme.primary,
                                            coneFwdDeg = FORWARD_CONE_DEG,
                                            coneFill = Color(0x267EFF62),
                                        ),
                                ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(
                    modifier = Modifier.fillMaxHeight().width(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    trackerStatColumn(
                        pair =
                            StatPair(
                                title1 = "RANGE",
                                value1 = "%.0fm".format(rangeMetres(state.pixelsPerMeter)),
                                title2 = "ZOOM",
                                value2 = "%.2f".format(state.pixelsPerMeter),
                            ),
                        theme = theme,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    conceptControlStrip(
                        state = state,
                        viewModel = viewModel,
                        theme = theme,
                        modifier = Modifier.fillMaxWidth(),
                        showTiltToggle = false,
                    )
                }
            }
        }

        scanlineOverlay()

        conceptSwitcher(
            current = state.concept,
            onCycle = onCycleConcept,
            accent = theme.accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        recenterButton(
            followMode = state.followMode,
            theme = theme,
            onClick = viewModel::recenterPan,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

private data class StatPair(val title1: String, val value1: String, val title2: String, val value2: String)

@Composable
private fun trackerStatColumn(
    pair: StatPair,
    theme: ConceptTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.border(1.dp, theme.primary).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(pair.title1, color = theme.secondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(pair.value1, color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(pair.title2, color = theme.secondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(pair.value2, color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun scanlineOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 4f
        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = Color(0x1100FF66),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += step
        }
    }
}

/**
 * Approx. on-screen radius (in playa metres) covered by the scope at the
 * current zoom — surfaced as the "RNG" header so the M41A's metre callout
 * actually reflects the visible map sector.
 */
private fun rangeMetres(pixelsPerMeter: Double): Float = (300.0 / pixelsPerMeter).toFloat()
