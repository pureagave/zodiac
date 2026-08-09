package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.vision.DriverThreat
import org.pureagave.zodiac.control.core.vision.VisionFeed

class RoutedThreatSourceTest {
    private class StubThreatSource(
        initial: List<DriverThreat>,
        alive: Boolean = true,
    ) : ThreatSource {
        val flow = MutableStateFlow(initial)
        val aliveFlow = MutableStateFlow(alive)
        override val threats: StateFlow<List<DriverThreat>> = flow
        override val feedAlive: StateFlow<Boolean> = aliveFlow

        override suspend fun start() = Unit

        override suspend fun stop() = Unit
    }

    private val demo = DriverThreat(relAzDeg = 0f, size = 0.3f, id = 1)

    @Test
    fun prefers_the_network_feed_when_present() {
        withScope { scope ->
            val net = StubThreatSource(listOf(DriverThreat(relAzDeg = 5f, size = 0.9f, id = 7)), alive = true)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = true)
            assertEquals(7, awaitNonEmpty(routed).single().id)
        }
    }

    @Test
    fun falls_back_to_the_demo_when_the_feed_is_absent() {
        withScope { scope ->
            val net = StubThreatSource(emptyList(), alive = false)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = true)
            assertEquals(1, awaitNonEmpty(routed).single().id)
        }
    }

    /**
     * The safety-critical case: a running edge box that reports an empty "all
     * clear" (feedAlive = true, threats = empty) must NOT resurrect the demo's
     * fabricated contacts/collision. Regression guard for the `net.ifEmpty { demo }`
     * bug that painted fake BRAKE alarms whenever the real feed said the road was
     * clear.
     */
    @Test
    fun live_all_clear_does_not_resurrect_the_demo() {
        withScope { scope ->
            val net = StubThreatSource(listOf(DriverThreat(relAzDeg = 5f, size = 0.9f, id = 7)), alive = true)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = true)
            assertEquals(7, awaitNonEmpty(routed).single().id) // real contact showing
            net.flow.value = emptyList() // edge box now says "all clear", still live
            assertTrue("live all-clear must show no contacts, not the demo", awaitEmpty(routed))
        }
    }

    @Test
    fun production_mode_shows_all_clear_when_the_feed_dies() {
        withScope { scope ->
            val net = StubThreatSource(emptyList(), alive = false)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = false)
            // demoEnabled=false: an absent feed reads as all-clear, never the demo.
            assertTrue(awaitEmpty(routed))
        }
    }

    // -- feedState (1.5e) --------------------------------------------------------

    @Test
    fun feed_state_is_live_while_the_network_feed_is_alive() {
        withScope { scope ->
            val net = StubThreatSource(emptyList(), alive = true)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = true)
            assertEquals(VisionFeed.LIVE, awaitFeedState(routed) { it == VisionFeed.LIVE })
        }
    }

    @Test
    fun feed_state_is_demo_when_the_network_feed_is_absent_and_demo_is_enabled() {
        withScope { scope ->
            val net = StubThreatSource(emptyList(), alive = false)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = true)
            assertEquals(VisionFeed.DEMO, awaitFeedState(routed) { it == VisionFeed.DEMO })
        }
    }

    @Test
    fun feed_state_is_absent_on_a_deployed_vehicle_with_no_feed_never_demo() {
        // demoEnabled=false is the deployed-vehicle config. A crashed Jetson
        // must surface as ABSENT, not silently fall back to DEMO — the status
        // line and ring rim both key off this to avoid lying to the driver.
        withScope { scope ->
            val net = StubThreatSource(emptyList(), alive = false)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = false)
            assertEquals(VisionFeed.ABSENT, awaitFeedState(routed) { it == VisionFeed.ABSENT })
        }
    }

    @Test
    fun feed_state_flips_to_live_the_moment_the_network_feed_recovers() {
        withScope { scope ->
            val net = StubThreatSource(emptyList(), alive = false)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope, demoEnabled = true)
            awaitFeedState(routed) { it == VisionFeed.DEMO }
            net.aliveFlow.value = true
            assertEquals(VisionFeed.LIVE, awaitFeedState(routed) { it == VisionFeed.LIVE })
        }
    }

    private fun awaitFeedState(
        source: RoutedThreatSource,
        predicate: (VisionFeed) -> Boolean,
    ): VisionFeed {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(source.feedState.value)) return source.feedState.value
            Thread.sleep(10)
        }
        return source.feedState.value
    }

    private fun withScope(block: (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }

    private fun awaitNonEmpty(source: ThreatSource): List<DriverThreat> = awaitValue(source) { it.isNotEmpty() }

    private fun awaitEmpty(source: ThreatSource): Boolean = awaitValue(source) { it.isEmpty() }.isEmpty()

    private fun awaitValue(
        source: ThreatSource,
        predicate: (List<DriverThreat>) -> Boolean,
    ): List<DriverThreat> {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(source.threats.value)) return source.threats.value
            Thread.sleep(10)
        }
        return source.threats.value
    }
}
