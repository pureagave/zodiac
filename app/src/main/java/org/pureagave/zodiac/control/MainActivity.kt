package org.pureagave.zodiac.control

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.pureagave.zodiac.control.core.permission.grantedAnythingNew
import org.pureagave.zodiac.control.core.permission.permissionsToRequest
import org.pureagave.zodiac.control.core.permission.requiredCockpitPermissions
import org.pureagave.zodiac.control.ui.state.luxToBrightness
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContent { zodiacApp() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    /**
     * Full-screen kiosk chrome for the mounted dashboard: draw edge-to-edge and
     * hide the status + navigation bars so the cockpit owns the whole panel
     * (targetSdk 35 forces edge-to-edge on Android 15+, which otherwise leaves
     * the bottom strip under the gesture bar). Bars reappear transiently on an
     * edge swipe, then auto-hide. Re-applied on focus regain because the system
     * restores the bars after dialogs / focus loss.
     */
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun zodiacApp() {
    val app = LocalContext.current.applicationContext as ZodiacApplication

    val viewModel: CockpitViewModel =
        viewModel(
            factory =
                CockpitViewModelFactory(
                    telemetryRepository = app.telemetryRepository,
                    vehicleGateway = app.vehicleGateway,
                    playaMapRepository = app.playaMapRepository,
                    locationSource = app.locationSource,
                    preferences = app.preferences,
                    fakeLocationSource = app.fakeLocationSource,
                    poisFlow = app.discoveryRepository.pois,
                    threatsFlow = app.threatSource.threats,
                    visionFeedFlow = app.threatSource.feedState,
                    beaconSensors = app.networkLocationSource.beaconSensors,
                ),
        )

    val context = LocalContext.current
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            // If anything was just granted, kick the active location source so
            // a previously-Error state (from "permission not granted") flips to
            // Searching/Active without needing the user to toggle a chip.
            if (grantedAnythingNew(results)) viewModel.restartLocationSource()
        }
    LaunchedEffect(Unit) {
        // Only ask for what's missing. Re-requesting a held permission returns
        // `true` from the launcher, which is indistinguishable from a fresh
        // grant — that was restarting the location source on every cold launch
        // (harmless on FAKE, a multicast rebind on NET).
        val missing =
            permissionsToRequest(requiredCockpitPermissions(Build.VERSION.SDK_INT)) { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    autoDim(viewModel)
    cockpitScreen(viewModel = viewModel, burnInManager = app.burnInManager)
}

/**
 * Ambient-light auto-dim: map the beacon's `$ZENV` lux to a window-brightness
 * fraction and apply it to the Activity window. No UI of its own — a cheap
 * effect-only composable, so its recomposition on each lux update doesn't touch
 * the cockpit tree. Null lux (no beacon) restores the system brightness.
 */
@Composable
private fun autoDim(viewModel: CockpitViewModel) {
    val lux by viewModel.uiState.collectAsStateWithLifecycle()
    val window = (LocalContext.current as? ComponentActivity)?.window
    val ambientLux = lux.ambientLux
    LaunchedEffect(ambientLux, window) {
        val target = window ?: return@LaunchedEffect
        target.attributes =
            target.attributes.apply {
                screenBrightness =
                    ambientLux?.let { luxToBrightness(it) }
                        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
    }
}
