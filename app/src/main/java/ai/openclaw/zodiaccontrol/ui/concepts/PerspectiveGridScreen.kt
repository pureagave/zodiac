package ai.openclaw.zodiaccontrol.ui.concepts

import ai.openclaw.zodiaccontrol.ui.playamap.MapPalette
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Concept B map palette: bright neon-green BRC features over the receding
 * grid backdrop. Plazas and major art fall back to B's accent yellow so
 * they read as foreground beacons against the green city.
 */
private val PerspectivePalette =
    MapPalette(
        fence = Color(0xFF00FF66),
        street = Color(0xFF1F8F46),
        streetOutline = Color(0xFF0C4220),
        plaza = Color(0xFFFFF700),
        toilet = Color(0xFFB266FF),
        cpn = Color(0xFF00FF66),
        artMajor = Color(0xFFFFF700),
        artMinor = Color(0xFF6BCC4F),
        grid = Color(0xFF1F6E37),
        labelsEnabled = true,
        labelPrimary = Color(0xFFB0FFB0),
    )

@Composable
fun perspectiveGridScreen(
    viewModel: CockpitViewModel,
    onCycleConcept: () -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val theme = ThemePerspective

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(theme.background)
                .padding(12.dp)
                .border(2.dp, theme.primary),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(theme.primary)
                        .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "ENV: ZODIAC",
                    color = theme.background,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    text = "1: DATA   3: DESTINATION   2: COURSE   4: FREIGHT",
                    color = theme.background,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxSize()) {
                ladderColumn(theme = theme, modifier = Modifier.fillMaxHeight().width(120.dp))
                Spacer(Modifier.width(8.dp))

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(1.dp, theme.primary),
                ) {
                    playaMapPanel(
                        state = state,
                        viewModel = viewModel,
                        style =
                            PlayaMapPanelStyle(
                                palette = PerspectivePalette,
                                egoStyle = EgoStyle.HEX,
                                egoColor = theme.accent,
                                allowTilt = true,
                                showRetroGrid = true,
                            ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    cornerFiducials(color = theme.primary)
                }

                Spacer(Modifier.width(8.dp))
                conceptControlStrip(
                    state = state,
                    viewModel = viewModel,
                    theme = theme,
                    modifier = Modifier.fillMaxHeight().width(240.dp),
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
private fun ladderColumn(
    theme: ConceptTheme,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.border(1.dp, theme.primary).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Object Z:430", color = theme.secondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("Interval", color = theme.secondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text("Distance", color = theme.secondary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Spacer(Modifier.height(8.dp))
        listOf("500 000", "—", "—", "—", "400 000", "—", "—", "—", "300 000", "—", "—").forEach { v ->
            Text(
                text = "— $v",
                color = if (v == "—") theme.dim else theme.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun cornerFiducials(color: Color) {
    val fid: @Composable (Modifier) -> Unit = { mod ->
        Text(text = "+", color = color, fontFamily = FontFamily.Monospace, fontSize = 20.sp, modifier = mod)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        fid(Modifier.align(Alignment.TopStart).padding(6.dp))
        fid(Modifier.align(Alignment.TopEnd).padding(6.dp))
        fid(Modifier.align(Alignment.BottomStart).padding(6.dp))
        fid(Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}
