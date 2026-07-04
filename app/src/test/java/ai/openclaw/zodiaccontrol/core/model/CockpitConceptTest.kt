package ai.openclaw.zodiaccontrol.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CockpitConceptTest {
    @Test
    fun next_cycles_through_all_concepts_and_wraps() {
        // Concept B was dropped; cycle is now A -> C -> D -> A.
        assertSame(CockpitConcept.C, CockpitConcept.A.next())
        assertSame(CockpitConcept.D, CockpitConcept.C.next())
        assertSame(CockpitConcept.A, CockpitConcept.D.next())
    }

    @Test
    fun entries_are_in_order() {
        assertEquals(
            listOf(
                CockpitConcept.A,
                CockpitConcept.C,
                CockpitConcept.D,
            ),
            CockpitConcept.entries,
        )
        assertEquals(3, CockpitConcept.entries.size)
    }

    @Test
    fun tags_match_each_concept() {
        assertEquals("A", CockpitConcept.A.tag)
        assertEquals("C", CockpitConcept.C.tag)
        assertEquals("D", CockpitConcept.D.tag)
    }

    @Test
    fun display_names_match_each_concept() {
        assertEquals("CRT VECTOR", CockpitConcept.A.displayName)
        assertEquals("TRACKER", CockpitConcept.C.displayName)
        assertEquals("BAY", CockpitConcept.D.displayName)
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
