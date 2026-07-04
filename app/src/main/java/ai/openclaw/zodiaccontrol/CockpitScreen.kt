package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.burnin.BurnInMitigationManager
import ai.openclaw.zodiaccontrol.burnin.burnInScaffold
import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.ui.concepts.instrumentBayScreen
import ai.openclaw.zodiaccontrol.ui.concepts.motionTrackerScreen
import ai.openclaw.zodiaccontrol.ui.ops.OPS_STRIP_HEIGHT_DP
import ai.openclaw.zodiaccontrol.ui.ops.opsStrip
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Top-level dispatcher: reads the current [CockpitConcept] and renders the
 * matching screen, wrapped in [burnInScaffold] so OLED burn-in mitigation
 * (pixel-shift, brightness breathe/dim, idle sleep) cascades to every concept
 * and overlay from a single node. The cycle callback is the same for every
 * concept — it advances to the next in [CockpitConcept.next] order — so every
 * concept's selector pill shares one wiring path.
 */
@Composable
fun cockpitScreen(
    viewModel: CockpitViewModel,
    burnInManager: BurnInMitigationManager,
) {
    val concept = viewModel.uiState.collectAsStateWithLifecycle().value.concept
    val cycle: () -> Unit = viewModel::cycleConcept

    burnInScaffold(manager = burnInManager) {
        Box(Modifier.fillMaxSize()) {
            // Reserve the strip's band so every concept (and its bottom-corner
            // chrome, e.g. the recenter button) renders above the footer rather
            // than under it.
            Box(Modifier.fillMaxSize().padding(bottom = OPS_STRIP_HEIGHT_DP.dp)) {
                when (concept) {
                    CockpitConcept.A -> crtVectorScreen(viewModel = viewModel, onCycleConcept = cycle)
                    CockpitConcept.C -> motionTrackerScreen(viewModel = viewModel, onCycleConcept = cycle)
                    CockpitConcept.D -> instrumentBayScreen(viewModel = viewModel, onCycleConcept = cycle)
                }
            }
            // Ambient operational strip in the reserved band. Collects its own
            // narrow state, so its per-second tick doesn't recompose the dispatch.
            opsStrip(viewModel = viewModel, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
