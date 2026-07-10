package ai.openclaw.zodiaccontrol

import ai.openclaw.zodiaccontrol.burnin.BurnInConfig
import ai.openclaw.zodiaccontrol.burnin.BurnInConfigStore
import ai.openclaw.zodiaccontrol.burnin.BurnInMitigationManager
import ai.openclaw.zodiaccontrol.core.connection.TransportType
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import ai.openclaw.zodiaccontrol.data.FakeTelemetryRepository
import ai.openclaw.zodiaccontrol.data.RoutedVehicleGateway
import ai.openclaw.zodiaccontrol.data.TelemetryRepository
import ai.openclaw.zodiaccontrol.data.VehicleConnectionGateway
import ai.openclaw.zodiaccontrol.data.discovery.BmApiClient
import ai.openclaw.zodiaccontrol.data.discovery.DiscoveryRepository
import ai.openclaw.zodiaccontrol.data.playa.AssetsPlayaMapRepository
import ai.openclaw.zodiaccontrol.data.playa.PlayaMapBinaryCache
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

    val playaMapRepository: PlayaMapRepository by lazy {
        AssetsPlayaMapRepository(
            assets = assets,
            binaryCache = PlayaMapBinaryCache(cacheDir = cacheDir),
        )
    }

    private val preferencesDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = applicationScope) {
            applicationContext.preferencesDataStoreFile("cockpit_prefs")
        }
    }

    val preferences: CockpitPreferences by lazy {
        DataStoreCockpitPreferences(preferencesDataStore)
    }

    /**
     * The synthetic GPS source, exposed separately from the routed one so
     * the cockpit's debug nudge chips can drive its manual offset without
     * having to downcast through the registry.
     */
    val fakeLocationSource: FakeLocationSource by lazy { FakeLocationSource(scope = applicationScope) }

    val locationSource: RoutedLocationSource by lazy {
        val registry =
            LocationSourceRegistry(
                sources =
                    listOf(
                        fakeLocationSource,
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

    /**
     * OLED burn-in mitigation state holder. Taps the same location/vehicle
     * flows the cockpit ViewModel uses (read-only) and drives the display's
     * idle-protection phases. Process-lifetime so the idle timer survives
     * Activity recreation.
     */
    val burnInManager: BurnInMitigationManager by lazy {
        BurnInMitigationManager(
            locationState = locationSource.state,
            connectionState = vehicleGateway.connectionState,
            scope = applicationScope,
            configStore =
                object : BurnInConfigStore {
                    override suspend fun read(): BurnInConfig = preferences.readBurnInConfig()

                    override suspend fun write(config: BurnInConfig) = preferences.setBurnInConfig(config)
                },
        )
    }

    /**
     * Playa discovery (art + camps) from the Burning Man API — offline-first:
     * serves its disk cache immediately, refreshes over Starlink when reachable,
     * and keeps the cache when offline. Process-lifetime so the cache survives
     * Activity recreation.
     */
    val discoveryRepository: DiscoveryRepository by lazy {
        DiscoveryRepository(
            source = BmApiClient(),
            scope = applicationScope,
            cacheDir = cacheDir,
            // 2025 = latest year with released locations; 2026 locations are
            // embargoed until ~3 weeks pre-event (and hidden from users per ToS).
            year = 2025,
        )
    }
}
