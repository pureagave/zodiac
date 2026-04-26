package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.FakeTelemetryRepository
import ai.openclaw.zodiaccontrol.data.RoutedVehicleGateway
import ai.openclaw.zodiaccontrol.data.TelemetryRepository
import ai.openclaw.zodiaccontrol.data.VehicleConnectionGateway
import ai.openclaw.zodiaccontrol.data.playa.AssetsPlayaMapRepository
import ai.openclaw.zodiaccontrol.data.playa.PlayaMapRepository
import ai.openclaw.zodiaccontrol.data.prefs.CockpitPreferences
import ai.openclaw.zodiaccontrol.data.prefs.DataStoreCockpitPreferences
import ai.openclaw.zodiaccontrol.data.sensor.BleLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.FakeLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.LocationSourceRegistry
import ai.openclaw.zodiaccontrol.data.sensor.RoutedLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.SystemLocationSource
import ai.openclaw.zodiaccontrol.data.sensor.UsbLocationSource
import ai.openclaw.zodiaccontrol.data.transport.FakeTransportAdapter
import ai.openclaw.zodiaccontrol.data.transport.TransportRegistry
import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime owner for the cockpit's manual DI graph. Replaces the
 * previous `remember { MainScope() }` inside the Composable, which leaked
 * the scope on Activity recreation. The registry, routed sources, and
 * gateway live as long as the process — exactly what we want for sensor
 * subscriptions that should outlast a configuration change.
 */
class ZodiacApplication : Application() {
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val telemetryRepository: TelemetryRepository by lazy { FakeTelemetryRepository() }

    val vehicleGateway: VehicleConnectionGateway by lazy {
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
            scope = applicationScope,
        )
    }

    val playaMapRepository: PlayaMapRepository by lazy { AssetsPlayaMapRepository(assets) }

    private val preferencesDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = applicationScope) {
            applicationContext.preferencesDataStoreFile("cockpit_prefs")
        }
    }

    val preferences: CockpitPreferences by lazy {
        DataStoreCockpitPreferences(preferencesDataStore)
    }

    val locationSource: RoutedLocationSource by lazy {
        val registry =
            LocationSourceRegistry(
                sources =
                    listOf(
                        FakeLocationSource(scope = applicationScope),
                        SystemLocationSource(applicationContext = this),
                        BleLocationSource(
                            applicationContext = this,
                            scope = applicationScope,
                        ),
                        UsbLocationSource(
                            applicationContext = this,
                            scope = applicationScope,
                        ),
                    ),
            )
        RoutedLocationSource(
            registry = registry,
            scope = applicationScope,
            initialType = LocationSourceType.FAKE,
        )
    }
}
