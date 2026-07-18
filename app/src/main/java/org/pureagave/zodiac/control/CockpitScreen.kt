package org.pureagave.zodiac.control

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pureagave.zodiac.control.burnin.BurnInMitigationManager
import org.pureagave.zodiac.control.burnin.burnInScaffold
import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.ui.concepts.ThemeTracker
import org.pureagave.zodiac.control.ui.concepts.driverNightScreen
import org.pureagave.zodiac.control.ui.concepts.instrumentBayScreen
import org.pureagave.zodiac.control.ui.concepts.motionTrackerScreen
import org.pureagave.zodiac.control.ui.ops.addressEntryPanel
import org.pureagave.zodiac.control.ui.ops.passingCallout
import org.pureagave.zodiac.control.ui.ops.streetCrossingPopup
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel

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
                CockpitConcept.DRIVER -> driverNightScreen(viewModel = viewModel, onCycleConcept = cycle)
            }
            state.streetPopup?.let { streetCrossingPopup(theme = ThemeTracker, name = it) }
            state.passingCallout?.let { passingCallout(theme = ThemeTracker, name = it) }
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
