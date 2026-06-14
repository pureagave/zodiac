package ai.openclaw.zodiaccontrol.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CockpitConceptTest {
    @Test
    fun next_cycles_through_all_concepts_and_wraps() {
        assertSame(CockpitConcept.B, CockpitConcept.A.next())
        assertSame(CockpitConcept.C, CockpitConcept.B.next())
        assertSame(CockpitConcept.D, CockpitConcept.C.next())
        assertSame(CockpitConcept.A, CockpitConcept.D.next())
    }

    @Test
    fun entries_are_in_order_and_size_four() {
        assertEquals(
            listOf(
                CockpitConcept.A,
                CockpitConcept.B,
                CockpitConcept.C,
                CockpitConcept.D,
            ),
            CockpitConcept.entries,
        )
        assertEquals(4, CockpitConcept.entries.size)
    }

    @Test
    fun tags_match_each_concept() {
        assertEquals("A", CockpitConcept.A.tag)
        assertEquals("B", CockpitConcept.B.tag)
        assertEquals("C", CockpitConcept.C.tag)
        assertEquals("D", CockpitConcept.D.tag)
    }

    @Test
    fun display_names_match_each_concept() {
        assertEquals("CRT VECTOR", CockpitConcept.A.displayName)
        assertEquals("PERSPECTIVE", CockpitConcept.B.displayName)
        assertEquals("TRACKER", CockpitConcept.C.displayName)
        assertEquals("BAY", CockpitConcept.D.displayName)
    }

    @Test
    fun four_nexts_round_trip_to_start_from_any_concept() {
        for (start in CockpitConcept.entries) {
            val result = start.next().next().next().next()
            assertSame("four next() calls from $start must return to $start", start, result)
        }
    }
}
