package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.data.FakeTelemetryRepository
import ai.openclaw.zodiaccontrol.data.RoutedVehicleGateway
import ai.openclaw.zodiaccontrol.data.transport.FakeTransportAdapter
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModelFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { zodiacApp() }
    }
}

@Composable
private fun zodiacApp() {
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

    val viewModel: CockpitViewModel =
        viewModel(
            factory =
                CockpitViewModelFactory(
                    telemetryRepository = FakeTelemetryRepository(),
                    vehicleGateway = gateway,
                ),
        )
    crtVectorScreen(viewModel = viewModel)
}
