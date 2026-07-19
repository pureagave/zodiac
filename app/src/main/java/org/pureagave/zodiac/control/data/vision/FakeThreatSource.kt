package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.pureagave.zodiac.control.core.vision.DriverThreat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic thermal contacts for developing/demoing the DRIVER HUD before the
 * FLIR edge box exists: three moving contacts — a pedestrian crossing left↔right,
 * an approacher on a near-constant bearing whose size grows until it trips the
 * collision flag then resets (so the red-lock escalation is exercised on a loop),
 * and a far drifter. The per-tick geometry is a pure function ([demo]) so it's
 * unit-testable without coroutines.
 */
class FakeThreatSource(
    private val scope: CoroutineScope,
    private val tickMs: Long = DEFAULT_TICK_MS,
) : ThreatSource {
    private val _threats = MutableStateFlow<List<DriverThreat>>(emptyList())
    override val threats: StateFlow<List<DriverThreat>> = _threats.asStateFlow()

    // The demo is only ever consulted as a fallback, so its own liveness is
    // moot — report a constant "alive" to satisfy the interface.
    override val feedAlive: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    private var job: Job? = null

    override suspend fun start() {
        job?.cancel()
        job =
            scope.launch {
                var tick = 0
                while (isActive) {
                    _threats.value = demo(tick)
                    tick++
                    delay(tickMs)
                }
            }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        _threats.value = emptyList()
    }

    companion object {
        const val DEFAULT_TICK_MS = 100L
        private const val COLLISION_SIZE = 0.55f

        /** Deterministic moving contacts for [tick] (pure → unit-testable). */
        fun demo(tick: Int): List<DriverThreat> {
            val t = tick * (DEFAULT_TICK_MS / 1000.0)
            val crosserAz = (28.0 * sin(t * 0.4)).toFloat()
            // Approacher: bearing ~steady, size ramps 0.15→1.0 then wraps.
            val phase = (t * 0.15) % 1.0
            val appSize = (0.15 + 0.85 * phase).toFloat()
            val appAz = (-3.0 + 1.5 * sin(t * 0.9)).toFloat()
            val farAz = (18.0 * cos(t * 0.25)).toFloat()
            return listOf(
                DriverThreat(relAzDeg = crosserAz, size = 0.32f, collision = false, id = 1),
                DriverThreat(relAzDeg = appAz, size = appSize, collision = appSize > COLLISION_SIZE, id = 2),
                DriverThreat(relAzDeg = farAz, size = 0.18f, collision = false, id = 3),
            )
        }
    }
}
