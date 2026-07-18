package org.pureagave.zodiac.control

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.burnin.BurnInConfig
import org.pureagave.zodiac.control.burnin.BurnInConfigStore
import org.pureagave.zodiac.control.burnin.BurnInMitigationManager
import org.pureagave.zodiac.control.core.connection.TransportType
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.FakeTelemetryRepository
import org.pureagave.zodiac.control.data.RoutedVehicleGateway
import org.pureagave.zodiac.control.data.TelemetryRepository
import org.pureagave.zodiac.control.data.VehicleConnectionGateway
import org.pureagave.zodiac.control.data.discovery.BmApiClient
import org.pureagave.zodiac.control.data.discovery.DiscoveryRepository
import org.pureagave.zodiac.control.data.playa.AssetsPlayaMapRepository
import org.pureagave.zodiac.control.data.playa.PlayaMapBinaryCache
import org.pureagave.zodiac.control.data.playa.PlayaMapRepository
import org.pureagave.zodiac.control.data.prefs.CockpitPreferences
import org.pureagave.zodiac.control.data.prefs.DataStoreCockpitPreferences
import org.pureagave.zodiac.control.data.sensor.BleLocationSource
import org.pureagave.zodiac.control.data.sensor.FakeLocationSource
import org.pureagave.zodiac.control.data.sensor.LocationSourceRegistry
import org.pureagave.zodiac.control.data.sensor.NetworkLocationSource
import org.pureagave.zodiac.control.data.sensor.RoutedLocationSource
import org.pureagave.zodiac.control.data.sensor.SystemLocationSource
import org.pureagave.zodiac.control.data.sensor.UsbLocationSource
import org.pureagave.zodiac.control.data.transport.FakeTransportAdapter
import org.pureagave.zodiac.control.data.transport.TransportRegistry
import org.pureagave.zodiac.control.data.vision.FakeThreatSource
import org.pureagave.zodiac.control.data.vision.NetworkThreatSource
import org.pureagave.zodiac.control.data.vision.RoutedThreatSource

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
                        NetworkLocationSource(applicationContext = this, scope = applicationScope),
                    ),
            )
        RoutedLocationSource(
            registry = registry,
            scope = applicationScope,
            initialType = LocationSourceType.FAKE,
        )
    }

    /**
     * Thermal contacts for the DRIVER HUD: prefers the network feed (the Jetson
     * edge box broadcasting detections on UDP 10120), falling back to a fake
     * moving demo when the feed is silent — so the HUD is always alive and
     * upgrades to real detections automatically. Started on first access.
     */
    val threatSource: RoutedThreatSource by lazy {
        RoutedThreatSource(
            network = NetworkThreatSource(applicationContext = this, scope = applicationScope),
            fake = FakeThreatSource(scope = applicationScope),
            scope = applicationScope,
        ).also { source -> applicationScope.launch { source.start() } }
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
