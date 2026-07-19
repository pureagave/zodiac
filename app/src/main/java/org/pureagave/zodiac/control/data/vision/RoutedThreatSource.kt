package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.pureagave.zodiac.control.core.vision.DriverThreat

/**
 * Prefers the real [network] threat feed (the Jetson edge box), falling back to
 * the [fake] demo feed only when the network feed is genuinely *absent* (never
 * seen, or gone stale) — NOT merely when it reports an empty "all clear". This
 * distinction is safety-critical: a live edge box reporting no contacts must
 * show no contacts, never the demo's fabricated collision alarms. So the DRIVER
 * HUD stays alive on the bench (no feed → demo) yet tells the truth on the road
 * (live all-clear → empty), switching to real detections automatically.
 *
 * [demoEnabled] gates the fallback: true (default) keeps the bench demo when no
 * feed is present; false is for a deployed vehicle, where an absent feed should
 * read as all-clear rather than fabricate contacts. Both underlying sources are
 * started/stopped together.
 */
class RoutedThreatSource(
    private val network: ThreatSource,
    private val fake: ThreatSource,
    scope: CoroutineScope,
    private val demoEnabled: Boolean = true,
) : ThreatSource {
    override val threats: StateFlow<List<DriverThreat>> =
        combine(network.feedAlive, network.threats, fake.threats) { alive, net, demo ->
            if (!alive && demoEnabled) demo else net
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val feedAlive: StateFlow<Boolean> = network.feedAlive

    override suspend fun start() {
        network.start()
        fake.start()
    }

    override suspend fun stop() {
        network.stop()
        fake.stop()
    }
}
