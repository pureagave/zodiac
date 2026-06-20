package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.burnin.BurnInMitigationManager
import ai.openclaw.zodiaccontrol.burnin.burnInScaffold
import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.ui.concepts.instrumentBayScreen
import ai.openclaw.zodiaccontrol.ui.concepts.motionTrackerScreen
import ai.openclaw.zodiaccontrol.ui.concepts.perspectiveGridScreen
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.runtime.Composable
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
        when (concept) {
            CockpitConcept.A -> crtVectorScreen(viewModel = viewModel, onCycleConcept = cycle)
            CockpitConcept.B -> perspectiveGridScreen(viewModel = viewModel, onCycleConcept = cycle)
            CockpitConcept.C -> motionTrackerScreen(viewModel = viewModel, onCycleConcept = cycle)
            CockpitConcept.D -> instrumentBayScreen(viewModel = viewModel, onCycleConcept = cycle)
        }
    }
}
