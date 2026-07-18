package org.pureagave.zodiac.control.burnin

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.connection.ConnectionPhase
import org.pureagave.zodiac.control.core.connection.ConnectionState
import org.pureagave.zodiac.control.core.geo.LatLon
import org.pureagave.zodiac.control.core.sensor.GpsFix
import org.pureagave.zodiac.control.core.sensor.LocationSourceState
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Narrow persistence port for [BurnInConfig] — the manager's view of the
 * preferences layer, kept minimal so the manager stays decoupled from (and
 * testable without) `CockpitPreferences`.
 */
interface BurnInConfigStore {
    suspend fun read(): BurnInConfig

    suspend fun write(config: BurnInConfig)
}

/**
 * Process-lifetime state holder that drives OLED burn-in mitigation for the
 * cockpit display. Owns only the discrete [BurnInPhase] machine and the live
 * [BurnInConfig]; the Compose layer translates phase + config into the actual
 * pixel-shift offset, brightness layer, and window backlight.
 *
 * Idle is measured against an injectable [clock] (defaults to
 * [SystemClock.elapsedRealtime], which excludes deep-sleep drift and never
 * jumps with wall-clock changes), so the whole machine is unit-testable with a
 * fake clock and no real delays. All mutable state is confined to [scope]'s
 * dispatcher (`Dispatchers.Main.immediate` in production) plus the Compose main
 * thread, so the plain `var` fields need no synchronization.
 *
 * Activity — anything that should keep the screen awake — is three things:
 *  - **touch**, reported by the UI via [onUserInteraction];
 *  - **real GPS movement**, derived from [locationState] (a parked car
 *    re-emitting identical fixes does not count);
 *  - **a vehicle-link phase change**, derived from [connectionState].
 *
 * Depends only on the two state streams, not the whole source/gateway, so it
 * stays decoupled from the routing layer (`RoutedLocationSource` doesn't
 * implement `LocationSource`).
 */
class BurnInMitigationManager(
    locationState: StateFlow<LocationSourceState>,
    connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    /** Persisted-config port: read at startup, written on every tuning edit (null → in-memory only). */
    private val configStore: BurnInConfigStore? = null,
) {
    private val _config = MutableStateFlow(BurnInConfig())
    val config: StateFlow<BurnInConfig> = _config.asStateFlow()

    private val _phase = MutableStateFlow(BurnInPhase.ACTIVE)
    val phase: StateFlow<BurnInPhase> = _phase.asStateFlow()

    private var lastActivityMs: Long = clock()

    /** Reference fix that GPS displacement is measured against; resets on movement. */
    private var referenceFix: GpsFix? = null
    private var lastConnectionPhase: ConnectionPhase? = null

    init {
        configStore?.let { store ->
            scope.launch {
                _config.value = store.read().coerced()
                recomputePhase()
            }
        }
        scope.launch {
            locationState.collect { state ->
                if (state is LocationSourceState.Active) onGpsFix(state.fix)
            }
        }
        scope.launch {
            connectionState.collect { connection ->
                if (lastConnectionPhase != null && connection.phase != lastConnectionPhase) {
                    registerActivity()
                }
                lastConnectionPhase = connection.phase
            }
        }
        scope.launch {
            while (isActive) {
                recomputePhase()
                delay(tickMillis)
            }
        }
    }

    /** Report any touch/gesture on the cockpit — wakes to [BurnInPhase.ACTIVE]. */
    fun onUserInteraction() = registerActivity()

    /** Tuning-panel "WAKE" action — identical to a user touch. */
    fun wake() = registerActivity()

    /**
     * Manual park: fast-forwards the idle clock to the deep-idle threshold so
     * the CRT standby screen shows immediately, then escalates to [SLEEP] on the
     * normal deep→sleep timeline. Any subsequent activity cancels it.
     */
    fun enterPark() {
        lastActivityMs = clock() - _config.value.deepIdleTimeoutSec * MILLIS_PER_SEC
        recomputePhase()
    }

    /** Replace the live config (from the tuning panel); coerced, then persisted. */
    fun updateConfig(config: BurnInConfig) {
        val coerced = config.coerced()
        _config.value = coerced
        recomputePhase()
        configStore?.let { store -> scope.launch { store.write(coerced) } }
    }

    private fun registerActivity() {
        lastActivityMs = clock()
        recomputePhase()
    }

    private fun onGpsFix(fix: GpsFix) {
        val reference = referenceFix
        val cfg = _config.value
        val moved =
            when {
                (fix.speedKph ?: 0.0) >= cfg.movementSpeedKph -> true
                reference == null -> false
                else -> distanceMeters(reference.location, fix.location) >= cfg.movementMeters
            }
        if (moved || reference == null) referenceFix = fix
        if (moved) registerActivity()
    }

    private fun recomputePhase() {
        _phase.value = phaseForIdle(clock() - lastActivityMs, _config.value)
    }

    companion object {
        const val DEFAULT_TICK_MILLIS: Long = 10_000
        private const val MILLIS_PER_SEC: Long = 1_000

        /** Mean earth radius scaling — meters per degree of latitude. */
        private const val METERS_PER_DEGREE: Double = 111_320.0

        /**
         * Pure idle→phase mapping. Exposed (internal) so the state machine can be
         * exhaustively tested without coroutines or a clock.
         */
        internal fun phaseForIdle(
            idleMillis: Long,
            config: BurnInConfig,
        ): BurnInPhase =
            when {
                idleMillis >= config.sleepTimeoutSec * MILLIS_PER_SEC -> BurnInPhase.SLEEP
                idleMillis >= config.deepIdleTimeoutSec * MILLIS_PER_SEC -> BurnInPhase.DEEP_IDLE
                idleMillis >= config.dimTimeoutSec * MILLIS_PER_SEC -> BurnInPhase.DIM
                else -> BurnInPhase.ACTIVE
            }

        /**
         * Planar equirectangular distance in meters. Accurate to well under a
         * meter at the few-meter movement threshold near the Black Rock City
         * origin — the only place it's used.
         */
        internal fun distanceMeters(
            a: LatLon,
            b: LatLon,
        ): Double {
            val dLat = (a.lat - b.lat) * METERS_PER_DEGREE
            val dLon = (a.lon - b.lon) * METERS_PER_DEGREE * cos(Math.toRadians(a.lat))
            return hypot(dLat, dLon)
        }
    }
}
