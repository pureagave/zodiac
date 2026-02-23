package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.model.CockpitMode
import ai.openclaw.zodiaccontrol.core.model.Telemetry
import ai.openclaw.zodiaccontrol.core.model.VehicleCommand
import ai.openclaw.zodiaccontrol.data.FakeVehicleGateway
import ai.openclaw.zodiaccontrol.data.TelemetryRepository
import ai.openclaw.zodiaccontrol.ui.viewmodel.CockpitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun setHeading_sendsVehicleCommand() =
        runTest {
            val gateway = FakeVehicleGateway()
            val vm =
                CockpitViewModel(
                    telemetryRepository = StaticTelemetryRepo(),
                    vehicleGateway = gateway,
                )

            vm.setHeading(123)
            advanceUntilIdle()

            assertTrue(gateway.history().contains(VehicleCommand.SetHeading(123)))
        }
}

private class StaticTelemetryRepo : TelemetryRepository {
    override fun stream(): Flow<Telemetry> =
        flowOf(
            Telemetry(
                headingDeg = 42,
                speedKph = 30,
                thermalC = 60,
                linkStable = true,
                mode = CockpitMode.DIAGNOSTIC,
            ),
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val dispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
