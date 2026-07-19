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
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope)
            assertEquals(7, awaitNonEmpty(routed).single().id)
        }
    }

    @Test
    fun falls_back_to_the_demo_when_the_feed_is_absent() {
        withScope { scope ->
            val net = StubThreatSource(emptyList(), alive = false)
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope)
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
            val routed = RoutedThreatSource(net, StubThreatSource(listOf(demo)), scope)
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
