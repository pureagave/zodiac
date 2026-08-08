package org.pureagave.zodiac.control

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pureagave.zodiac.control.burnin.BurnInMitigationManager
import org.pureagave.zodiac.control.burnin.burnInScaffold
import org.pureagave.zodiac.control.core.log.RollingFileLog
import org.pureagave.zodiac.control.core.model.CockpitConcept
import org.pureagave.zodiac.control.ui.concepts.ThemeTracker
import org.pureagave.zodiac.control.ui.concepts.driverNightScreen
import org.pureagave.zodiac.control.ui.concepts.instrumentBayScreen
import org.pureagave.zodiac.control.ui.concepts.motionTrackerScreen
import org.pureagave.zodiac.control.ui.concepts.provideCockpitTheme
import org.pureagave.zodiac.control.ui.debug.logViewerPanel
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
    fileLog: RollingFileLog,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cycle: () -> Unit = viewModel::cycleConcept
    var logsOpen by remember { mutableStateOf(false) }

    // One provider at the root: every concept and overlay below reads its
    // palette from LocalCockpitTheme instead of being handed one. The concepts
    // share a palette today, so this changes no pixels — it's the seam that
    // lets one diverge, and what the second map consumer (A3) will need.
    burnInScaffold(manager = burnInManager, zone = state.concept.name) {
        provideCockpitTheme(ThemeTracker) {
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
                // Hidden bottom-right long-press opens the log viewer, matching the
                // burn-in scaffold's corner-gesture convention (top-left parks,
                // bottom-left tunes) and taking the one corner still free. Nothing
                // about it should be discoverable by a passenger poking the screen.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(LOG_HOT_ZONE)
                            .pointerInput(Unit) { detectTapGestures(onLongPress = { logsOpen = true }) },
                )
                if (logsOpen) {
                    logViewerPanel(log = fileLog, theme = ThemeTracker, onClose = { logsOpen = false })
                }
            }
        }
    }
}

/** Corner hot-zone for the log-viewer long-press; mirrors the burn-in scaffold's. */
private val LOG_HOT_ZONE = 56.dp
