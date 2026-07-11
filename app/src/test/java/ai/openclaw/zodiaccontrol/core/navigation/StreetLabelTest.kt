package ai.openclaw.zodiaccontrol.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreetLabelTest {
    @Test
    fun on_arc_reports_the_arc_name() {
        assertEquals("Hedge", NavigationCue.OnArc("Hedge", ClockTime(2, 15)).streetLabel())
    }

    @Test
    fun on_a_radial_inbound_reports_the_radial() {
        assertEquals("2:30", NavigationCue.OnRadialInbound("2:30", "Esplanade").streetLabel())
    }

    @Test
    fun driving_out_a_radial_reports_the_arc_just_crossed() {
        // So it ticks Esplanade -> A -> B ... as you pass each street outbound.
        assertEquals("Atwood", NavigationCue.OnRadialOutbound("2:30", "Atwood").streetLabel())
    }

    @Test
    fun off_street_has_no_label() {
        assertNull(NavigationCue.Unknown.streetLabel())
        assertNull(NavigationCue.TowardClock(ClockTime(6, 0), 500.0).streetLabel())
    }
}
