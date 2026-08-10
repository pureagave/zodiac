package org.pureagave.zodiac.control

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
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
import org.pureagave.zodiac.control.core.geo.GoldenSpike
import org.pureagave.zodiac.control.core.log.RollingFileLog
import org.pureagave.zodiac.control.core.passenger.DisplayRoleStore
import org.pureagave.zodiac.control.core.sensor.LocationSourceType
import org.pureagave.zodiac.control.data.FakeTelemetryRepository
import org.pureagave.zodiac.control.data.RoutedVehicleGateway
import org.pureagave.zodiac.control.data.TelemetryRepository
import org.pureagave.zodiac.control.data.VehicleConnectionGateway
import org.pureagave.zodiac.control.data.art.ArtImageStore
import org.pureagave.zodiac.control.data.discovery.BmApiClient
import org.pureagave.zodiac.control.data.discovery.DiscoveryRepository
import org.pureagave.zodiac.control.data.log.FileLogTree
import org.pureagave.zodiac.control.data.playa.AssetsPlayaMapRepository
import org.pureagave.zodiac.control.data.playa.PlayaMapBinaryCache
import org.pureagave.zodiac.control.data.playa.PlayaMapRepository
import org.pureagave.zodiac.control.data.prefs.CockpitPreferences
import org.pureagave.zodiac.control.data.prefs.DataStoreCockpitPreferences
import org.pureagave.zodiac.control.data.prefs.cockpitPrefsDataStore
import org.pureagave.zodiac.control.data.sensor.BleLocationSource
import org.pureagave.zodiac.control.data.sensor.FailoverLocationSource
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
import timber.log.Timber
import java.io.File

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

    /**
     * The rolling postmortem log. Lives under `getExternalFilesDir("logs")` so
     * it comes off the tablet with a plain `adb pull` — no root, no debug
     * build required, which is the whole point when a fleet tablet has been
     * misbehaving in the dust for two days. Falls back to internal storage if
     * external is unavailable (unmounted, or a device without it).
     */
    val fileLog: RollingFileLog by lazy {
        RollingFileLog(dir = getExternalFilesDir("logs") ?: File(filesDir, "logs"))
    }

    private val fileLogTree: FileLogTree by lazy {
        FileLogTree(log = fileLog, scope = applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Timber.plant(fileLogTree)
        installCrashLogger()
        Timber.i(
            "boot: %s %s (%s), api %d, %s %s",
            BuildConfig.APPLICATION_ID,
            BuildConfig.VERSION_NAME,
            BuildConfig.BUILD_TYPE,
            Build.VERSION.SDK_INT,
            Build.MANUFACTURER,
            Build.MODEL,
        )
        Timber.i("boot: BRC year %d, log at %s", GoldenSpike.ACTIVE_YEAR, fileLog.currentFile.absolutePath)
    }

    /**
     * Record an uncaught exception *before* handing back to the platform
     * handler, which kills the process. Written synchronously — the async
     * drain would lose precisely the entry worth having. The previous handler
     * still runs, so the usual crash reporting/`am` behaviour is unchanged.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            fileLogTree.logBlocking(Log.ERROR, "Crash", "uncaught on thread '${thread.name}'", error)
            previous?.uncaughtException(thread, error)
        }
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

    /** Pre-rendered, pre-treated art images baked into the APK assets. */
    val artImages: ArtImageStore by lazy { ArtImageStore(assets) }

    val playaMapRepository: PlayaMapRepository by lazy {
        AssetsPlayaMapRepository(
            assets = assets,
            binaryCache = PlayaMapBinaryCache(cacheDir = cacheDir),
            year = GoldenSpike.ACTIVE_YEAR.toString(),
        )
    }

    private val preferencesDataStore: DataStore<Preferences> by lazy {
        cockpitPrefsDataStore(scope = applicationScope) {
            applicationContext.preferencesDataStoreFile("cockpit_prefs")
        }
    }

    val preferences: CockpitPreferences by lazy {
        DataStoreCockpitPreferences(preferencesDataStore)
    }

    /**
     * Whether this tablet is a passenger display. Process-scoped rather than
     * ViewModel state — it's a property of the device, not the session.
     */
    val displayRole: DisplayRoleStore by lazy {
        DisplayRoleStore(
            scope = applicationScope,
            read = { preferences.read().passengerMode },
            write = { preferences.setPassengerMode(it) },
        )
    }

    /**
     * The synthetic GPS source, exposed separately from the routed one so
     * the cockpit's debug nudge chips can drive its manual offset without
     * having to downcast through the registry.
     */
    val fakeLocationSource: FakeLocationSource by lazy { FakeLocationSource(scope = applicationScope) }

    /**
     * The shared-WiFi GPS + Sensor Hub receiver, hoisted out of the registry so
     * the cockpit can also read its low-rate beacon channels ([beaconSensors]) —
     * the same instance is used both in the routed source and by the ViewModel.
     */
    val networkLocationSource: NetworkLocationSource by lazy {
        NetworkLocationSource(applicationContext = this, scope = applicationScope)
    }

    /**
     * Does this tablet have its own GNSS receiver? The Samsung slates do (the
     * Tab S9+ carries GPS/GLONASS/BeiDou/Galileo/QZSS); the Fire tablets have
     * none at all, which is the reason the beacon exists in the first place.
     * Asked of the hardware rather than assumed per-model, so a new device in
     * the fleet gets the right behaviour without a code change.
     */
    private val hasOwnGnss: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    }

    /**
     * The beacon, wrapped in this tablet's own GNSS as an automatic backup.
     * Presents as the NET source, so selection and persistence are unaffected —
     * see [FailoverLocationSource].
     */
    val networkWithFailover: FailoverLocationSource by lazy {
        FailoverLocationSource(
            primary = networkLocationSource,
            fallback = SystemLocationSource(applicationContext = this, scope = applicationScope),
            scope = applicationScope,
            fallbackArmed = hasOwnGnss,
        )
    }

    val locationSource: RoutedLocationSource by lazy {
        val registry =
            LocationSourceRegistry(
                sources =
                    listOf(
                        fakeLocationSource,
                        SystemLocationSource(applicationContext = this, scope = applicationScope),
                        BleLocationSource(
                            applicationContext = this,
                            scope = applicationScope,
                        ),
                        UsbLocationSource(
                            applicationContext = this,
                            scope = applicationScope,
                        ),
                        // NET carries its own failover; SYSTEM stays separately
                        // selectable so it can still be chosen deliberately.
                        networkWithFailover,
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
            // Deployed vehicle: a dead feed must read ABSENT, never invent
            // people. The fake source stays wired so a bench build can flip
            // this without re-plumbing the graph.
            demoEnabled = false,
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
            // filesDir, not cacheDir: this is the only offline copy of art/camp
            // data for up to 14 unattended days, and Android is free to purge
            // cacheDir under storage pressure. Unlike PlayaMapBinaryCache (which
            // legitimately uses cacheDir because bundled JSON assets back it),
            // discovery has no fallback if this evaporates.
            storageDir = filesDir,
            // Active year. 2026 art/camp locations are embargoed until ~3 weeks
            // pre-event, so this returns nothing until BM releases them — no markers
            // now, auto-populating (correctly placed) on release. Matches the 2026
            // base map; no 2025 art shown ~583 m off the moved city.
            year = GoldenSpike.ACTIVE_YEAR,
        )
    }
}
