package ai.openclaw.zodiaccontrol.data.sensor

import ai.openclaw.zodiaccontrol.core.geo.GoldenSpike
import ai.openclaw.zodiaccontrol.core.geo.LatLon
import ai.openclaw.zodiaccontrol.core.sensor.GpsFix
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceState
import ai.openclaw.zodiaccontrol.core.sensor.LocationSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic GPS for development. Default path is a slow circle around the
 * Golden Spike at a small radius so the ego marker visibly moves on the map;
 * pass [pathRadiusMeters] = 0.0 for a stationary fix at [center].
 */
class FakeLocationSource(
    private val scope: CoroutineScope,
    private val center: LatLon = GoldenSpike.Y2025,
    private val pathRadiusMeters: Double = DEFAULT_PATH_RADIUS_M,
    private val periodSeconds: Double = DEFAULT_PERIOD_S,
    private val tickMillis: Long = DEFAULT_TICK_MS,
) : LocationSource {
    override val type: LocationSourceType = LocationSourceType.FAKE

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    private var job: Job? = null
    private var startTimeMs: Long = 0L
    private val mutex = Mutex()

    override suspend fun start() =
        mutex.withLock {
            if (job?.isActive == true) return@withLock
            startTimeMs = System.currentTimeMillis()
            _state.value = LocationSourceState.Searching
            job =
                scope.launch {
                    while (true) {
                        _state.value = LocationSourceState.Active(currentFix())
                        delay(tickMillis)
                    }
                }
        }

    override suspend fun stop() =
        mutex.withLock {
            job?.cancel()
            job = null
            _state.value = LocationSourceState.Disconnected
        }

    private fun currentFix(): GpsFix {
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1_000.0
        val angleRad = 2.0 * Math.PI * (elapsedSec / periodSeconds)
        // Convert path radius (meters) → degrees lon/lat near BRC's latitude.
        // 1° lat ≈ 111_320 m; 1° lon ≈ 111_320 * cos(lat) m.
        val dLat = (pathRadiusMeters / METERS_PER_DEGREE) * sin(angleRad)
        val dLon = (pathRadiusMeters / METERS_PER_DEGREE / cos(Math.toRadians(center.lat))) * cos(angleRad)
        return GpsFix(
            location = LatLon(lon = center.lon + dLon, lat = center.lat + dLat),
            headingDeg = Math.toDegrees(angleRad).normalizeDegrees(),
            speedKph = if (pathRadiusMeters > 0.0) circumferenceKph() else 0.0,
            fixQualityM = SYNTHETIC_FIX_M,
        )
    }

    private fun circumferenceKph(): Double {
        val circumferenceM = 2.0 * Math.PI * pathRadiusMeters
        val metersPerSecond = circumferenceM / periodSeconds
        return metersPerSecond * SECONDS_PER_HOUR / METERS_PER_KILOMETER
    }

    private fun Double.normalizeDegrees(): Double = ((this % FULL_TURN_DEG) + FULL_TURN_DEG) % FULL_TURN_DEG

    companion object {
        const val DEFAULT_PATH_RADIUS_M: Double = 200.0
        const val DEFAULT_PERIOD_S: Double = 60.0
        const val DEFAULT_TICK_MS: Long = 500L
        const val SYNTHETIC_FIX_M: Double = 1.0
        private const val METERS_PER_DEGREE: Double = 111_320.0
        private const val METERS_PER_KILOMETER: Double = 1_000.0
        private const val SECONDS_PER_HOUR: Double = 3_600.0
        private const val FULL_TURN_DEG: Double = 360.0
    }
}
