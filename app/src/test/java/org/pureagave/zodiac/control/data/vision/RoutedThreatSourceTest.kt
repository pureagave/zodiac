package org.pureagave.zodiac.control.data.vision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pureagave.zodiac.control.core.vision.DriverThreat

class RoutedThreatSourceTest {
    private class StubThreatSource(initial: List<DriverThreat>) : ThreatSource {
        private val flow = MutableStateFlow(initial)
        override val threats: StateFlow<List<DriverThreat>> = flow

        override suspend fun start() = Unit

        override suspend fun stop() = Unit
    }

    @Test
    fun falls_back_to_the_fake_demo_when_the_network_is_silent() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val net = StubThreatSource(emptyList())
            val fake = StubThreatSource(listOf(DriverThreat(relAzDeg = 0f, size = 0.3f, id = 1)))
            val routed = RoutedThreatSource(net, fake, scope)
            assertEquals(1, await(routed).single().id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun prefers_the_network_feed_when_present() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val net = StubThreatSource(listOf(DriverThreat(relAzDeg = 5f, size = 0.9f, id = 7)))
            val fake = StubThreatSource(listOf(DriverThreat(relAzDeg = 0f, size = 0.3f, id = 1)))
            val routed = RoutedThreatSource(net, fake, scope)
            assertEquals(7, await(routed).single().id)
        } finally {
            scope.cancel()
        }
    }

    private fun await(source: RoutedThreatSource): List<DriverThreat> {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (source.threats.value.isNotEmpty()) return source.threats.value
            Thread.sleep(10)
        }
        return source.threats.value
    }
}
