package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.burnin.BurnInMitigationManager
import ai.openclaw.zodiaccontrol.burnin.burnInScaffold
import ai.openclaw.zodiaccontrol.core.model.CockpitConcept
import ai.openclaw.zodiaccontrol.ui.concepts.ThemeTracker
import ai.openclaw.zodiaccontrol.ui.concepts.instrumentBayScreen
import ai.openclaw.zodiaccontrol.ui.concepts.motionTrackerScreen
import ai.openclaw.zodiaccontrol.ui.ops.addressEntryPanel
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Top-level dispatcher: reads the current [CockpitConcept] and renders the
 * matching screen, wrapped in [burnInScaffold] so OLED burn-in mitigation
 * (pixel-shift, brightness breathe/dim, idle sleep) cascades to every concept
 * from a single node. The address-entry keypad is a shared overlay on top of
 * whichever concept is active. The operational readout (clock / sun / drive-to)
 * is a first-class element inside each concept (see `ui/ops/opsReadout`). The
 * cycle callback advances to the next in [CockpitConcept.next] order.
 */
@Composable
fun cockpitScreen(
    viewModel: CockpitViewModel,
    burnInManager: BurnInMitigationManager,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cycle: () -> Unit = viewModel::cycleConcept

    burnInScaffold(manager = burnInManager) {
        Box(Modifier.fillMaxSize()) {
            when (state.concept) {
                CockpitConcept.RADAR -> motionTrackerScreen(viewModel = viewModel, onCycleConcept = cycle)
                CockpitConcept.MAP -> instrumentBayScreen(viewModel = viewModel, onCycleConcept = cycle)
            }
            if (state.addressEntryOpen) {
                addressEntryPanel(
                    theme = ThemeTracker,
                    egoFix = state.egoFix,
                    onDriveToAddress = viewModel::driveToAddress,
                    onClose = { viewModel.setAddressEntryOpen(false) },
                )
            }
        }
    }
}
