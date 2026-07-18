package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.pureagave.zodiac.control.core.vision.DriverThreat

/**
 * Prefers the real [network] threat feed (the Jetson edge box), falling back to
 * the [fake] demo feed whenever the network is silent (empty) — so the DRIVER
 * HUD is always alive on the bench, and automatically switches to real
 * detections the moment the edge box starts broadcasting, with no source
 * selection. Both underlying sources are started/stopped together.
 */
class RoutedThreatSource(
    private val network: ThreatSource,
    private val fake: ThreatSource,
    scope: CoroutineScope,
) : ThreatSource {
    override val threats: StateFlow<List<DriverThreat>> =
        combine(network.threats, fake.threats) { net, demo -> net.ifEmpty { demo } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun start() {
        network.start()
        fake.start()
    }

    override suspend fun stop() {
        network.stop()
        fake.stop()
    }
}
