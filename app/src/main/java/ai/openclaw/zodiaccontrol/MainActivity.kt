package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.data.FakeTelemetryRepository
import ai.openclaw.zodiaccontrol.data.FakeVehicleGateway
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModelFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { zodiacApp() }
    }
}

@Composable
private fun zodiacApp() {
    val viewModel: CockpitViewModel =
        viewModel(
            factory =
                CockpitViewModelFactory(
                    telemetryRepository = FakeTelemetryRepository(),
                    vehicleGateway = FakeVehicleGateway(),
                ),
        )
    crtVectorScreen(viewModel = viewModel)
}
