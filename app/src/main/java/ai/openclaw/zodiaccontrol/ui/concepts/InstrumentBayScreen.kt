package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.ui.playamap.MapPalette
import ai.openclaw.zodiaccontrol.ui.playamap.MapPointStyle
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.sin

/**
 * Concept D map palette: BLOCK point style so toilets, CPNs, and art render
 * as small framed rects matching the bay's orange-on-black tile aesthetic.
 * As the user zooms in, those blocks get visibly larger and read like
 * miniature instrument tiles scattered across the map.
 */
private val InstrumentBayPalette =
    MapPalette(
        fence = Color(0xFFFF8A00),
        street = Color(0xFFFFAE3A),
        streetOutline = Color(0xFF7A4200),
        plaza = Color(0xFFFFD166),
        toilet = Color(0xFFB266FF),
        cpn = Color(0xFFFF8A00),
        artMajor = Color(0xFFFFD166),
        artMinor = Color(0xFFFFAE3A),
        grid = Color(0xFFA36000),
        pointStyle = MapPointStyle.BLOCK,
        labelsEnabled = true,
        labelPrimary = Color(0xFFFFD9A0),
    )

@Composable
fun instrumentBayScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val theme = ThemeInstrumentBay

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(theme.background)
                .padding(12.dp)
                .border(2.dp, theme.primary),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Top header strip: title + system code
            Row(modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tile(theme = theme, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        text = "NOSTROMO  //  ZODIAC INSTRUMENT BAY  //  STATION 04",
                        color = theme.accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("SYS:ZD :BL: 76.75 :OB:", color = theme.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                tile(theme = theme, modifier = Modifier.width(140.dp).fillMaxHeight()) {
                    Text("UTC", color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        text = "19:38:23",
                        color = theme.secondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Left column: heading dial + speed gauge stacked
                Column(
                    modifier = Modifier.fillMaxHeight().width(160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tile(theme = theme, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Text("HEADING", color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                            headingDial(theme = theme, headingDeg = state.headingDeg)
                        }
                    }
                    tile(theme = theme, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Text("SPEED KPH", color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                            speedGauge(theme = theme, speedKph = state.speedKph)
                        }
                    }
                }

                // Center: large map tile — real BRC playa rendered with D's
                // blocky orange palette, honouring TOP/TILT toggle and the
                // shared zoom/pan/recenter controls.
                tile(theme = theme, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        text = "GROUND TRACK // ZOOM ${"%.2f".format(state.pixelsPerMeter)} px/m",
                        color = theme.accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                        playaMapPanel(
                            state = state,
                            viewModel = viewModel,
                            style =
                                PlayaMapPanelStyle(
                                    palette = InstrumentBayPalette,
                                    egoStyle = EgoStyle.TRIANGLE,
                                    egoColor = theme.accent,
                                    allowTilt = true,
                                    showRetroGrid = false,
                                ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Right column: throttle trace + cell bars + control strip
                Column(
                    modifier = Modifier.fillMaxHeight().width(280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tile(theme = theme, modifier = Modifier.fillMaxWidth().height(120.dp)) {
                        Text("THROTTLE TRACE", color = theme.accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Box(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                            throttleTrace(theme = theme)
                        }
                    }
                    tile(theme = theme, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                        cellBar(theme = theme, label = "CELL A", percent = 70)
                        Spacer(Modifier.height(4.dp))
                        cellBar(theme = theme, label = "CELL B", percent = 45)
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        conceptControlStrip(
                            state = state,
                            viewModel = viewModel,
                            theme = theme,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Hazard chevron footer
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .border(2.dp, theme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stripeW = 28f
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(
                            color = theme.primary,
                            start = Offset(x, size.height),
                            end = Offset(x + size.height, 0f),
                            strokeWidth = stripeW,
                        )
                        x += stripeW * 2f
                    }
                }
                Text(
                    text = "⚠  CAUTION  ZODIAC TRANSIT ACTIVE  ⚠",
                    color = theme.background,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }

        conceptSwitcher(
            current = state.concept,
            onCycle = onCycleConcept,
            accent = theme.accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

@Composable
private fun tile(
    theme: ConceptTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.border(2.dp, theme.primary).padding(8.dp)) { content() }
}

@Composable
private fun headingDial(
    theme: ConceptTheme,
    headingDeg: Int,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.width.coerceAtMost(size.height) / 2f - 4f
        drawCircle(theme.primary, r, Offset(cx, cy), style = Stroke(width = 1.6f))
        drawCircle(theme.primary, r * 0.78f, Offset(cx, cy), style = Stroke(width = 1f))
        // Cardinal ticks
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0 - 90.0)
            val outer = Offset((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            val inner = Offset((cx + (r - 8f) * cos(a)).toFloat(), (cy + (r - 8f) * sin(a)).toFloat())
            drawLine(theme.primary, outer, inner, strokeWidth = 1.4f)
        }
        // Needle
        val needle = Math.toRadians(headingDeg.toDouble() - 90.0)
        drawLine(
            color = theme.accent,
            start = Offset(cx, cy),
            end =
                Offset(
                    (cx + r * 0.86f * cos(needle)).toFloat(),
                    (cy + r * 0.86f * sin(needle)).toFloat(),
                ),
            strokeWidth = 3f,
        )
        drawCircle(theme.accent, 4f, Offset(cx, cy))
    }
}

@Composable
private fun speedGauge(
    theme: ConceptTheme,
    speedKph: Int,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height * 0.85f
        val r = size.width.coerceAtMost(size.height) * 0.48f
        // Half arc (180° → 0°)
        val ticks = 6
        for (i in 0..ticks) {
            val a = Math.toRadians(180.0 - i * (180.0 / ticks))
            val outer = Offset((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            val inner = Offset((cx + (r - 8f) * cos(a)).toFloat(), (cy + (r - 8f) * sin(a)).toFloat())
            drawLine(theme.primary, outer, inner, strokeWidth = 1.4f)
        }
        val frac = (speedKph / 160f).coerceIn(0f, 1f)
        val needleA = Math.toRadians(180.0 - frac.toDouble() * 180.0)
        drawLine(
            color = theme.accent,
            start = Offset(cx, cy),
            end =
                Offset(
                    (cx + r * 0.92f * cos(needleA)).toFloat(),
                    (cy + r * 0.92f * sin(needleA)).toFloat(),
                ),
            strokeWidth = 3f,
        )
        drawCircle(theme.accent, 5f, Offset(cx, cy))
    }
}

@Composable
private fun throttleTrace(theme: ConceptTheme) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pts =
            floatArrayOf(
                0.8f, 0.7f, 0.76f, 0.4f, 0.5f, 0.18f, 0.32f, 0.24f, 0.6f,
                0.44f, 0.3f, 0.12f, 0.28f, 0.36f, 0.18f, 0.42f, 0.3f, 0.54f, 0.4f, 0.62f, 0.3f,
            )
        val w = size.width
        val h = size.height
        val dx = w / (pts.size - 1)
        for (i in 0 until pts.size - 1) {
            drawLine(
                color = theme.secondary,
                start = Offset(i * dx, pts[i] * h),
                end = Offset((i + 1) * dx, pts[i + 1] * h),
                strokeWidth = 2f,
            )
        }
    }
}

@Composable
private fun cellBar(
    theme: ConceptTheme,
    label: String,
    percent: Int,
) {
    Box(modifier = Modifier.fillMaxWidth().height(28.dp).border(1.dp, theme.primary)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val frac = (percent / 100f).coerceIn(0f, 1f)
            drawLine(
                color = theme.secondary,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width * frac, size.height / 2f),
                strokeWidth = size.height,
            )
        }
        Text(
            text = "$label  $percent%",
            color = theme.background,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
        )
    }
}
