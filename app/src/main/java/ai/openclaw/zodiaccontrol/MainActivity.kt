package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModelFactory
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { zodiacApp() }
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
                ),
        )

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            // If anything was just granted, kick the active location source so
            // a previously-Error state (from "permission not granted") flips to
            // Searching/Active without needing the user to toggle a chip.
            if (results.values.any { it }) viewModel.restartLocationSource()
        }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    cockpitScreen(viewModel = viewModel)
}
