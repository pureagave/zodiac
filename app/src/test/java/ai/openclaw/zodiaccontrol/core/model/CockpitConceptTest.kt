package ai.openclaw.zodiaccontrol.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CockpitConceptTest {
    @Test
    fun next_cycles_and_wraps() {
        // Two concepts remain: RADAR <-> MAP.
        assertSame(CockpitConcept.MAP, CockpitConcept.RADAR.next())
        assertSame(CockpitConcept.RADAR, CockpitConcept.MAP.next())
    }

    @Test
    fun entries_are_in_order() {
        assertEquals(
            listOf(CockpitConcept.RADAR, CockpitConcept.MAP),
            CockpitConcept.entries,
        )
        assertEquals(2, CockpitConcept.entries.size)
    }

    @Test
    fun display_names_match_each_concept() {
        assertEquals("RADAR", CockpitConcept.RADAR.displayName)
        assertEquals("MAP", CockpitConcept.MAP.displayName)
    }

    @Test
    fun a_full_cycle_of_nexts_round_trips_to_start_from_any_concept() {
        for (start in CockpitConcept.entries) {
            var result = start
            repeat(CockpitConcept.entries.size) { result = result.next() }
            assertSame("cycling all concepts from $start must return to $start", start, result)
        }
    }
}
