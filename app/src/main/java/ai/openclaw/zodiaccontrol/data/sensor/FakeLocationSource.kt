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
 * Synthetic GPS that the cockpit "drives" via the heading and speed chips
 * — the rail acts as a steering wheel + throttle, and on each tick the
 * source integrates motion (`pos += headingUnit · speed · dt`) and emits a
 * fresh [GpsFix] reflecting the new position with the configured heading
 * and speed. Initial state is parked at [center] with heading = 0,
 * speed = 0; nothing moves until the user taps a speed chip.
 *
 * The cockpit also has nudge / reset chips that teleport the parked
 * position around for testing known-location behaviour.
 */
class FakeLocationSource(
    private val scope: CoroutineScope,
    private val center: LatLon = GoldenSpike.Y2025,
    private val tickMillis: Long = DEFAULT_TICK_MS,
) : LocationSource {
    override val type: LocationSourceType = LocationSourceType.FAKE

    private val _state = MutableStateFlow<LocationSourceState>(LocationSourceState.Disconnected)
    override val state: StateFlow<LocationSourceState> = _state.asStateFlow()

    /**
     * Live ego position in playa metres (east, north) relative to [center].
     * Volatile because the ticker on the frame thread reads it while the
     * rail's chip handlers (also frame thread) write it; a non-tearing
     * read of each Double is enough — no locking needed.
     */
    @Volatile private var posEastM: Double = 0.0

    @Volatile private var posNorthM: Double = 0.0

    /** Driver-set heading and speed — replicated into every fix. */
    @Volatile private var headingDeg: Double = 0.0

    @Volatile private var speedKph: Double = 0.0

    private var job: Job? = null
    private val mutex = Mutex()

    override suspend fun start() =
        mutex.withLock {
            if (job?.isActive == true) return@withLock
            _state.value = LocationSourceState.Searching
            job =
                scope.launch {
                    while (true) {
                        integrate()
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

    /** Advance the parked position by speed × dt along the current heading. */
    private fun integrate() {
        if (speedKph <= 0.0) return
        val dt = tickMillis / MS_PER_SECOND
        val displacementM = speedKph * dt / KPH_TO_MPS
        val rad = Math.toRadians(headingDeg)
        posEastM += sin(rad) * displacementM
        posNorthM += cos(rad) * displacementM
    }

    private fun currentFix(): GpsFix {
        val dLat = posNorthM / METERS_PER_DEGREE
        val dLon = posEastM / METERS_PER_DEGREE / cos(Math.toRadians(center.lat))
        return GpsFix(
            location = LatLon(lon = center.lon + dLon, lat = center.lat + dLat),
            headingDeg = headingDeg.normaliseDegrees(),
            speedKph = speedKph,
            fixQualityM = SYNTHETIC_FIX_M,
        )
    }

    /**
     * Driver input: set the ego's heading. The next integration step will
     * advance position along this direction. Pushes an immediate fix so
     * the cockpit reflects the new heading without waiting for [tickMillis].
     */
    fun setHeading(deg: Double) {
        headingDeg = deg
        emitImmediateFix()
    }

    /** Driver input: set the ego's speed in km/h. */
    fun setSpeed(kph: Double) {
        speedKph = kph
        emitImmediateFix()
    }

    /**
     * Teleport the parked position by [deastM] east and [dnorthM] north —
     * convenient for testing arbitrary spots without "driving" there.
     */
    fun nudgeManualOffset(
        deastM: Double,
        dnorthM: Double,
    ) {
        posEastM += deastM
        posNorthM += dnorthM
        emitImmediateFix()
    }

    /** Reset position, heading, and speed to zero. */
    fun resetManualOffset() {
        posEastM = 0.0
        posNorthM = 0.0
        headingDeg = 0.0
        speedKph = 0.0
        emitImmediateFix()
    }

    private fun emitImmediateFix() {
        if (job?.isActive == true) _state.value = LocationSourceState.Active(currentFix())
    }

    private fun Double.normaliseDegrees(): Double = ((this % FULL_TURN_DEG) + FULL_TURN_DEG) % FULL_TURN_DEG

    companion object {
        const val DEFAULT_TICK_MS: Long = 500L
        const val SYNTHETIC_FIX_M: Double = 1.0
        private const val METERS_PER_DEGREE: Double = 111_320.0
        private const val MS_PER_SECOND: Double = 1_000.0

        // 1 m/s = 3.6 km/h, so dividing kph by 3.6 yields m/s.
        private const val KPH_TO_MPS: Double = 3.6
        private const val FULL_TURN_DEG: Double = 360.0
    }
}
