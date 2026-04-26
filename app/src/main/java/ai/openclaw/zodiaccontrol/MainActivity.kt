package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.FakeTelemetryRepository
import ai.openclaw.zodiaccontrol.data.RoutedVehicleGateway
import ai.openclaw.zodiaccontrol.data.playa.AssetsPlayaMapRepository
import ai.openclaw.zodiaccontrol.data.sensor.BleLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.LocationSourceRegistry
import ai.openclaw.zodiaccontrol.data.sensor.RoutedLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.SystemLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.UsbLocationSource
import ai.openclaw.zodiaccontrol.data.transport.FakeTransportAdapter
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry
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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.MainScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { zodiacApp() }
    }
}

@Composable
private fun zodiacApp() {
    val context = LocalContext.current

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { /* Sources self-check on start; no callback action needed. */ }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val gateway =
        remember {
            val registry =
                TransportRegistry(
                    adapters =
                        listOf(
                            FakeTransportAdapter(TransportType.BLE),
                            FakeTransportAdapter(TransportType.USB),
                            FakeTransportAdapter(TransportType.WIFI),
                        ),
                )

            RoutedVehicleGateway(
                transportRegistry = registry,
                initialTransport = TransportType.BLE,
            )
        }
    val playaMapRepository = remember { AssetsPlayaMapRepository(context.assets) }
    val locationSource =
        remember {
            val sensorScope = MainScope()
            val registry =
                LocationSourceRegistry(
                    sources =
                        listOf(
                            FakeLocationSource(scope = sensorScope),
                            SystemLocationSource(applicationContext = context.applicationContext),
                            BleLocationSource(
                                applicationContext = context.applicationContext,
                                scope = sensorScope,
                            ),
                            UsbLocationSource(
                                applicationContext = context.applicationContext,
                                scope = sensorScope,
                            ),
                        ),
                )
            RoutedLocationSource(
                registry = registry,
                scope = sensorScope,
                initialType = LocationSourceType.FAKE,
            )
        }

    val viewModel: CockpitViewModel =
        viewModel(
            factory =
                CockpitViewModelFactory(
                    telemetryRepository = FakeTelemetryRepository(),
                    vehicleGateway = gateway,
                    playaMapRepository = playaMapRepository,
                    locationSource = locationSource,
                ),
        )
    crtVectorScreen(viewModel = viewModel)
}
