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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.pureagave.zodiac.control.core.permission.PermissionPrompt
import org.pureagave.zodiac.control.core.permission.grantedAnythingNew
import org.pureagave.zodiac.control.core.permission.permissionPromptFor
import org.pureagave.zodiac.control.core.permission.permissionsToRequest
import org.pureagave.zodiac.control.core.permission.requiredCockpitPermissions
import org.pureagave.zodiac.control.kiosk.KioskController
import org.pureagave.zodiac.control.ui.concepts.ThemeTracker
import org.pureagave.zodiac.control.ui.ops.permissionRationalePanel
import org.pureagave.zodiac.control.ui.state.luxToBrightness
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModel
import org.pureagave.zodiac.control.ui.viewmodel.CockpitViewModelFactory

class MainActivity : ComponentActivity() {
    private val kiosk by lazy { KioskController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContent { zodiacApp() }
    }

    override fun onResume() {
        super.onResume()
        // Re-engaged on every resume rather than once at create: a tablet that
        // somehow got out of lock task (a crash, a service dialog) should fall
        // back into it by itself. No-op unless provisioned as device owner.
        kiosk.engage(this)
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
    val viewModel = rememberCockpitViewModel(app)

    autoDim(viewModel)
    val audio by app.networkLocationSource.audioLevel.collectAsStateWithLifecycle()
    val passengerMode by app.displayRole.passengerMode.collectAsStateWithLifecycle()
    cockpitScreen(
        viewModel = viewModel,
        burnInManager = app.burnInManager,
        fileLog = app.fileLog,
        audio = audio,
        passengerMode = passengerMode,
        onSetPassengerMode = app.displayRole::setPassengerMode,
        artImages = app.artImages,
    )
    // Emitted *after* the cockpit deliberately: siblings at the root stack in
    // declaration order, so a gate declared first draws underneath the whole
    // UI and its panel is invisible. (It was — caught on device, not in review.)
    cockpitPermissionGate(onNewGrant = viewModel::restartLocationSource)
}

/**
 * Bind the ViewModel to the process-lifetime dependency graph. Extracted from
 * [zodiacApp] (L7) so the wiring — which grows every time a source is added —
 * doesn't crowd out what the composable actually does.
 */
@Composable
private fun rememberCockpitViewModel(app: ZodiacApplication): CockpitViewModel =
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

/**
 * The runtime-permission flow, start to finish: work out what's missing, ask
 * for exactly that, and explain first when Android says the user has already
 * declined once (because the next decline latches to "don't ask again" and
 * takes the system dialog with it).
 *
 * [onNewGrant] fires only on a genuine transition to granted, so a source that
 * was sitting in Error because of a missing permission re-attempts its start
 * path — and a fully-granted tablet restarts nothing on launch.
 */
@Composable
private fun cockpitPermissionGate(onNewGrant: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var showRationale by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            if (grantedAnythingNew(results)) onNewGrant()
        }

    LaunchedEffect(Unit) {
        missing =
            permissionsToRequest(requiredCockpitPermissions(Build.VERSION.SDK_INT)) { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        when (
            permissionPromptFor(missing) { permission ->
                activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false
            }
        ) {
            PermissionPrompt.NONE -> Unit
            PermissionPrompt.REQUEST -> permissionLauncher.launch(missing.toTypedArray())
            PermissionPrompt.RATIONALE -> showRationale = true
        }
    }

    if (showRationale) {
        permissionRationalePanel(
            theme = ThemeTracker,
            onContinue = {
                showRationale = false
                permissionLauncher.launch(missing.toTypedArray())
            },
            // NOT NOW is a real option: the cockpit is fully usable on the
            // synthetic and network GPS sources without this permission.
            onDismiss = { showRationale = false },
        )
    }
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
