package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.VisionFeed
import timber.log.Timber

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

    /**
     * Tri-state feed health for the DRIVER HUD status line and ring rim —
     * distinct from [feedAlive] because "showing demo data" is neither live
     * nor absent. `demoEnabled=false` (deployed vehicle) means a dead feed
     * surfaces as [VisionFeed.ABSENT], never silently as [VisionFeed.DEMO].
     */
    val feedState: StateFlow<VisionFeed> =
        network.feedAlive
            .map { alive -> deriveFeedState(alive, demoEnabled) }
            .distinctUntilChanged()
            // The single most useful line in a DRIVER postmortem: whether the
            // HUD the driver was reading came from the edge box or the demo.
            // After distinctUntilChanged, so this logs transitions, not frames.
            .onEach { Timber.i("vision: feed %s", it) }
            .stateIn(scope, SharingStarted.Eagerly, deriveFeedState(network.feedAlive.value, demoEnabled))

    override suspend fun start() {
        Timber.i("vision: start (demo %s)", if (demoEnabled) "enabled" else "disabled")
        network.start()
        fake.start()
    }

    override suspend fun stop() {
        Timber.i("vision: stop")
        network.stop()
        fake.stop()
    }
}

private fun deriveFeedState(
    alive: Boolean,
    demoEnabled: Boolean,
): VisionFeed =
    when {
        alive -> VisionFeed.LIVE
        demoEnabled -> VisionFeed.DEMO
        else -> VisionFeed.ABSENT
    }
